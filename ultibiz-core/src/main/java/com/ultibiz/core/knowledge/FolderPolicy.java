package com.ultibiz.core.knowledge;

import java.util.List;

/** The folder rules: the tree has no cycle and stays shallow. */
public final class FolderPolicy {

    public static final int MAX_DEPTH = 5;

    private FolderPolicy() {
    }

    /** The parents of a folder (the id and the code of every ancestor). */
    public record Node(long id, Long parentId, String code) {
    }

    /** A folder may not become its own ancestor. */
    public static void checkMove(Node target, List<Node> all) {
        if (target == null) {
            throw new KnowledgeException("The folder does not exist");
        }
        Long parentId = target.parentId();
        int depth = 1;
        while (parentId != null) {
            if (parentId == target.id()) {
                throw new KnowledgeException("A folder cannot be its own ancestor");
            }
            final Long current = parentId;
            Node parent = all.stream().filter(node -> node.id() == current).findFirst()
                    .orElse(null);
            if (parent == null) {
                break;      // the parent is outside the tree — nothing to check
            }
            parentId = parent.parentId();
            depth++;
            if (depth > MAX_DEPTH) {
                throw new KnowledgeException("The folder tree is deeper than "
                        + MAX_DEPTH + " levels");
            }
        }
    }
}
