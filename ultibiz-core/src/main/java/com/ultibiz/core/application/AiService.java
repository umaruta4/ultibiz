package com.ultibiz.core.application;

import com.ultibiz.core.ai.AiException;
import com.ultibiz.core.ai.AiGateway;
import com.ultibiz.core.ai.AiProvider;
import com.ultibiz.core.ai.AiProviderConfig;
import com.ultibiz.core.ai.AiToolProvider;
import com.ultibiz.core.ai.ContextEnvelope;
import com.ultibiz.core.ai.KnowledgeService;
import com.ultibiz.core.ai.PiiRedactor;
import com.ultibiz.core.ai.ProposedAction;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.JournalEntry;
import com.ultibiz.core.infrastructure.AiRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The assistant: it answers a question with the ERP data and the knowledge
 * base, runs the read tools of the modules that match the question and turns
 * a write tool into a proposal a human has to confirm.
 *
 * <p>Every prompt, answer and tool call is stored, with the model, the token
 * usage and the latency. A provider outage never breaks the page: the answer
 * becomes a readable message and the rest of the application keeps working.
 */
@Service
public class AiService {

    /** One citation of the knowledge base. */
    public record Citation(int index, String documentCode, String documentTitle, String source,
                           String chunk, double distance) {
    }

    /** The answer of the assistant. */
    public record ChatResult(long conversationId, String answer, List<Citation> citations,
                             boolean degraded, String error) {
    }

    private final AiGateway gateway;
    private final AiRepository ai;
    private final KnowledgeService knowledge;
    private final List<AiToolProvider> toolProviders;
    private final AccountingAiToolProvider accountingTools;
    private final JournalService journals;
    private final AccountService accounts;
    private final AuditService audit;

