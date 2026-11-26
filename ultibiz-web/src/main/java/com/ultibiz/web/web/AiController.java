package com.ultibiz.web.web;

import com.ultibiz.core.application.AiService;
import com.ultibiz.core.application.InsightService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The assistant: the chat (also as a stream), the proposed actions, the
 * conversations, the insights of the anomaly rules and the usage dashboard.
 */
@Controller
@RequestMapping("/ai")
public class AiController {

    private final AiService assistant;
    private final InsightService insights;
    private final SiteScopeProvider scopeProvider;

    public AiController(AiService assistant, InsightService insights, SiteScopeProvider scopeProvider) {
        this.assistant = assistant;
        this.insights = insights;
        this.scopeProvider = scopeProvider;
    }

    /** The body of a chat request. */
    public record ChatRequest(String question, String route, String entity, Map<String, String> filters,
                              Long conversationId) {
    }

    @PostMapping("/chat")
    @ResponseBody
    public Map<String, Object> chat(@RequestBody ChatRequest request,
                                     @AuthenticationPrincipal UltiBizUserPrincipal principal) {
        SiteScope scope = scopeProvider.current();
        AiService.ChatResult result = assistant.chat(scope, actor(principal), userId(principal),
                request.question(), request.route(), request.entity(),
                request.filters() == null ? Map.of() : request.filters(), request.conversationId());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("conversationId", result.conversationId());
        answer.put("answer", result.answer());
        answer.put("citations", result.citations());
        answer.put("degraded", result.degraded());
        answer.put("error", result.error());
        return answer;
    }

