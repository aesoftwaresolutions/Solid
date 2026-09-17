package com.aesoftwaresolutions.solid.org;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business logic for organizations, entities and ownership. Public API of the org module. */
@Service
public class OrgService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JdbcClient db;
    private final OrgScope orgScope;

    OrgService(JdbcClient db, OrgScope orgScope) {
        this.db = db;
        this.orgScope = orgScope;
    }

    @Transactional
    public Organization createOrganization(String name, String kind) {
        UUID id = Ids.newId();
        db.sql("insert into org.organization (id, name, kind) values (?, ?, ?)")
                .params(id, name.trim(), kind)
                .update();
        return getOrganization(id);
    }

    @Transactional(readOnly = true)
    public Organization getOrganization(UUID orgId) {
        return db.sql("select id, name, kind, created_at from org.organization where id = ?")
                .param(orgId)
                .query(Organization.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("Organization " + orgId + " not found"));
    }

    public LegalEntity createEntity(UUID orgId, String kind, String legalName, Integer fiscalYearEnd,
                                    String accountingMethod, String homeState, String baseCurrency) {
        getOrganization(orgId);
        return orgScope.call(orgId, () -> {
            UUID id = Ids.newId();
            db.sql("""
                    insert into org.entity (id, org_id, kind, legal_name, fiscal_year_end, accounting_method, home_state, base_currency)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, kind, legalName.trim(),
                            Objects.requireNonNullElse(fiscalYearEnd, 12),
                            Objects.requireNonNullElse(accountingMethod, "cash"),
                            homeState,
                            Objects.requireNonNullElse(baseCurrency, "USD"))
                    .update();
            return findEntity(id);
        });
    }

    public List<LegalEntity> listEntities(UUID orgId) {
        getOrganization(orgId);
        return orgScope.call(orgId, () -> db.sql(ENTITY_SELECT + " order by created_at, id")
                .query(LegalEntity.class)
                .list());
    }

    public LegalEntity getEntity(UUID orgId, UUID entityId) {
        getOrganization(orgId);
        return orgScope.call(orgId, () -> findEntity(entityId));
    }

    public Ownership createOwnership(UUID orgId, UUID ownerEntityId, UUID ownedEntityId, BigDecimal percent,
                                     LocalDate effectiveFrom, LocalDate effectiveTo) {
        if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0 || percent.scale() > 4) {
            throw new IllegalArgumentException("percent must be greater than 0 and at most 100, with up to 4 decimals");
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
        if (ownerEntityId.equals(ownedEntityId)) {
            throw new IllegalArgumentException("An entity cannot own itself");
        }
        getOrganization(orgId);
        return orgScope.call(orgId, () -> {
            findEntity(ownerEntityId);
            // Lock the owned entity so two concurrent requests can't both push ownership over 100%.
            db.sql("select id from org.entity where id = ? for update").param(ownedEntityId).query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new NotFoundException("Entity " + ownedEntityId + " not found"));

            BigDecimal existing = db.sql("""
                    select coalesce(sum(percent), 0) from org.ownership
                    where owned_entity_id = :owned
                      and effective_from < coalesce(cast(:to as date), 'infinity'::date)
                      and coalesce(effective_to, 'infinity'::date) > :from""")
                    .param("owned", ownedEntityId)
                    .param("to", effectiveTo)
                    .param("from", effectiveFrom)
                    .query(BigDecimal.class)
                    .single();
            if (existing.add(percent).compareTo(HUNDRED) > 0) {
                throw new BusinessRuleException("OWNERSHIP_OVER_100",
                        "Total ownership would be " + existing.add(percent).stripTrailingZeros().toPlainString()
                                + "% for an overlapping period (maximum 100%)");
            }

            UUID id = Ids.newId();
            db.sql("""
                    insert into org.ownership (id, org_id, owner_entity_id, owned_entity_id, percent, effective_from, effective_to)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, ownerEntityId, ownedEntityId, percent, effectiveFrom, effectiveTo)
                    .update();
            return db.sql(OWNERSHIP_SELECT + " where id = ?").param(id).query(Ownership.class).single();
        });
    }

    public List<Ownership> listOwnerships(UUID orgId) {
        getOrganization(orgId);
        return orgScope.call(orgId, () -> db.sql(OWNERSHIP_SELECT + " order by effective_from, id")
                .query(Ownership.class)
                .list());
    }

    private LegalEntity findEntity(UUID entityId) {
        return db.sql(ENTITY_SELECT + " where id = ?")
                .param(entityId)
                .query(LegalEntity.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("Entity " + entityId + " not found"));
    }

    private static final String ENTITY_SELECT = """
            select id, org_id, kind, legal_name, fiscal_year_end, accounting_method, home_state, base_currency, created_at
            from org.entity""";

    private static final String OWNERSHIP_SELECT = """
            select id, org_id, owner_entity_id, owned_entity_id, percent, effective_from, effective_to
            from org.ownership""";
}
