package com.ultibiz.core.application;

import com.ultibiz.core.domain.Account;
import com.ultibiz.core.infrastructure.AccountRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Chart-of-accounts use cases. */
@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public List<Account> list(SiteScope scope, boolean activeOnly) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return accounts.list(scope.organizationId(), activeOnly);
    }

    /** Accounts that may be posted to, for the journal editor. */
    public List<Account> postable(SiteScope scope) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return accounts.postable(scope.organizationId());
    }

    public Account byCode(SiteScope scope, String code) {
        if (scope.isDenyAll()) {
            return null;
        }
        return accounts.findByCode(scope.organizationId(), code);
    }

    public Account byId(SiteScope scope, long id) {
        if (scope.isDenyAll()) {
            return null;
        }
        return accounts.findById(scope.organizationId(), id);
    }

    /** The chart of accounts as a tree (parents with their children). */
    public List<AccountNode> tree(SiteScope scope, boolean activeOnly) {
        List<Account> flat = list(scope, activeOnly);
        Map<Long, AccountNode> nodes = new LinkedHashMap<>();
        for (Account a : flat) {
            nodes.put(a.id(), new AccountNode(a, new ArrayList<>()));
        }
        List<AccountNode> roots = new ArrayList<>();
        for (Account a : flat) {
            AccountNode node = nodes.get(a.id());
            AccountNode parent = a.parentId() == null ? null : nodes.get(a.parentId());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.children().add(node);
            }
        }
        return roots;
    }

    /** A node of the chart-of-accounts tree. */
    public record AccountNode(Account account, List<AccountNode> children) {
    }

    /** A flat row of the tree: the account plus its depth (0 = root). */
    public record AccountRow(Account account, int depth) {
    }

    /**
     * The chart of accounts as a flat, ordered list with a depth per row.
     * A flat list keeps the view simple (no recursive template fragments) and
     * keeps the tree order.
     */
    public List<AccountRow> rows(SiteScope scope, boolean activeOnly) {
        return flatten(tree(scope, activeOnly), 0);
    }

    private List<AccountRow> flatten(List<AccountNode> nodes, int depth) {
        List<AccountRow> rows = new ArrayList<>();
        for (AccountNode node : nodes) {
            rows.add(new AccountRow(node.account(), depth));
            rows.addAll(flatten(node.children(), depth + 1));
        }
        return rows;
    }
}