    public AiService(AiGateway gateway, AiRepository ai, KnowledgeService knowledge,
                     List<AiToolProvider> toolProviders, AccountingAiToolProvider accountingTools,
                     JournalService journals, AccountService accounts, AuditService audit) {
        this.gateway = gateway;
        this.ai = ai;
        this.knowledge = knowledge;
        this.toolProviders = toolProviders;
        this.accountingTools = accountingTools;
        this.journals = journals;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** The country of the organisation — it decides the PII rules. */
    private String countryOf(SiteScope scope) {
        return "ID";
    }

    /**
     * Answers a question. The screen context travels with the question as a
     * structured envelope — never as raw HTML.
     */
    public ChatResult chat(SiteScope scope, String actor, String userId, String question, String route,
                           String entity, Map<String, String> filters, Long conversationId) {
        String redacted = PiiRedactor.redact(question, countryOf(scope));
        long conversation = conversationId != null && conversationId > 0
                ? conversationId
                : ai.conversation(scope.organizationId(), userId, title(redacted), route);
        ai.message(conversation, "user", redacted, null, null, 0, 0, 0, 0);

        StringBuilder context = new StringBuilder();
        ContextEnvelope envelope = new ContextEnvelope(route, entity, null, filters);
        context.append("Screen context: ").append(envelope.describe()).append('\n');

        List<Citation> citations = new ArrayList<>();
        KnowledgeService.Retrieval retrieval = knowledge.search(scope, question, 5, 0.62);
        if (retrieval.found()) {
            context.append("Knowledge base:\n");
            int index = 1;
            for (KnowledgeService.Chunk chunk : retrieval.chunks()) {
                context.append('[').append(index).append("] ").append(chunk.content()).append('\n');
                citations.add(new Citation(index, chunk.documentCode(), chunk.documentTitle(),
                        chunk.source(), chunk.content(), chunk.distance()));
                index++;
            }
        } else {
            context.append("Knowledge base: no matching document.\n");
        }

        // the read tools of the modules that match the question
        for (AiToolProvider provider : toolProviders) {
            for (AiToolProvider.AiTool tool : provider.tools()) {
                if (tool.write() || !tool.matches(question)) {
                    continue;
                }
                AccountingAiToolProvider.ToolResult result = accountingTools.read(scope, tool.name(),
                        Map.of());
                ai.toolCall(conversation, null, tool.name(), "READ", "{}", "EXECUTED", null,
                        result.summary());
                context.append("Tool ").append(tool.name()).append(":\n")
                        .append(result.summary()).append('\n');
            }
        }

        List<AiProvider.AiTurn> turns = List.of(new AiProvider.AiTurn("user",
                question + "\n\n" + context));
        long started = System.currentTimeMillis();
        try {
            AiProvider.AiResponse response = gateway.complete(scope.organizationId(), turns);
            int latency = (int) (System.currentTimeMillis() - started);
            ai.message(conversation, "assistant", response.content(),
                    ai.config(scope.organizationId()) == null ? null
                            : ai.config(scope.organizationId()).provider(),
                    response.model(), response.promptTokens(), response.completionTokens(),
                    0.0, latency);
            audit.record(AuditEvent.of(scope.organizationId(), actor, "AI_CHAT", "ai_conversation",
                    String.valueOf(conversation), "{\"citations\":" + citations.size() + "}", null));
            return new ChatResult(conversation, response.content(), citations, false, null);
        } catch (AiException ex) {
            // the provider is down: the assistant degrades, the app does not
            String message = "The assistant is not available right now: " + ex.getMessage();
            ai.message(conversation, "assistant", message, null, null, 0, 0, 0, 0);
            return new ChatResult(conversation, message, citations, true, ex.getMessage());
        }
    }

    /**
     * Prepares a write action. The action is stored as a proposal with the
     * diff; nothing is written until a human confirms it.
     */
    public AiRepository.ToolCall propose(SiteScope scope, String actor, long conversationId,
                                         String toolName, Map<String, String> arguments) {
        ProposedAction action;
        if ("draft_journal".equals(toolName)) {
            action = draftJournal(scope, arguments);
        } else {
            throw new OrderException("Unknown write tool: " + toolName);
        }
        long id = ai.toolCall(conversationId, null, action.toolName(), "WRITE",
                toJson(arguments), "PROPOSED", String.join("\n", action.diffLines()),
                action.summary());
        audit.record(AuditEvent.of(scope.organizationId(), actor, "AI_ACTION_PROPOSED",
                "ai_tool_call", String.valueOf(id), "{\"conversationId\":" + conversationId
                + ",\"tool\":\"" + toolName + "\"}", null));
        return ai.toolCall(scope.organizationId(), id);
    }

    /** Confirms a proposal: the action runs through the same service as the UI. */
    public AiRepository.ToolCall confirm(SiteScope scope, String actor, long toolCallId) {
        AiRepository.ToolCall call = ai.toolCall(scope.organizationId(), toolCallId);
        if (call == null) {
            throw new OrderException("The action does not exist");
        }
        if (!"PROPOSED".equals(call.status())) {
            throw new OrderException("The action was already " + call.status().toLowerCase());
        }
        Map<String, String> arguments = parseArguments(call.arguments());
        if ("draft_journal".equals(call.toolName())) {
            String description = arguments.getOrDefault("description", "Drafted by the assistant");
            LocalDate date = arguments.containsKey("date")
                    ? LocalDate.parse(arguments.get("date")) : LocalDate.now();
            BigDecimal amount = new BigDecimal(arguments.getOrDefault("amount", "0"));
            long debit = accountId(scope, arguments.get("debitAccount"));
            long credit = accountId(scope, arguments.get("creditAccount"));
            List<JournalLineDraft> lines = List.of(
                    new JournalLineDraft(debit, null, null, description, amount, BigDecimal.ZERO,
                            null, null),
                    new JournalLineDraft(credit, null, null, description, BigDecimal.ZERO, amount,
                            null, null));
            JournalEntry journal = journals.post(scope, actor, new JournalDraft(countryOf(scope), null,
                    "MANUAL", date, arguments.getOrDefault("currency", "IDR"), BigDecimal.ONE,
                    null, "ai_journal", null, null, description, lines));
            ai.confirmToolCall(toolCallId, "CONFIRMED",
                    "journal " + journal.journalNumber() + " posted", actor);
            audit.record(AuditEvent.of(scope.organizationId(), actor, "AI_ACTION_EXECUTED",
                    "ai_tool_call", String.valueOf(toolCallId),
                    "{\"conversationId\":" + call.conversationId() + ",\"journal\":\""
                            + journal.journalNumber() + "\"}", null));
        } else {
            ai.confirmToolCall(toolCallId, "FAILED", "The tool cannot be executed", actor);
        }
        return ai.toolCall(scope.organizationId(), toolCallId);
    }

    /** Rejects a proposal. */
    public void reject(SiteScope scope, String actor, long toolCallId) {
        AiRepository.ToolCall call = ai.toolCall(scope.organizationId(), toolCallId);
        if (call == null) {
            throw new OrderException("The action does not exist");
        }
        ai.confirmToolCall(toolCallId, "REJECTED", "rejected by " + actor, actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "AI_ACTION_REJECTED",
                "ai_tool_call", String.valueOf(toolCallId),
                "{\"conversationId\":" + call.conversationId() + "}", null));
    }

