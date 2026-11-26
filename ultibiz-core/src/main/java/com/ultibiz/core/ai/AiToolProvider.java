package com.ultibiz.core.ai;

import java.util.List;

/**
 * The tools a module contributes to the assistant. A read tool answers at
 * once; a write tool returns a proposed action that a human has to confirm.
 */
public interface AiToolProvider {

    /** The module the tools belong to. */
    String module();

    /** The tools of this module. */
    List<AiTool> tools();

    /** One tool. */
    record AiTool(String name, String description, boolean write, List<String> keywords) {
        public AiTool {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }

        /** Does the question ask for this tool? */
        public boolean matches(String question) {
            if (question == null) {
                return false;
            }
            String lower = question.toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
            return false;
        }
    }
}
