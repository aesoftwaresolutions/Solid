package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Business lines: the facets of a business (web design, automation, a product) that the books report on
 * separately (spec 069). A management dimension only — it never changes an account, a tax line or a total.
 */
@Service
public class BusinessLineService {

    public record BusinessLine(UUID id, UUID entityId, String name, boolean isArchived, OffsetDateTime createdAt) {
    }

    static final int MAX_LINES = 50;

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final AuditLog audit;

    BusinessLineService(JdbcClient db, OrgScope orgScope, OrgService orgs, AuditLog audit) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.audit = audit;
    }

    public List<BusinessLine> list(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(SELECT + " where entity_id = ? order by is_archived, lower(name)")
                .param(entityId).query(BusinessLine.class).list());
    }

    public BusinessLine get(UUID orgId, UUID entityId, UUID id) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> load(entityId, id));
    }

    public BusinessLine create(UUID orgId, UUID entityId, String name) {
        orgs.getEntity(orgId, entityId);
        String clean = clean(name);
        BusinessLine created = orgScope.call(orgId, () -> {
            // Serialize per entity so the count limit and the name check cannot race.
            db.sql("select id from org.entity where id = ? for update").param(entityId).query(UUID.class).single();
            int count = db.sql("select count(*) from gl.business_line where entity_id = ?")
                    .param(entityId).query(Integer.class).single();
            if (count >= MAX_LINES) {
                throw new BusinessRuleException("TOO_MANY_BUSINESS_LINES",
                        "An entity can have at most " + MAX_LINES + " business lines");
            }
            requireNameFree(entityId, clean, null);
            UUID id = Ids.newId();
            db.sql("insert into gl.business_line (id, org_id, entity_id, name) values (?, ?, ?, ?)")
                    .params(id, orgId, entityId, clean).update();
            return load(entityId, id);
        });
        audit.record(AuditLog.Actor.current(), orgId, "business_line_created", "business_line", created.id(),
                Map.of("entityId", entityId.toString(), "name", clean));
        return created;
    }

    /** Renames and/or archives. A null argument leaves that field as it is. */
    public BusinessLine update(UUID orgId, UUID entityId, UUID id, String name, Boolean archived) {
        orgs.getEntity(orgId, entityId);
        BusinessLine before = get(orgId, entityId, id);
        BusinessLine after = orgScope.call(orgId, () -> {
            db.sql("select id from org.entity where id = ? for update").param(entityId).query(UUID.class).single();
            if (name != null) {
                String clean = clean(name);
                requireNameFree(entityId, clean, id);
                db.sql("update gl.business_line set name = ? where id = ?").params(clean, id).update();
            }
            if (archived != null) {
                db.sql("update gl.business_line set is_archived = ? where id = ?").params(archived, id).update();
            }
            return load(entityId, id);
        });
        Map<String, Object> details = new HashMap<>();
        details.put("entityId", entityId.toString());
        details.put("from", before.name() + (before.isArchived() ? " (archived)" : ""));
        details.put("to", after.name() + (after.isArchived() ? " (archived)" : ""));
        audit.record(AuditLog.Actor.current(), orgId, "business_line_changed", "business_line", id, details);
        return after;
    }

    /**
     * Checks that a business line may be put on new work for this entity: it exists, belongs to the entity and is
     * not archived. Null is always allowed — it means shared or unassigned. Call inside an org scope.
     */
    public void requireAssignable(UUID entityId, UUID businessLineId) {
        if (businessLineId == null) {
            return;
        }
        BusinessLine line = db.sql(SELECT + " where id = ?").param(businessLineId).query(BusinessLine.class)
                .optional().orElse(null);
        if (line == null || !line.entityId().equals(entityId)) {
            throw new BusinessRuleException("BUSINESS_LINE_NOT_FOUND",
                    "Business line " + businessLineId + " is not a business line of this entity");
        }
        if (line.isArchived()) {
            throw new BusinessRuleException("BUSINESS_LINE_ARCHIVED",
                    "Business line \"" + line.name() + "\" is archived; restore it before using it");
        }
    }

    // ---------- internals (call inside orgScope) ----------

    private BusinessLine load(UUID entityId, UUID id) {
        return db.sql(SELECT + " where entity_id = ? and id = ?").params(entityId, id).query(BusinessLine.class)
                .optional().orElseThrow(() -> new NotFoundException("Business line " + id + " not found"));
    }

    private void requireNameFree(UUID entityId, String name, UUID except) {
        boolean taken = db.sql("""
                select exists (select 1 from gl.business_line
                               where entity_id = ? and lower(trim(name)) = lower(?) and id is distinct from ?)""")
                .params(entityId, name, except).query(Boolean.class).single();
        if (taken) {
            throw new BusinessRuleException("BUSINESS_LINE_NAME_TAKEN",
                    "There is already a business line called \"" + name + "\"");
        }
    }

    private static String clean(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 60) {
            throw new IllegalArgumentException("A business line name is 1 to 60 characters");
        }
        return clean;
    }

    private static final String SELECT = """
            select id, entity_id, name, is_archived, created_at from gl.business_line""";
}
