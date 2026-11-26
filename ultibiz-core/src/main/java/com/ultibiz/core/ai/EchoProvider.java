package com.ultibiz.core.ai;

import org.springframework.stereotype.Component;

/**
 * The provider used when no model is configured (and in the tests): it echoes
 * the question, so the assistant, the audit and the conversation history can
 * be exercised without a model server.
 */
@Component
public class EchoProvider implements AiProvider {

    @Override
    public String code() {
        return "ECHO";
    }

    @Override
    public AiResponse complete(AiRequest request) throws AiException {
        StringBuilder answer = new StringBuilder();
        for (AiTurn turn : request.messages()) {
            if ("user".equals(turn.role())) {
                answer.append(turn.content());
                break;
            }
        }
        String content = "Echo: " + answer;
        int tokens = Math.max(1, content.length() / 4);
        return new AiResponse(content, tokens, tokens, request.model() == null ? "ultibiz-echo"
                : request.model());
    }
}
