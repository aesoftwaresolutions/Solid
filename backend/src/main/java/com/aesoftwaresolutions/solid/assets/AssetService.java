package com.aesoftwaresolutions.solid.assets;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.ledger.Account;
import com.aesoftwaresolutions.solid.ledger.AccountService;
import com.aesoftwaresolutions.solid.ledger.AccountType;
import com.aesoftwaresolutions.solid.ledger.JournalEntry;
import com.aesoftwaresolutions.solid.ledger.JournalService;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Fixed assets and straight-line book depreciation. */
@Service
public class AssetService {

    static final String TAX_NOTE = "These figures are book depreciation (straight-line). Tax depreciation "
            + "(MACRS, section 179 and bonus depreciation) is not calculated yet and will come from a reviewed tax "
            + "rule pack — give this schedule to your preparer rather than using it on a return.";

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final AccountService accounts;
    private final JournalService journal;

    AssetService(JdbcClient db, OrgScope orgScope, OrgService orgs, AccountService accounts, JournalService journal) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.accounts = accounts;
        this.journal = journal;
    }

    public AssetModels.Asset create(UUID orgId, UUID entityId, String name, String description, String category,
                                    LocalDate placedInServiceDate, Money cost, Money salvageValue, int usefulLifeMonths,
                                    UUID assetAccountId, UUID accumulatedAccountId, UUID depreciationExpenseAccountId) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        Money salvage = salvageValue == null ? Money.zero(ccy) : salvageValue;
        if (!cost.currency().equals(ccy) || !salvage.currency().equals(ccy)) {
            throw new BusinessRuleException("CURRENCY_MISMATCH", "Amounts must be in " + ccy);
        }
        if (!cost.isPositive()) {
            throw new IllegalArgumentException("Cost must be positive");
        }
        if (salvage.isNegative() || salvage.compareTo(cost) >= 0) {
            throw new IllegalArgumentException("Salvage value must be zero or more and less than the cost");
        }
        if (usefulLifeMonths < 1 || usefulLifeMonths > 600) {
            throw new IllegalArgumentException("Useful life must be between 1 and 600 months");
        }
        requireAccount(orgId, entityId, assetAccountId, AccountType.asset, "asset account");
        requireAccount(orgId, entityId, accumulatedAccountId, AccountType.asset, "accumulated depreciation account");
        requireAccount(orgId, entityId, depreciationExpenseAccountId, AccountType.expense, "depreciation expense account");

        return orgScope.call(orgId, () -> {
            UUID id = Ids.newId();
            db.sql("""
                    insert into fa.asset (id, org_id, entity_id, name, description, category, placed_in_service_date,
                                          cost_minor, salvage_minor, useful_life_months, currency, asset_account_id,
                                          accumulated_account_id, depreciation_expense_account_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, entityId, name.trim(), description, category, placedInServiceDate,
                            cost.minorUnits(), salvage.minorUnits(), usefulLifeMonths, ccy, assetAccountId,
                            accumulatedAccountId, depreciationExpenseAccountId)
                    .update();
            return load(entityId, id);
        });
    }

    public List<AssetModels.Asset> list(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql("select id from fa.asset where entity_id = ? order by placed_in_service_date, name")
                .param(entityId).query(UUID.class).list().stream().map(id -> load(entityId, id)).toList());
    }

    public AssetModels.Asset get(UUID orgId, UUID entityId, UUID assetId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> load(entityId, assetId));
    }

    /** Posts one journal entry per month for every month up to and including {@code throughMonth}. */
    public AssetModels.DepreciationRun run(UUID orgId, UUID entityId, YearMonth throughMonth) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        Optional<LocalDate> lock = journal.periodLock(orgId, entityId);

        return orgScope.call(orgId, () -> {
            List<AssetModels.Asset> assets = db.sql("select id from fa.asset where entity_id = ? and status = 'active'")
                    .param(entityId).query(UUID.class).list().stream().map(id -> load(entityId, id)).toList();

            // month -> (asset, amount) pairs still to post
            Map<LocalDate, List<Map.Entry<AssetModels.Asset, Money>>> due = new LinkedHashMap<>();
            for (AssetModels.Asset asset : assets) {
                for (AssetModels.ScheduleMonth month : asset.monthlySchedule()) {
                    if (month.posted() || YearMonth.from(month.month()).isAfter(throughMonth)) {
                        continue;
                    }
                    due.computeIfAbsent(month.month(), k -> new ArrayList<>())
                            .add(Map.entry(asset, month.amount()));
                }
            }

            List<AssetModels.PostedMonth> posted = new ArrayList<>();
            List<LocalDate> skipped = new ArrayList<>();
            Money total = Money.zero(ccy);
            for (LocalDate month : due.keySet().stream().sorted().toList()) {
                LocalDate entryDate = month.withDayOfMonth(month.lengthOfMonth());
                if (lock.isPresent() && !entryDate.isAfter(lock.get())) {
                    skipped.add(month);
                    continue;
                }
                List<JournalService.NewLine> lines = new ArrayList<>();
                Money monthTotal = Money.zero(ccy);
                for (Map.Entry<AssetModels.Asset, Money> item : due.get(month)) {
                    lines.add(new JournalService.NewLine(item.getKey().depreciationExpenseAccountId(), item.getValue(),
                            item.getKey().name()));
                    lines.add(new JournalService.NewLine(item.getKey().accumulatedAccountId(), item.getValue().negate(),
                            item.getKey().name()));
                    monthTotal = monthTotal.add(item.getValue());
                }
                JournalEntry entry = journal.postFromSource(orgId, entityId, entryDate,
                        "Depreciation " + YearMonth.from(month), lines, "depreciation", Ids.newId());
                for (Map.Entry<AssetModels.Asset, Money> item : due.get(month)) {
                    db.sql("""
                            insert into fa.depreciation_entry (id, org_id, asset_id, period_month, amount_minor, journal_entry_id)
                            values (?, ?, ?, ?, ?, ?)""")
                            .params(Ids.newId(), orgId, item.getKey().id(), month, item.getValue().minorUnits(), entry.id())
                            .update();
                }
                posted.add(new AssetModels.PostedMonth(month, monthTotal, entry.id()));
                total = total.add(monthTotal);
            }
            String reason = skipped.isEmpty() ? null
                    : "Skipped months that fall in the locked period (locked through " + lock.orElse(null) + ")";
            return new AssetModels.DepreciationRun(List.copyOf(posted), total, List.copyOf(skipped), reason);
        });
    }

    public AssetModels.Asset dispose(UUID orgId, UUID entityId, UUID assetId, LocalDate disposalDate, Money proceeds,
                                     UUID depositAccountId, UUID gainLossAccountId) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        Money received = proceeds == null ? Money.zero(ccy) : proceeds;
        if (received.isNegative()) {
            throw new IllegalArgumentException("Proceeds can't be negative");
        }
        if (received.isPositive() && depositAccountId == null) {
            throw new IllegalArgumentException("Tell Solid which account received the proceeds");
        }
        if (depositAccountId != null) {
            requireAccount(orgId, entityId, depositAccountId, AccountType.asset, "deposit account");
        }
        Account gainLoss = accounts.get(orgId, entityId, gainLossAccountId);
        if (gainLoss.isHeader() || gainLoss.isArchived() || !gainLoss.type().isIncomeStatement()) {
            throw new BusinessRuleException("ACCOUNT_NOT_POSTABLE",
                    "Gain/loss must be an active, non-header income or expense account");
        }

        return orgScope.call(orgId, () -> {
            AssetModels.Asset asset = lock(entityId, assetId);
            if (asset.status().equals("disposed")) {
                throw new BusinessRuleException("ASSET_DISPOSED", "This asset has already been disposed of");
            }
            Money accumulated = asset.accumulatedDepreciation();
            Money netBookValue = asset.cost().subtract(accumulated);

            List<JournalService.NewLine> lines = new ArrayList<>();
            if (accumulated.isPositive()) {
                lines.add(new JournalService.NewLine(asset.accumulatedAccountId(), accumulated, "Remove accumulated depreciation"));
            }
            if (received.isPositive()) {
                lines.add(new JournalService.NewLine(depositAccountId, received, "Disposal proceeds"));
            }
            lines.add(new JournalService.NewLine(asset.assetAccountId(), asset.cost().negate(), "Remove asset cost"));
            Money gain = received.subtract(netBookValue); // positive = gain, negative = loss
            if (!gain.isZero()) {
                lines.add(new JournalService.NewLine(gainLossAccountId, gain.negate(),
                        gain.isPositive() ? "Gain on disposal" : "Loss on disposal"));
            }
            JournalEntry entry = journal.postFromSource(orgId, entityId, disposalDate,
                    "Disposal of " + asset.name(), lines, "asset_disposal", assetId);
            db.sql("update fa.asset set status = 'disposed', disposal_date = ?, disposal_journal_entry_id = ? where id = ?")
                    .params(disposalDate, entry.id(), assetId).update();
            return load(entityId, assetId);
        });
    }

    public AssetModels.FixedAssetReport report(UUID orgId, UUID entityId, LocalDate asOf) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        return orgScope.call(orgId, () -> {
            List<AssetModels.ScheduleRow> rows = new ArrayList<>();
            Money totalCost = Money.zero(ccy);
            Money totalAccumulated = Money.zero(ccy);
            for (UUID id : db.sql("select id from fa.asset where entity_id = ? and placed_in_service_date <= ? order by placed_in_service_date, name")
                    .params(entityId, asOf).query(UUID.class).list()) {
                AssetModels.Asset asset = load(entityId, id);
                if (asset.disposalDate() != null && !asset.disposalDate().isAfter(asOf)) {
                    continue; // no longer on the books
                }
                Money accumulated = accumulatedThrough(id, asOf, ccy);
                rows.add(new AssetModels.ScheduleRow(asset.id(), asset.name(), asset.placedInServiceDate(), asset.cost(),
                        accumulated, asset.cost().subtract(accumulated), asset.status()));
                totalCost = totalCost.add(asset.cost());
                totalAccumulated = totalAccumulated.add(accumulated);
            }
            return new AssetModels.FixedAssetReport(asOf, ccy, List.copyOf(rows), totalCost, totalAccumulated,
                    totalCost.subtract(totalAccumulated), TAX_NOTE);
        });
    }

    // ---------------- internals (inside org scope) ----------------

    private void requireAccount(UUID orgId, UUID entityId, UUID accountId, AccountType type, String label) {
        Account account = accounts.get(orgId, entityId, accountId);
        if (account.type() != type || account.isHeader() || account.isArchived()) {
            throw new BusinessRuleException("ACCOUNT_NOT_POSTABLE",
                    "The " + label + " must be an active, non-header " + type + " account");
        }
    }

    private Money accumulatedThrough(UUID assetId, LocalDate asOf, String ccy) {
        Long minor = db.sql("""
                select coalesce(sum(amount_minor), 0) from fa.depreciation_entry
                where asset_id = ? and period_month <= ?""")
                .params(assetId, asOf.withDayOfMonth(1)).query(Long.class).single();
        return Money.ofMinor(minor, ccy);
    }

    private AssetModels.Asset lock(UUID entityId, UUID assetId) {
        db.sql("select id from fa.asset where entity_id = ? and id = ? for update").params(entityId, assetId)
                .query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("Asset " + assetId + " not found"));
        return load(entityId, assetId);
    }

    private AssetModels.Asset load(UUID entityId, UUID assetId) {
        Map<String, Object> row = db.sql("""
                select id, entity_id, name, description, category, placed_in_service_date, cost_minor, salvage_minor,
                       useful_life_months, currency, method, asset_account_id, accumulated_account_id,
                       depreciation_expense_account_id, status, disposal_date
                from fa.asset where entity_id = ? and id = ?""")
                .params(entityId, assetId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Asset " + assetId + " not found"));
        String ccy = ((String) row.get("currency")).trim();
        Money cost = Money.ofMinor(((Number) row.get("cost_minor")).longValue(), ccy);
        Money salvage = Money.ofMinor(((Number) row.get("salvage_minor")).longValue(), ccy);
        int life = ((Number) row.get("useful_life_months")).intValue();
        LocalDate placed = date(row.get("placed_in_service_date"));
        LocalDate disposal = date(row.get("disposal_date"));

        List<LocalDate> postedMonths = db.sql("select period_month from fa.depreciation_entry where asset_id = ?")
                .param(assetId).query(LocalDate.class).list();
        Money accumulated = Money.ofMinor(db.sql("select coalesce(sum(amount_minor), 0) from fa.depreciation_entry where asset_id = ?")
                .param(assetId).query(Long.class).single(), ccy);

        long[] ones = new long[life];
        java.util.Arrays.fill(ones, 1L);
        // Equal monthly amounts; the leftover cents land in the final months, the usual bookkeeping convention.
        List<Money> parts = new ArrayList<>(cost.subtract(salvage).allocate(ones));
        java.util.Collections.reverse(parts);
        List<AssetModels.ScheduleMonth> schedule = new ArrayList<>(life);
        YearMonth start = YearMonth.from(placed);
        for (int i = 0; i < life; i++) {
            LocalDate month = start.plusMonths(i).atDay(1);
            boolean stop = disposal != null && !month.isBefore(disposal.withDayOfMonth(1));
            if (stop) {
                break;
            }
            schedule.add(new AssetModels.ScheduleMonth(month, parts.get(i), postedMonths.contains(month)));
        }

        return new AssetModels.Asset((UUID) row.get("id"), (UUID) row.get("entity_id"), (String) row.get("name"),
                (String) row.get("description"), (String) row.get("category"), placed, cost, salvage, life,
                (String) row.get("method"), (UUID) row.get("asset_account_id"), (UUID) row.get("accumulated_account_id"),
                (UUID) row.get("depreciation_expense_account_id"), (String) row.get("status"), disposal, accumulated,
                cost.subtract(accumulated), List.copyOf(schedule));
    }

    private static LocalDate date(Object value) {
        return value == null ? null : value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value;
    }
}