    public List<AiRepository.Conversation> conversations(SiteScope scope, String userId, int limit) {
        return ai.conversations(scope.organizationId(), userId, limit);
    }

    public List<AiRepository.Message> messages(SiteScope scope, long conversationId) {
        return ai.messages(conversationId);
    }

    public Map<String, Object> usage(SiteScope scope) {
        return ai.usage(scope.organizationId());
    }

    public AiProviderConfig config(SiteScope scope) {
        return ai.config(scope.organizationId());
    }

    public void saveConfig(SiteScope scope, String actor, String provider, String baseUrl, String model,
                           String apiKey, double temperature, int timeoutMs, int maxTokens,
                           boolean allowExternal, String systemPrompt) {
        ai.saveConfig(scope.organizationId(), provider, baseUrl, model, apiKey, temperature,
                timeoutMs, maxTokens, allowExternal, systemPrompt);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "AI_CONFIG", "ai_provider_config",
                String.valueOf(scope.organizationId()), "{\"provider\":\"" + provider + "\"}", null));
    }

    // -- helpers -----------------------------------------------------------------

    private ProposedAction draftJournal(SiteScope scope, Map<String, String> arguments) {
        String description = arguments.getOrDefault("description", "Drafted by the assistant");
        LocalDate date = arguments.containsKey("date")
                ? LocalDate.parse(arguments.get("date")) : LocalDate.now();
        BigDecimal amount = new BigDecimal(arguments.getOrDefault("amount", "0"));
        String debit = arguments.getOrDefault("debitAccount", "1300");
        String credit = arguments.getOrDefault("creditAccount", "2100");
        List<String> diff = List.of(
                "+ debit  " + debit + "  " + amount,
                "+ credit " + credit + "  " + amount,
                "+ date   " + date,
                "+ description " + description);
        Map<String, String> before = new LinkedHashMap<>();
        before.put("journal", "none");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("debitAccount", debit);
        after.put("creditAccount", credit);
        after.put("amount", amount.toPlainString());
        after.put("date", date.toString());
        after.put("description", description);
        return new ProposedAction("draft_journal",
                "Draft a journal of " + amount + " " + arguments.getOrDefault("currency", "IDR"),
                before, after, diff, "JournalService.post");
    }

    private long accountId(SiteScope scope, String code) {
        if (code == null) {
            throw new OrderException("The action needs a debit and a credit account");
        }
        var account = accounts.byCode(scope, code);
        if (account == null) {
            throw new OrderException("Unknown account: " + code);
        }
        return account.id();
    }

    private static String toJson(Map<String, String> arguments) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception ex) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseArguments(String json) {
        try {
            Map<String, String> parsed = com.fasterxml.jackson.databind.json.JsonMapper
                    .builder().build().readValue(json == null ? "{}" : json, Map.class);
            return parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String title(String question) {
        if (question == null || question.isBlank()) {
            return "Conversation";
        }
        return question.length() > 80 ? question.substring(0, 80) : question;
    }
}
