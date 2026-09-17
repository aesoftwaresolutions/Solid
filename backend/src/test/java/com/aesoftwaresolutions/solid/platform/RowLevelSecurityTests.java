package com.aesoftwaresolutions.solid.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.common.Ids;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec 003, AC 3, 4, 8: tenant isolation is enforced by PostgreSQL itself. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RowLevelSecurityTests {

    /** Tables allowed to exist without org_id / RLS. Adding to this list needs a written reason. */
    private static final Set<String> GLOBAL_TABLES = Set.of(
            "org.organization",      // visibility controlled by iam.membership (OrgAccessInterceptor)
            "iam.user_account",      // identity spans organizations
            "iam.session",           // identity spans organizations
            "iam.mfa_recovery_code", // identity spans organizations
            "iam.membership",        // needed to decide org access before an org scope exists
            "audit.event"            // instance-wide chain; app role has INSERT/SELECT only, filtered by org in queries
    );

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OrgScope orgScope;

    private UUID newOrg() {
        UUID id = Ids.newId();
        jdbc.update("insert into org.organization (id, name, kind) values (?, 'Test', 'household')", id);
        return id;
    }

    @Test
    void ac4_appRunsAsRestrictedRole() {
        Map<String, Object> role = jdbc.queryForMap(
                "select current_user as name, rolsuper, rolbypassrls from pg_roles where rolname = current_user");
        assertThat(role.get("name")).isEqualTo("solid_app");
        assertThat(role.get("rolsuper")).isEqualTo(false);
        assertThat(role.get("rolbypassrls")).isEqualTo(false);
    }

    @Test
    void ac3_rowsOfOtherOrgsAreInvisibleAndUnwritable() {
        UUID orgA = newOrg();
        UUID orgB = newOrg();
        UUID entityA = Ids.newId();

        orgScope.run(orgA, () -> jdbc.update(
                "insert into org.entity (id, org_id, kind, legal_name) values (?, ?, 'individual', 'A person')",
                entityA, orgA));

        int visibleInA = orgScope.call(orgA, () ->
                jdbc.queryForObject("select count(*) from org.entity where id = ?", Integer.class, entityA));
        int visibleInB = orgScope.call(orgB, () ->
                jdbc.queryForObject("select count(*) from org.entity where id = ?", Integer.class, entityA));
        assertThat(visibleInA).isEqualTo(1);
        assertThat(visibleInB).isZero();

        // Writing a row for org A while scoped to org B violates the policy.
        assertThatThrownBy(() -> orgScope.run(orgB, () -> jdbc.update(
                "insert into org.entity (id, org_id, kind, legal_name) values (?, ?, 'individual', 'Sneaky')",
                Ids.newId(), orgA)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("violates row-level security policy");

        // Updating/deleting another org's row silently affects nothing.
        int updated = orgScope.call(orgB, () ->
                jdbc.update("update org.entity set legal_name = 'hacked' where id = ?", entityA));
        assertThat(updated).isZero();
    }

    @Test
    void ac3_noOrgSetMeansNoRows() {
        UUID org = newOrg();
        orgScope.run(org, () -> jdbc.update(
                "insert into org.entity (id, org_id, kind, legal_name) values (?, ?, 'individual', 'X')",
                Ids.newId(), org));

        Integer count = jdbc.queryForObject("select count(*) from org.entity", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void ac8_everyModuleTableHasForcedRowLevelSecurity() {
        List<Map<String, Object>> tables = jdbc.queryForList("""
                select n.nspname || '.' || c.relname as table_name,
                       c.relrowsecurity as rls,
                       c.relforcerowsecurity as forced,
                       exists (select 1 from information_schema.columns col
                               where col.table_schema = n.nspname and col.table_name = c.relname
                                 and col.column_name = 'org_id') as has_org_id,
                       exists (select 1 from pg_policies p
                               where p.schemaname = n.nspname and p.tablename = c.relname) as has_policy
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where c.relkind in ('r', 'p')
                  and n.nspname in ('iam','org','gl','bank','ar_ap','fa','pf','tax','stx','efile','doc','ai','audit','sys')
                """);

        assertThat(tables).isNotEmpty();
        for (Map<String, Object> t : tables) {
            String name = (String) t.get("table_name");
            if (GLOBAL_TABLES.contains(name)) {
                continue;
            }
            assertThat(t.get("has_org_id")).as(name + " must have org_id").isEqualTo(true);
            assertThat(t.get("rls")).as(name + " must enable RLS").isEqualTo(true);
            assertThat(t.get("forced")).as(name + " must FORCE RLS").isEqualTo(true);
            assertThat(t.get("has_policy")).as(name + " must have a policy").isEqualTo(true);
        }
    }
}
