package com.aesoftwaresolutions.solid.assets;

import com.aesoftwaresolutions.solid.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AssetModels {

    private AssetModels() {
    }

    public record ScheduleMonth(LocalDate month, Money amount, boolean posted) {
    }

    public record Asset(UUID id, UUID entityId, String name, String description, String category,
                        LocalDate placedInServiceDate, Money cost, Money salvageValue, int usefulLifeMonths,
                        String method, UUID assetAccountId, UUID accumulatedAccountId,
                        UUID depreciationExpenseAccountId, String status, LocalDate disposalDate,
                        Money accumulatedDepreciation, Money netBookValue, List<ScheduleMonth> monthlySchedule) {
    }

    public record PostedMonth(LocalDate month, Money amount, UUID journalEntryId) {
    }

    public record DepreciationRun(List<PostedMonth> months, Money totalPosted, List<LocalDate> skippedMonths,
                                  String skippedReason) {
    }

    public record ScheduleRow(UUID assetId, String name, LocalDate placedInServiceDate, Money cost,
                              Money accumulatedDepreciation, Money netBookValue, String status) {
    }

    public record FixedAssetReport(LocalDate asOf, String currency, List<ScheduleRow> assets, Money totalCost,
                                   Money totalAccumulated, Money totalNetBookValue, String taxNote) {
    }
}
