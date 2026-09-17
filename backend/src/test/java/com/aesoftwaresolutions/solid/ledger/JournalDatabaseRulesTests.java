package com.aesoftwaresolutions.solid.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Spec 005 AC 3, 4, 5, 7: rules hold even when someone bypasses the application and writes SQL directly
 * as the application role.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JournalDatabaseRulesTests {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OrgScope orgScope;
    @Autowired
    OrgService orgs;
    @Autowired
    AccountService accounts;
    @Autowired
    JournalService journal;
    @Autowired
    PostgreSQLContainer<?> postgres;

    UUID org;
    UUID entity;
    Map<String, UUID> acct;

    @BeforeEach
    void setUp() {
        org = orgs.createOrganization("DB rules", "business").id();
        entity = orgs.createEntity(org, "sole_prop", "Rules Co", null, null, null, null).id();
        acct = accounts.applyTemplate(org, entity, "schedule_c").stream()
                .collect(Collectors.toMap(Account::code, Account::id));
    }

    private UUID postSoftware(String date) {
        return journal.create(org, entity, LocalDate.parse(date), "Software", true, List.of(
                new JournalService.NewLine(acct.get("6220"), Money.of("54.99", "USD"), null),
                new JournalService.NewLine(acct.get("1010"), Money.of("-54.99", "USD"), null)), null).entry().id();
    }

    @Test
    void ac3_postedRowsCannotBeChangedWithDirectSql() {
        UUID id = postSoftware("2026-09-10");

        assertThatThrownBy(() -> orgScope.run(org, () ->
                jdbc.update("update gl.journal_line set amount_minor = 1 where journal_entry_id = ?", id)))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("cannot be inserted, changed or deleted");
        assertThatThrownBy(() -> orgScope.run(org, () ->
                jdbc.update("update gl.journal_entry set memo = 'edited' where id = ?", id)))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("cannot be changed");
        assertThatThrownBy(() -> orgScope.run(org, () ->
                jdbc.update("delete from gl.journal_entry where id = ?", id)))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("cannot be deleted");
        assertThatThrownBy(() -> orgScope.run(org, () -> jdbc.update("""
                insert into gl.journal_line (id, org_id, journal_entry_id, line_no, account_id, amount_minor, currency)
                values (?, ?, ?, 3, ?, 100, 'USD')""", Ids.newId(), org, id, acct.get("6220"))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ac4_databaseRejectsUnbalancedPostedEntryAtCommit() {
        UUID id = Ids.newId();
        assertThatThrownBy(() -> orgScope.run(org, () -> {
            jdbc.update("""
                    insert into gl.journal_entry (id, org_id, entity_id, entry_date, status)
                    values (?, ?, ?, '2026-09-10', 'draft')""", id, org, entity);
            jdbc.update("""
                    insert into gl.journal_line (id, org_id, journal_entry_id, line_no, account_id, amount_minor, currency)
                    values (?, ?, ?, 1, ?, 5000, 'USD'), (?, ?, ?, 2, ?, -4999, 'USD')""",
                    Ids.newId(), org, id, acct.get("6220"), Ids.newId(), org, id, acct.get("1010"));
            jdbc.update("""
                    update gl.journal_entry set status = 'posted', posting_seq = 999, hash = 'x', posted_at = now()
                    where id = ?""", id);
        })).as("deferred trigger fires at COMMIT").isInstanceOf(TransactionSystemException.class)
                .hasStackTraceContaining("does not balance");

        Integer count = orgScope.call(org, () ->
                jdbc.queryForObject("select count(*) from gl.journal_entry where id = ?", Integer.class, id));
        assertThat(count).as("whole transaction rolled back").isZero();
    }

    @Test
    void ac5_databaseEnforcesPeriodLock() {
        journal.setPeriodLock(org, entity, LocalDate.parse("2026-06-30"));
        assertThatThrownBy(() -> orgScope.run(org, () -> jdbc.update("""
                insert into gl.journal_entry (id, org_id, entity_id, entry_date, status)
                values (?, ?, ?, '2026-06-15', 'draft')""", Ids.newId(), org, entity)))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("Period is locked");
    }

    @Test
    void ac7_verificationDetectsTamperingByDatabaseOwner() throws Exception {
        postSoftware("2026-09-01");
        UUID second = postSoftware("2026-09-02");
        postSoftware("2026-09-03");
        assertThat(journal.verifyChain(org, entity).valid()).isTrue();

        // Simulate an attacker with owner access who disables the triggers and edits history.
        try (Connection owner = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = owner.createStatement()) {
            st.execute("alter table gl.journal_line disable trigger journal_line_immutable");
            st.execute("update gl.journal_line set amount_minor = amount_minor * 10 where journal_entry_id = '" + second + "'");
            st.execute("alter table gl.journal_line enable trigger journal_line_immutable");
        }

        JournalService.ChainVerification result = journal.verifyChain(org, entity);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstInvalidSeq()).isEqualTo(2L);
    }
}
