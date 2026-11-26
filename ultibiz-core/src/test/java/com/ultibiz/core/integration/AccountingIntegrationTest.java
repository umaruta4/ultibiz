package com.ultibiz.core.integration;

import com.ultibiz.core.application.AccountingException;
import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.FiscalPeriodService;
import com.ultibiz.core.application.FxService;
import com.ultibiz.core.application.JournalDraft;
import com.ultibiz.core.application.JournalLineDraft;
import com.ultibiz.core.application.JournalService;
import com.ultibiz.core.application.NumberingService;
import com.ultibiz.core.application.PermissionGate;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.domain.FiscalPeriod;
import com.ultibiz.core.domain.JournalEntry;
import com.ultibiz.core.domain.JournalLine;
import com.ultibiz.core.infrastructure.AccountRepository;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.CountryConfigRepository;
import com.ultibiz.core.infrastructure.FiscalPeriodRepository;
import com.ultibiz.core.infrastructure.FxRateRepository;
import com.ultibiz.core.infrastructure.JournalRepository;
import com.ultibiz.core.infrastructure.NumberingRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the posting engine against a real MariaDB.
 *
 * <pre>
 * mvn -B verify -Dultibiz.it.jdbcUrl=jdbc:mariadb://localhost:3311/ultibiz_test \
 *               -Dultibiz.it.user=ultibiz -Dultibiz.it.password=...
 * </pre>
 */
class AccountingIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 15);

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static long orgId;
    private static long siteId;
    private static long cashAccount;
    private static long salesAccount;
    private static long receivableAccount;
    private static SiteScope scope;

    @BeforeAll
    static void prepareDatabase() {
        String url = System.getProperty("ultibiz.it.jdbcUrl");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "integration tests need -Dultibiz.it.jdbcUrl (skipped)");
        String user = System.getProperty("ultibiz.it.user", "ultibiz");
        String password = System.getProperty("ultibiz.it.password", "");

        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(url, user, password)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        dsl = DSL.using(dataSource, SQLDialect.MARIADB);

        orgId = ((Number) dsl.fetchValue("SELECT id FROM organization WHERE code = 'ACME'")).longValue();
        siteId = ((Number) dsl.fetchValue("SELECT id FROM site WHERE code = 'JKT1'")).longValue();
        cashAccount = accountId("1110");
        salesAccount = accountId("4100");
        receivableAccount = accountId("1200");
        scope = new SiteScope(orgId, Set.of(siteId), true);
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static long accountId(String code) {
        return ((Number) dsl.fetchValue("SELECT id FROM account WHERE code = ?", code)).longValue();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private JournalService journalService() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        NumberingService numbering = new NumberingService(new NumberingRepository(jdbc), tx());
        FxService fx = new FxService(new FxRateRepository(jdbc), List.of());
        return new JournalService(new JournalRepository(jdbc), new FiscalPeriodRepository(jdbc),
                new AccountRepository(jdbc), new CountryConfigRepository(dsl), numbering,
                new AuditService(new AuditRepository(dsl)), fx, tx());
    }

    private FiscalPeriodService periodService(PermissionGate gate) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new FiscalPeriodService(new FiscalPeriodRepository(jdbc),
                new AuditService(new AuditRepository(dsl)), gate);
    }

    private JournalDraft draft(List<JournalLineDraft> lines) {
        return new JournalDraft("ID", siteId, "GENERAL", DATE, "IDR", BigDecimal.ONE,
                "REF-TEST", null, null, null, "integration test", lines);
    }

    private JournalLineDraft debit(long account, String amount) {
        return new JournalLineDraft(account, null, siteId, "debit line",
                new BigDecimal(amount), BigDecimal.ZERO, null, null);
    }

    private JournalLineDraft credit(long account, String amount) {
        return new JournalLineDraft(account, null, siteId, "credit line",
                BigDecimal.ZERO, new BigDecimal(amount), null, null);
    }

    @Test
    void balancedJournalIsPostedAndStoredBalanced() {
        JournalService service = journalService();

        JournalEntry posted = service.post(scope, "it", draft(List.of(
                debit(cashAccount, "150000.00"),
                credit(salesAccount, "150000.00"))));

        assertNotNull(posted.id());
        assertTrue(posted.journalNumber().startsWith("JV-"), posted.journalNumber());
        assertEquals(0, posted.totalDebit().compareTo(new BigDecimal("150000.00")));
        assertEquals(0, posted.totalCredit().compareTo(new BigDecimal("150000.00")));
        assertTrue(posted.balanced());

        List<JournalLine> lines = service.lines(scope, posted.id());
        assertEquals(2, lines.size());
        assertEquals(0, lines.get(0).debit().compareTo(new BigDecimal("150000.00")));
        assertEquals(0, lines.get(1).credit().compareTo(new BigDecimal("150000.00")));

        Number audits = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'JOURNAL_POST' AND entity_id = ?",
                String.valueOf(posted.id()));
        assertEquals(1L, audits.longValue(), "posting must be audited");
    }

    @Test
    void unbalancedJournalIsRejectedAndNothingIsWritten() {
        JournalService service = journalService();
        long before = ((Number) dsl.fetchValue("SELECT COUNT(*) FROM journal_entry")).longValue();

        assertThrows(AccountingException.class, () -> service.post(scope, "it", draft(List.of(
                debit(cashAccount, "150000.00"),
                credit(salesAccount, "140000.00")))));

        long after = ((Number) dsl.fetchValue("SELECT COUNT(*) FROM journal_entry")).longValue();
        assertEquals(before, after, "an unbalanced journal must not be stored");
    }

    @Test
    void aLineCannotCarryBothADebitAndACredit() {
        JournalService service = journalService();
        JournalLineDraft broken = new JournalLineDraft(cashAccount, null, siteId, "broken",
                new BigDecimal("100.00"), new BigDecimal("100.00"), null, null);

        assertThrows(AccountingException.class,
                () -> service.post(scope, "it", draft(List.of(broken))));
    }

    @Test
    void randomJournalsAlwaysBalance() {
        JournalService service = journalService();
        Random random = new Random(20261125L);

        for (int i = 0; i < 50; i++) {
            BigDecimal amount = BigDecimal.valueOf(random.nextInt(9_999_900) + 100)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            JournalEntry posted = service.post(scope, "it", draft(List.of(
                    debit(cashAccount, amount.toPlainString()),
                    credit(salesAccount, amount.toPlainString()))));
            assertTrue(posted.balanced(),
                    "journal " + posted.journalNumber() + " must balance: "
                            + posted.totalDebit() + " vs " + posted.totalCredit());
            assertEquals(0, posted.totalDebit().compareTo(posted.totalCredit()));
        }
    }

    @Test
    void reversalRestoresTheBalancesExactly() {
        JournalService service = journalService();

        JournalEntry original = service.post(scope, "it", draft(List.of(
                debit(receivableAccount, "750000.00"),
                credit(salesAccount, "750000.00"))));

        BigDecimal balanceBefore = netBalance();

        JournalEntry reversal = service.reverse(scope, "it", original.id());
        assertTrue(reversal.reversesJournalId() != null
                && reversal.reversesJournalId() == original.id());
        assertEquals("REVERSAL", reversal.journalType());
        assertTrue(reversal.balanced());

        JournalEntry reloaded = service.byId(scope, original.id());
        assertEquals("REVERSED", reloaded.status());

        assertEquals(0, balanceBefore.compareTo(netBalance()),
                "after the reversal the net balance must be the same as before the original posting");
        assertEquals(0, netBalance().compareTo(BigDecimal.ZERO),
                "a posted journal and its reversal must cancel out");
    }

    @Test
    void closedPeriodRejectsPosting() {
        JournalService service = journalService();
        FiscalPeriodService periods = periodService(code -> true);

        FiscalPeriod period = periods.list(scope, "ID").stream()
                .filter(p -> p.fiscalYear() == 2026 && p.fiscalMonth() == 9)
                .findFirst()
                .orElseThrow();
        periods.close(scope, "it", period.id());

        // a posting dated inside the closed month must be refused
        JournalDraft inClosedMonth = new JournalDraft("ID", siteId, "GENERAL",
                LocalDate.of(2026, 9, 10), "IDR", BigDecimal.ONE, "REF-CLOSED", null, null, null,
                "closed period", List.of(debit(cashAccount, "1000.00"), credit(salesAccount, "1000.00")));
        assertThrows(AccountingException.class, () -> service.post(scope, "it", inClosedMonth));

        FiscalPeriod reloaded = periods.list(scope, "ID").stream()
                .filter(p -> p.id() == period.id())
                .findFirst()
                .orElseThrow();
        assertEquals("CLOSED", reloaded.status().name());
    }

    @Test
    void reopeningAPeriodNeedsThePermission() {
        FiscalPeriodService periods = periodService(code -> false);
        FiscalPeriod period = periods.list(scope, "ID").stream()
                .filter(p -> p.fiscalYear() == 2026 && p.fiscalMonth() == 10)
                .findFirst()
                .orElseThrow();
        periods.close(scope, "it", period.id());

        AccountingException denied = assertThrows(AccountingException.class,
                () -> periods.reopen(scope, "it", period.id()));
        assertTrue(denied.getMessage().contains("accounting.period.reopen"), denied.getMessage());

        FiscalPeriodService allowed = periodService(code -> true);
        allowed.reopen(scope, "it", period.id());

        FiscalPeriod reopened = allowed.list(scope, "ID").stream()
                .filter(p -> p.id() == period.id())
                .findFirst()
                .orElseThrow();
        assertEquals("REOPENED", reopened.status().name());
        assertFalse(reopened.isOpen() == false, "a reopened period accepts postings again");
    }

    @Test
    void trialBalanceMatchesThePostedLines() {
        JournalService service = journalService();
        // a date of its own, so no other test's journals are in the range
        LocalDate ownDate = LocalDate.of(2026, 11, 20);
        JournalDraft ownDraft = new JournalDraft("ID", siteId, "GENERAL", ownDate, "IDR", BigDecimal.ONE,
                "REF-TRIAL", null, null, null, "trial balance",
                List.of(debit(cashAccount, "321000.00"), credit(salesAccount, "321000.00")));
        service.post(scope, "it", ownDraft);

        List<JournalRepository.TrialBalanceRow> rows = service.trialBalance(scope, ownDate, ownDate);
        JournalRepository.TrialBalanceRow cash = rows.stream()
                .filter(r -> r.accountCode().equals("1110"))
                .findFirst()
                .orElseThrow();
        assertEquals(0, cash.totalDebit().compareTo(new BigDecimal("321000.00")));
        assertEquals(0, cash.totalCredit().compareTo(BigDecimal.ZERO));

        BigDecimal totalDebit = rows.stream()
                .map(JournalRepository.TrialBalanceRow::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = rows.stream()
                .map(JournalRepository.TrialBalanceRow::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, totalDebit.compareTo(totalCredit),
                "the trial balance of that period must be balanced: " + totalDebit + " vs " + totalCredit);

        // over the whole book every journal and its reversal cancel out
        List<JournalRepository.TrialBalanceRow> all = service.trialBalance(scope, null, null);
        BigDecimal allDebit = all.stream()
                .map(JournalRepository.TrialBalanceRow::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal allCredit = all.stream()
                .map(JournalRepository.TrialBalanceRow::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, allDebit.compareTo(allCredit),
                "the complete trial balance must be balanced: " + allDebit + " vs " + allCredit);
    }

    /** Sum of all posted lines (debit - credit) over every journal. */
    private BigDecimal netBalance() {
        Object n = dsl.fetchValue(
                "SELECT COALESCE(SUM(base_debit - base_credit), 0) FROM journal_line");
        return new BigDecimal(String.valueOf(n));
    }
}
