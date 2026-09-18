package com.aesoftwaresolutions.solid.assets;

import com.aesoftwaresolutions.solid.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class AssetController {

    record CreateAsset(@NotBlank @Size(max = 200) String name, @Size(max = 500) String description,
                       @Size(max = 60) String category, @NotNull LocalDate placedInServiceDate, @NotNull Money cost,
                       Money salvageValue, @NotNull @Min(1) @Max(600) Integer usefulLifeMonths,
                       @NotNull UUID assetAccountId, @NotNull UUID accumulatedAccountId,
                       @NotNull UUID depreciationExpenseAccountId) {
    }

    record RunRequest(@NotNull @Pattern(regexp = "\\d{4}-\\d{2}") String throughMonth) {
    }

    record DisposeRequest(@NotNull LocalDate disposalDate, Money proceeds, UUID depositAccountId,
                          @NotNull UUID gainLossAccountId) {
    }

    private final AssetService assets;

    AssetController(AssetService assets) {
        this.assets = assets;
    }

    @GetMapping("/assets")
    List<AssetModels.Asset> list(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return assets.list(orgId, entityId);
    }

    @GetMapping("/assets/{assetId}")
    AssetModels.Asset get(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID assetId) {
        return assets.get(orgId, entityId, assetId);
    }

    @PostMapping("/assets")
    @ResponseStatus(HttpStatus.CREATED)
    AssetModels.Asset create(@PathVariable UUID orgId, @PathVariable UUID entityId,
                             @Valid @RequestBody CreateAsset body) {
        return assets.create(orgId, entityId, body.name(), body.description(), body.category(),
                body.placedInServiceDate(), body.cost(), body.salvageValue(), body.usefulLifeMonths(),
                body.assetAccountId(), body.accumulatedAccountId(), body.depreciationExpenseAccountId());
    }

    @PostMapping("/depreciation-runs")
    @ResponseStatus(HttpStatus.CREATED)
    AssetModels.DepreciationRun run(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                    @Valid @RequestBody RunRequest body) {
        return assets.run(orgId, entityId, YearMonth.parse(body.throughMonth()));
    }

    @PostMapping("/assets/{assetId}/dispose")
    AssetModels.Asset dispose(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID assetId,
                              @Valid @RequestBody DisposeRequest body) {
        return assets.dispose(orgId, entityId, assetId, body.disposalDate(), body.proceeds(), body.depositAccountId(),
                body.gainLossAccountId());
    }

    @GetMapping("/reports/fixed-assets")
    AssetModels.FixedAssetReport report(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                        @RequestParam LocalDate asOf) {
        return assets.report(orgId, entityId, asOf);
    }
}
