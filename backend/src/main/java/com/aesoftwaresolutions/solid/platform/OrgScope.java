package com.aesoftwaresolutions.solid.platform;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs database work "inside" one organization.
 *
 * <p>It opens a transaction and sets the PostgreSQL setting {@code app.org_id} for that transaction only.
 * Row-level security policies compare each row's {@code org_id} to this setting, so queries can never
 * see or write another organization's rows — even if application code forgets a WHERE clause.
 */
@Component
public class OrgScope {

    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    OrgScope(TransactionTemplate tx, JdbcTemplate jdbc) {
        this.tx = tx;
        this.jdbc = jdbc;
    }

    public <T> T call(UUID orgId, Supplier<T> work) {
        Objects.requireNonNull(orgId, "orgId");
        return tx.execute(status -> {
            jdbc.queryForObject("select set_config('app.org_id', ?, true)", String.class, orgId.toString());
            return work.get();
        });
    }

    public void run(UUID orgId, Runnable work) {
        call(orgId, () -> {
            work.run();
            return null;
        });
    }
}
