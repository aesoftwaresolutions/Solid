package com.aesoftwaresolutions.solid.tax;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tax figures added by whoever runs this installation, each with the source it came from.
 *
 * <p>Solid ships the figures it knows in files inside the jar, which means a newly announced rate would
 * otherwise need a rebuild. A figure added here is used in preference to the built-in file for the same year;
 * nothing is ever interpolated, and a year nobody has supplied stays <em>unknown</em>.
 *
 * <p>Rows are never updated or deleted. A correction supersedes its predecessor, so what the installation
 * believed at any point in the past can still be read.
 */
@Service
public class TaxFigures {

    /** The figures this version of Solid actually uses. An unknown key is refused rather than stored. */
    public enum Key {
        mileage_rate_per_mile("US dollars per mile", 3),
        home_office_rate_per_square_foot("US dollars per square foot", 2),
        form_1099_nec_threshold("US dollars", 2);

        private final String unit;
        private final int scale;

        Key(String unit, int scale) {
            this.unit = unit;
            this.scale = scale;
        }

        public String unit() {
            return unit;
        }

        public int scale() {
            return scale;
        }
    }

    /** @param inUse true when this is the figure Solid would use today for that key and year */
    public record Figure(UUID id, Key key, int taxYear, BigDecimal value, String unit, String source, String note,
                         OffsetDateTime addedAt, UUID addedBy, OffsetDateTime supersededAt, boolean inUse) {
    }

    private static final int EARLIEST_YEAR = 1913;
    private static final int LATEST_YEAR = 2100;
    private static final int MIN_SOURCE_LENGTH = 30;

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final AuditLog audit;

    TaxFigures(JdbcClient db, TransactionTemplate tx, AuditLog audit) {
        this.db = db;
        this.tx = tx;
        this.audit = audit;
    }

    /** The figure in force for that key and year, if someone has supplied one. */
    public Optional<Figure> inForce(Key key, int taxYear) {
        return db.sql(SELECT + " where figure_key = ? and tax_year = ? and superseded_at is null")
                .params(key.name(), taxYear).query(this::map).optional();
    }

    /** Everything ever supplied, newest first, including superseded rows. */
    public List<Figure> all() {
        return db.sql(SELECT + " order by added_at desc").query(this::map).list();
    }

    public Figure add(String keyName, int taxYear, String value, String source, String note, boolean supersede,
                      UUID actorId, String ip) {
        Key key = parseKey(keyName);
        if (taxYear < EARLIEST_YEAR || taxYear > LATEST_YEAR) {
            throw new ApiProblemException(400, "BAD_TAX_YEAR",
                    "The tax year must be between " + EARLIEST_YEAR + " and " + LATEST_YEAR);
        }
        if (source == null || source.trim().length() < MIN_SOURCE_LENGTH) {
            throw new ApiProblemException(400, "SOURCE_REQUIRED",
                    "Say where this figure comes from — the IRS notice, publication or page — in at least "
                            + MIN_SOURCE_LENGTH + " characters. A figure with no provenance is worse than none.");
        }
        BigDecimal amount = parseValue(value, key);

        return tx.execute(status -> {
            Optional<Figure> existing = inForce(key, taxYear);
            if (existing.isPresent()) {
                if (!supersede) {
                    throw new ApiProblemException(409, "FIGURE_EXISTS",
                            "There is already a figure for " + key + " in " + taxYear + " (" + existing.get().value()
                                    + "). Send supersede=true, with the source for the new one, to replace it.");
                }
                db.sql("update tax.figure set superseded_at = now(), superseded_by = ? where id = ?")
                        .params(actorId, existing.get().id()).update();
                audit.record(new AuditLog.Actor(actorId, ip), null, "tax_figure_superseded", "tax_figure",
                        existing.get().id(),
                        Map.of("key", key.name(), "taxYear", taxYear, "was", existing.get().value().toPlainString()));
            }
            UUID id = Ids.newId();
            db.sql("""
                    insert into tax.figure (id, figure_key, tax_year, value_decimal, source, note, added_by)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, key.name(), taxYear, amount, source.trim(),
                            note == null || note.isBlank() ? null : note.trim(), actorId)
                    .update();
            audit.record(new AuditLog.Actor(actorId, ip), null, "tax_figure_added", "tax_figure", id,
                    Map.of("key", key.name(), "taxYear", taxYear, "value", amount.toPlainString(),
                            "source", source.trim()));
            return db.sql(SELECT + " where id = ?").param(id).query(this::map).single();
        });
    }

    private static Key parseKey(String keyName) {
        try {
            return Key.valueOf(keyName == null ? "" : keyName.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiProblemException(400, "UNKNOWN_FIGURE",
                    "This version of Solid knows no figure called '" + keyName + "'. It understands: "
                            + String.join(", ", java.util.Arrays.stream(Key.values()).map(Enum::name).toList()));
        }
    }

    private static BigDecimal parseValue(String value, Key key) {
        BigDecimal amount;
        try {
            // Parsed from the text exactly: a rate of 0.70 must not become 0.7000000001.
            amount = new BigDecimal(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw new ApiProblemException(400, "BAD_VALUE", "'" + value + "' is not a number");
        }
        if (amount.signum() < 0) {
            throw new ApiProblemException(400, "BAD_VALUE", "A tax figure cannot be negative");
        }
        if (amount.scale() > key.scale()) {
            throw new ApiProblemException(400, "BAD_VALUE",
                    key + " is given to " + key.scale() + " decimal place(s); '" + value + "' has more");
        }
        return amount;
    }

    private static final String SELECT = """
            select id, figure_key, tax_year, value_decimal, source, note, added_by, added_at,
                   superseded_at, superseded_by
            from tax.figure""";

    private Figure map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Key key = Key.valueOf(rs.getString("figure_key"));
        OffsetDateTime superseded = rs.getObject("superseded_at", OffsetDateTime.class);
        return new Figure(rs.getObject("id", UUID.class), key, rs.getInt("tax_year"),
                rs.getBigDecimal("value_decimal").stripTrailingZeros().scale() < 0
                        ? rs.getBigDecimal("value_decimal").setScale(0, java.math.RoundingMode.UNNECESSARY)
                        : rs.getBigDecimal("value_decimal").stripTrailingZeros(),
                key.unit(), rs.getString("source"), rs.getString("note"),
                rs.getObject("added_at", OffsetDateTime.class), rs.getObject("added_by", UUID.class),
                superseded, superseded == null);
    }
}