    /** The same answer, streamed. */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream(@RequestBody ChatRequest request,
                             @AuthenticationPrincipal UltiBizUserPrincipal principal) {
        SiteScope scope = scopeProvider.current();
        SseEmitter emitter = new SseEmitter(60_000L);
        new Thread(() -> {
            try {
                AiService.ChatResult result = assistant.chat(scope, actor(principal),
                        userId(principal), request.question(), request.route(), request.entity(),
                        request.filters() == null ? Map.of() : request.filters(),
                        request.conversationId());
                emitter.send(SseEmitter.event().name("meta").data(result.conversationId()));
                String answer = result.answer() == null ? "" : result.answer();
                for (int i = 0; i < answer.length(); i += 48) {
                    emitter.send(SseEmitter.event().name("token")
                            .data(answer.substring(i, Math.min(answer.length(), i + 48))));
                    Thread.sleep(12);
                }
                emitter.send(SseEmitter.event().name("citations").data(result.citations()));
                emitter.send(SseEmitter.event().name("done").data(result.degraded()));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.completeWithError(ex);
                } catch (Exception ignored) {
                    // the client disconnected
                }
            }
        }, "ai-stream").start();
        return emitter;
    }

    /** Prepares a write action: the proposal waits for a human. */
    @PostMapping("/actions/propose")
    @ResponseBody
    public Map<String, Object> propose(@RequestParam String tool,
                                        @RequestParam(required = false) Long conversationId,
                                        @RequestParam(required = false) String description,
                                        @RequestParam(required = false) String date,
                                        @RequestParam(required = false) String amount,
                                        @RequestParam(required = false) String debitAccount,
                                        @RequestParam(required = false) String creditAccount,
                                        @RequestParam(required = false) String currency,
                                        @AuthenticationPrincipal UltiBizUserPrincipal principal) {
        SiteScope scope = scopeProvider.current();
        long conversation = conversationId == null || conversationId <= 0
                ? assistant.conversations(scope, userId(principal), 1).stream().findFirst()
                        .map(com.ultibiz.core.infrastructure.AiRepository.Conversation::id).orElse(0L)
                : conversationId;
        Map<String, String> arguments = new LinkedHashMap<>();
        if (description != null) {
            arguments.put("description", description);
        }
        if (date != null) {
            arguments.put("date", date);
        }
        if (amount != null) {
            arguments.put("amount", amount);
        }
        if (debitAccount != null) {
            arguments.put("debitAccount", debitAccount);
        }
        if (creditAccount != null) {
            arguments.put("creditAccount", creditAccount);
        }
        if (currency != null) {
            arguments.put("currency", currency);
        }
        var call = assistant.propose(scope, actor(principal), conversation, tool, arguments);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", call.id());
        answer.put("status", call.status());
        answer.put("diff", call.diff());
        answer.put("conversationId", call.conversationId());
        return answer;
    }

    @PostMapping("/actions/{id}/confirm")
    @ResponseBody
    public Map<String, Object> confirm(@PathVariable long id,
                                       @AuthenticationPrincipal UltiBizUserPrincipal principal) {
        SiteScope scope = scopeProvider.current();
        var call = assistant.confirm(scope, actor(principal), id);
        return Map.of("id", call.id(), "status", call.status(),
                "result", call.result() == null ? "" : call.result());
    }

    @PostMapping("/actions/{id}/reject")
    @ResponseBody
    public Map<String, Object> reject(@PathVariable long id,
                                       @AuthenticationPrincipal UltiBizUserPrincipal principal) {
        SiteScope scope = scopeProvider.current();
        assistant.reject(scope, actor(principal), id);
        return Map.of("id", id, "status", "REJECTED");
    }

    @GetMapping("/conversations")
    @ResponseBody
    public List<?> conversations(@AuthenticationPrincipal UltiBizUserPrincipal principal) {
        return assistant.conversations(scopeProvider.current(), userId(principal), 20);
    }

    @GetMapping("/conversations/{id}/messages")
    @ResponseBody
    public List<?> messages(@PathVariable long id) {
        return assistant.messages(scopeProvider.current(), id);
    }

    @GetMapping("/insights")
    @ResponseBody
    public List<?> insightList(@RequestParam(required = false) String status) {
        return insights.alerts(scopeProvider.current(), status, 100);
    }

    @PostMapping("/insights/{id}/acknowledge")
    @ResponseBody
    public Map<String, Object> acknowledge(@PathVariable long id,
                                           @RequestParam(defaultValue = "ACKNOWLEDGED") String status,
                                           @AuthenticationPrincipal UltiBizUserPrincipal principal) {
        SiteScope scope = scopeProvider.current();
        insights.acknowledge(scope, actor(principal), id, status);
        return Map.of("id", id, "status", status);
    }

    @PostMapping("/insights/run")
    @ResponseBody
    public Map<String, Object> runInsights(@AuthenticationPrincipal UltiBizUserPrincipal principal) {
        SiteScope scope = scopeProvider.current();
        int found = insights.run(scope);
        return Map.of("findings", found);
    }

    /** The assistant board: the insights and the usage of the organisation. */
    @GetMapping
    public String board(@AuthenticationPrincipal UltiBizUserPrincipal principal, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("insights", insights.alerts(scope, null, 50));
        model.addAttribute("usage", assistant.usage(scope));
        model.addAttribute("config", assistant.config(scope));
        model.addAttribute("conversations", assistant.conversations(scope, userId(principal), 10));
        return "ai/board";
    }

    @GetMapping("/usage")
    public String usage(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("usage", assistant.usage(scope));
        return "ai/usage";
    }

    @PostMapping("/config")
    public String saveConfig(@RequestParam String provider,
                             @RequestParam(required = false) String baseUrl,
                             @RequestParam(required = false) String model,
                             @RequestParam(required = false) String apiKey,
                             @RequestParam(defaultValue = "0.2") double temperature,
                             @RequestParam(defaultValue = "30000") int timeoutMs,
                             @RequestParam(defaultValue = "1024") int maxTokens,
                             @RequestParam(defaultValue = "false") boolean allowExternal,
                             @RequestParam(required = false) String systemPrompt,
                             @AuthenticationPrincipal UltiBizUserPrincipal principal,
                             RedirectAttributes redirect) {
        try {
            assistant.saveConfig(scopeProvider.current(), actor(principal), provider, baseUrl, model,
                    apiKey, temperature, timeoutMs, maxTokens, allowExternal, systemPrompt);
            redirect.addFlashAttribute("flash", "ai.configSaved");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/ai";
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }

    private static String userId(UltiBizUserPrincipal principal) {
        return principal != null ? principal.getUsername() : "anonymous";
    }
}
