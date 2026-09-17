package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import com.aesoftwaresolutions.solid.tax.TaxLine;
import com.aesoftwaresolutions.solid.tax.TaxLineCatalog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Chart of accounts. Public API of the ledger module for account lookups. */
@Service
public class AccountService {

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final TaxLineCatalog taxLines;
    private final CoaTemplates templates;

    AccountService(JdbcClient db, OrgScope orgScope, OrgService orgs, TaxLineCatalog taxLines, CoaTemplates templates) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.taxLines = taxLines;
        this.templates = templates;
    }

    public List<Account> list(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(SELECT + " where entity_id = ? order by code")
                .param(entityId).query(Account.class).list());
    }

    public Account get(UUID orgId, UUID entityId, UUID accountId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> find(entityId, accountId));
    }

    public Account create(UUID orgId, UUID entityId, String code, String name, AccountType type, String subtype,
                          UUID parentId, boolean isHeader, String taxLineCode) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> insert(orgId, entityId, code, name, type, subtype, parentId, isHeader,
                blankToNull(taxLineCode)));
    }

    public Account update(UUID orgId, UUID entityId, UUID accountId, String name, String taxLineCode, Boolean archived) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            Account account = find(entityId, accountId);
            if (name != null) {
                db.sql("update gl.account set name = ? where id = ?").params(name.trim(), accountId).update();
            }
            if (taxLineCode != null) {
                String code = blankToNull(taxLineCode);
                validateTaxLine(account.type(), account.isHeader(), code);
                db.sql("update gl.account set default_tax_line_code = ? where id = ?").params(code, accountId).update();
            }
            if (Boolean.TRUE.equals(archived) && !account.isArchived()) {
                Integer activeChildren = db.sql("select count(*) from gl.account where parent_id = ? and not is_archived")
                        .param(accountId).query(Integer.class).single();
                if (activeChildren > 0) {
                    throw new BusinessRuleException("HAS_ACTIVE_CHILDREN",
                            "Archive the " + activeChildren + " active sub-account(s) first");
                }
                db.sql("update gl.account set is_archived = true where id = ?").param(accountId).update();
            } else if (Boolean.FALSE.equals(archived)) {
                db.sql("update gl.account set is_archived = false where id = ?").param(accountId).update();
            }
            return find(entityId, accountId);
        });
    }

    public List<Account> applyTemplate(UUID orgId, UUID entityId, String templateName) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        CoaTemplates.Template template = templates.find(templateName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateName));
        if (!template.applicableEntityKinds().contains(entity.kind())) {
            throw new BusinessRuleException("TEMPLATE_NOT_APPLICABLE",
                    "Template " + templateName + " is for " + template.applicableEntityKinds() + ", not " + entity.kind());
        }
        return orgScope.call(orgId, () -> {
            // Lock the entity row so two concurrent template requests can't both run.
            db.sql("select id from org.entity where id = ? for update").param(entityId).query(UUID.class).single();
            Integer existing = db.sql("select count(*) from gl.account where entity_id = ?")
                    .param(entityId).query(Integer.class).single();
            if (existing > 0) {
                throw new BusinessRuleException("COA_NOT_EMPTY", "Entity already has " + existing + " account(s)");
            }
            Map<String, UUID> idsByCode = new HashMap<>();
            for (CoaTemplates.TemplateAccount t : template.accounts()) {
                UUID parent = t.parentCode() == null ? null : idsByCode.get(t.parentCode());
                if (t.parentCode() != null && parent == null) {
                    throw new IllegalStateException("Template parent " + t.parentCode() + " must come before " + t.code());
                }
                Account created = insert(orgId, entityId, t.code(), t.name(), t.type(), t.subtype(), parent, t.header(),
                        t.taxLineCode());
                idsByCode.put(t.code(), created.id());
            }
            return db.sql(SELECT + " where entity_id = ? order by code").param(entityId).query(Account.class).list();
        });
    }

    // Must be called inside orgScope.
    private Account insert(UUID orgId, UUID entityId, String code, String name, AccountType type, String subtype,
                           UUID parentId, boolean isHeader, String taxLineCode) {
        Boolean taken = db.sql("select exists (select 1 from gl.account where entity_id = ? and code = ?)")
                .params(entityId, code).query(Boolean.class).single();
        if (taken) {
            throw new BusinessRuleException("ACCOUNT_CODE_TAKEN", "Account code " + code + " is already used");
        }
        if (parentId != null) {
            Account parent = db.sql(SELECT + " where id = ?").param(parentId).query(Account.class).optional()
                    .orElseThrow(() -> new BusinessRuleException("INVALID_PARENT", "Parent account not found"));
            if (!parent.entityId().equals(entityId) || parent.type() != type || !parent.isHeader() || parent.isArchived()) {
                throw new BusinessRuleException("INVALID_PARENT",
                        "Parent must be an active header account of the same type in the same entity");
            }
        }
        validateTaxLine(type, isHeader, taxLineCode);

        UUID id = Ids.newId();
        db.sql("""
                insert into gl.account (id, org_id, entity_id, code, name, type, subtype, parent_id, is_header, default_tax_line_code)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, orgId, entityId, code, name.trim(), type.name(), subtype, parentId, isHeader, taxLineCode)
                .update();
        return find(entityId, id);
    }

    private void validateTaxLine(AccountType type, boolean isHeader, String taxLineCode) {
        if (taxLineCode == null) {
            return;
        }
        TaxLine line = taxLines.find(taxLineCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tax line code: " + taxLineCode));
        boolean compatible = !isHeader && switch (line.kind()) {
            case income -> type == AccountType.income;
            case expense, cogs -> type == AccountType.expense;
        };
        if (!compatible) {
            throw new BusinessRuleException("TAX_LINE_INCOMPATIBLE",
                    "Tax line " + taxLineCode + " (" + line.kind() + ") can't be used on a "
                            + (isHeader ? "header " : "") + type + " account");
        }
    }

    private Account find(UUID entityId, UUID accountId) {
        return db.sql(SELECT + " where entity_id = ? and id = ?").params(entityId, accountId)
                .query(Account.class).optional()
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static final String SELECT = """
            select id, org_id, entity_id, code, name, type, subtype, parent_id, is_header,
                   default_tax_line_code as tax_line_code, is_archived, created_at
            from gl.account""";
}
