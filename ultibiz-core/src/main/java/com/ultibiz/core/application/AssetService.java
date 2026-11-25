package com.ultibiz.core.application;

import com.ultibiz.core.domain.Asset;
import com.ultibiz.core.domain.AssetCategory;
import com.ultibiz.core.domain.AssetDepreciation;
import com.ultibiz.core.domain.AssetDisposal;
import com.ultibiz.core.domain.AssetMaintenance;
import com.ultibiz.core.domain.AssetMeterReading;
import com.ultibiz.core.domain.MaintenancePlan;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.JournalEntry;
import com.ultibiz.core.infrastructure.AssetRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Asset management: the register, the monthly depreciation run (straight
 * line, declining balance, units of production), disposals, transfers between
 * sites and maintenance.
 *
 * <p>The depreciation run posts one journal for the whole period: the expense
 * side per category and the accumulated depreciation side per category.
 */
@Service
public class AssetService {

    public static final String STRAIGHT_LINE = "STRAIGHT_LINE";
    public static final String DECLINING_BALANCE = "DECLINING_BALANCE";
    public static final String UNITS_OF_PRODUCTION = "UNITS_OF_PRODUCTION";

    private final AssetRepository assets;
    private final AccountService accounts;
    private final JournalService journals;
    private final AuditService audit;

    public AssetService(AssetRepository assets, AccountService accounts, JournalService journals,
                        AuditService audit) {
        this.assets = assets;
        this.accounts = accounts;
        this.journals = journals;
        this.audit = audit;
    }

    public PageResult<Asset> search(SiteScope scope, String status, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        if (scope.isDenyAll()) {
            return new PageResult<>(List.of(), safePage, safeSize, 0);
        }
        long total = assets.count(scope.organizationId(), status);
        List<Asset> items = assets.list(scope.organizationId(), status, safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    public Asset byId(SiteScope scope, long id) {
        if (scope.isDenyAll()) {
            return null;
        }
        return assets.findById(scope.organizationId(), id);
    }

    public List<AssetCategory> categories(SiteScope scope) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return assets.categories(scope.organizationId());
    }

    public Asset register(SiteScope scope, String actor, long categoryId, String code, String name,
                          LocalDate acquisitionDate, BigDecimal cost, BigDecimal salvageValue,
                          Long locationId, String serialNumber, Long supplierPartyId, LocalDate warrantyEnd) {
        if (assets.findByCode(scope.organizationId(), code) != null) {
            throw new OrderException("Asset code already exists: " + code);
        }
        AssetCategory category = assets.findCategory(scope.organizationId(), categoryId);
        if (category == null) {
            throw new OrderException("Unknown asset category: " + categoryId);
        }
        BigDecimal salvage = salvageValue != null ? salvageValue
                : cost.multiply(category.salvagePercent()).setScale(2, RoundingMode.HALF_UP);
        long siteId = scope.siteIds().stream().findFirst().orElse(0L);
        long id = assets.insert(scope.organizationId(), siteId, categoryId, code, name, acquisitionDate, cost,
                salvage, locationId, serialNumber, supplierPartyId, null, warrantyEnd, null);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "ASSET_REGISTER", "asset",
                String.valueOf(id), "{\"code\":\"" + code + "\"}", null));
        return assets.findById(scope.organizationId(), id);
    }

    /**
     * Runs the depreciation for one month and posts the journal.
     *
     * @return the depreciation rows written (one per asset)
     */
    public List<AssetDepreciation> depreciate(SiteScope scope, String actor, int year, int month) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        YearMonth period = YearMonth.of(year, month);
        LocalDate from = period.atDay(1);
        LocalDate to = period.atEndOfMonth();

        List<AssetDepreciation> written = new ArrayList<>();
        List<JournalLineDraft> journalLines = new ArrayList<>();
        for (Asset asset : assets.list(scope.organizationId(), null, 1000, 0)) {
            if ("DISPOSED".equals(asset.status()) || asset.acquisitionDate().isAfter(to)) {
                continue;
            }
            if (assets.depreciationExists(asset.id(), year, month)) {
                continue;
            }
            AssetCategory category = assets.findCategory(scope.organizationId(), asset.categoryId());
            if (category == null) {
                continue;
            }
            BigDecimal amount = depreciationAmount(category, asset, from, to);
            if (amount.signum() <= 0) {
                continue;
            }
            BigDecimal newAccumulated = asset.accumulatedDepreciation().add(amount);
            BigDecimal newBookValue = asset.acquisitionCost().subtract(newAccumulated);
            BigDecimal units = asset.unitsProduced();
            if (UNITS_OF_PRODUCTION.equals(category.depreciationMethod())) {
                units = units.add(assets.unitsProducedIn(asset.id(), from, to));
            }
            journalLines.add(new JournalLineDraft(expenseAccount(category), null, null,
                    "Depreciation " + asset.code(), amount, BigDecimal.ZERO, null, null));
            journalLines.add(new JournalLineDraft(accumulatedAccount(category), null, null,
                    "Depreciation " + asset.code(), BigDecimal.ZERO, amount, null, null));
            assets.updateDepreciation(asset.id(), newAccumulated, newBookValue, units);
            written.add(new AssetDepreciation(0, scope.organizationId(), asset.id(), year, month,
                    category.depreciationMethod(), amount, newAccumulated, newBookValue, null));
        }
        if (journalLines.isEmpty()) {
            return written;
        }
        JournalEntry journal = journals.post(scope, actor, new JournalDraft("ID", null, "DEPRECIATION",
                to, "IDR", BigDecimal.ONE, "Depreciation " + period, null, null, null,
                "Depreciation " + period, journalLines));
        List<AssetDepreciation> result = new ArrayList<>();
        for (AssetDepreciation row : written) {
            long id = assets.insertDepreciation(scope.organizationId(), row.assetId(), year, month,
                    row.method(), row.depreciationAmount(), row.accumulatedAmount(), row.bookValueAfter(),
                    journal.id());
            result.add(new AssetDepreciation(id, row.organizationId(), row.assetId(), year, month,
                    row.method(), row.depreciationAmount(), row.accumulatedAmount(), row.bookValueAfter(),
                    journal.id()));
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "ASSET_DEPRECIATION", "journal_entry",
                String.valueOf(journal.id()), "{\"period\":\"" + period + "\"}", null));
        return result;
    }

    /**
     * The monthly depreciation of one asset.
     *
     * <ul>
     *   <li>straight line: (cost - salvage) / useful life months</li>
     *   <li>declining balance: book value * (1 - (salvage/cost)^(1/life))</li>
     *   <li>units of production: (cost - salvage) / total units * units produced</li>
     * </ul>
     * The book value never drops below the salvage value.
     */
    public BigDecimal depreciationAmount(AssetCategory category, Asset asset, LocalDate from, LocalDate to) {
        BigDecimal cost = asset.acquisitionCost();
        BigDecimal salvage = asset.salvageValue();
        BigDecimal bookValue = asset.bookValue();
        if (bookValue.compareTo(salvage) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount;
        switch (category.depreciationMethod() == null ? STRAIGHT_LINE : category.depreciationMethod()) {
            case DECLINING_BALANCE -> {
                if (cost.signum() <= 0 || category.usefulLifeMonths() <= 0) {
                    return BigDecimal.ZERO;
                }
                double salvageRatio = salvage.divide(cost, 8, RoundingMode.HALF_UP).doubleValue();
                double rate = 1.0 - Math.pow(salvageRatio, 1.0 / category.usefulLifeMonths());
                amount = bookValue.multiply(BigDecimal.valueOf(rate)).setScale(2, RoundingMode.HALF_UP);
            }
            case UNITS_OF_PRODUCTION -> {
                BigDecimal totalUnits = category.totalProductionUnits();
                if (totalUnits == null || totalUnits.signum() <= 0) {
                    return BigDecimal.ZERO;
                }
                BigDecimal units = assets.unitsProducedIn(asset.id(), from, to);
                amount = cost.subtract(salvage).divide(totalUnits, 6, RoundingMode.HALF_UP)
                        .multiply(units).setScale(2, RoundingMode.HALF_UP);
            }
            default -> {
                if (category.usefulLifeMonths() <= 0) {
                    return BigDecimal.ZERO;
                }
                amount = cost.subtract(salvage)
                        .divide(BigDecimal.valueOf(category.usefulLifeMonths()), 2, RoundingMode.HALF_UP);
            }
        }
        BigDecimal floor = bookValue.subtract(salvage);
        if (amount.compareTo(floor) > 0) {
            amount = floor;
        }
        return amount.signum() < 0 ? BigDecimal.ZERO : amount;
    }

    /** Disposes an asset: the proceeds are compared with the book value. */
    public AssetDisposal dispose(SiteScope scope, String actor, long assetId, LocalDate disposalDate,
                                 BigDecimal proceeds, String reason) {
        Asset asset = assets.findById(scope.organizationId(), assetId);
        if (asset == null) {
            throw new OrderException("Asset not found: " + assetId);
        }
        if ("DISPOSED".equals(asset.status())) {
            throw new OrderException("Asset " + asset.code() + " is already disposed");
        }
        AssetCategory category = assets.findCategory(scope.organizationId(), asset.categoryId());
        if (category == null || category.assetAccountId() == null
                || category.accumulatedDepreciationAccountId() == null) {
            throw new OrderException("The asset category has no ledger accounts configured");
        }
        BigDecimal sale = proceeds == null ? BigDecimal.ZERO : proceeds;
        BigDecimal bookValue = asset.bookValue();
        BigDecimal accumulated = asset.accumulatedDepreciation();
        BigDecimal gainLoss = sale.subtract(bookValue);

        List<JournalLineDraft> lines = new ArrayList<>();
        lines.add(new JournalLineDraft(category.accumulatedDepreciationAccountId(), null, null,
                "Disposal " + asset.code(), accumulated, BigDecimal.ZERO, null, null));
        if (sale.signum() > 0) {
            lines.add(new JournalLineDraft(cashAccount(scope), null, null,
                    "Proceeds " + asset.code(), sale, BigDecimal.ZERO, null, null));
        }
        lines.add(new JournalLineDraft(category.assetAccountId(), null, null,
                "Disposal " + asset.code(), BigDecimal.ZERO, asset.acquisitionCost(), null, null));
        if (gainLoss.signum() < 0) {
            lines.add(new JournalLineDraft(lossAccount(scope), null, null,
                    "Loss on disposal " + asset.code(), gainLoss.abs(), BigDecimal.ZERO, null, null));
        } else if (gainLoss.signum() > 0) {
            lines.add(new JournalLineDraft(gainAccount(scope), null, null,
                    "Gain on disposal " + asset.code(), BigDecimal.ZERO, gainLoss, null, null));
        }

        JournalEntry journal = journals.post(scope, actor, new JournalDraft("ID", null, "DISPOSAL",
                disposalDate, "IDR", BigDecimal.ONE, asset.code(), "asset", assetId, null,
                "Disposal of " + asset.code(), lines));

        long id = assets.insertDisposal(scope.organizationId(), assetId, disposalDate, sale, bookValue,
                gainLoss, reason, journal.id(), actor);
        assets.updateStatus(assetId, "DISPOSED");
        assets.updateDepreciation(assetId, accumulated, BigDecimal.ZERO, asset.unitsProduced());
        audit.record(AuditEvent.of(scope.organizationId(), actor, "ASSET_DISPOSAL", "asset",
                String.valueOf(assetId), "{\"gainLoss\":" + gainLoss + "}", null));
        return new AssetDisposal(id, scope.organizationId(), assetId, disposalDate, sale, bookValue,
                gainLoss, reason, journal.id(), actor);
    }

    /** Moves an asset to another site. */
    public void transfer(SiteScope scope, String actor, long assetId, long toSiteId) {
        Asset asset = assets.findById(scope.organizationId(), assetId);
        if (asset == null) {
            throw new OrderException("Asset not found: " + assetId);
        }
        if (asset.siteId() == toSiteId) {
            throw new OrderException("The asset is already in that site");
        }
        assets.updateSite(assetId, toSiteId);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "ASSET_TRANSFER", "asset",
                String.valueOf(assetId), "{\"toSite\":" + toSiteId + "}", null));
    }

    public long scheduleMaintenance(SiteScope scope, String actor, long assetId, Long planId,
                                    LocalDate dueDate, String note) {
        long id = assets.insertMaintenance(scope.organizationId(), assetId, planId, dueDate, note, actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "ASSET_MAINTENANCE", "asset_maintenance",
                String.valueOf(id), null, null));
        return id;
    }

    public void completeMaintenance(SiteScope scope, String actor, long maintenanceId, LocalDate doneDate,
                                    BigDecimal cost) {
        assets.completeMaintenance(maintenanceId, doneDate, cost == null ? BigDecimal.ZERO : cost);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "ASSET_MAINTENANCE_DONE", "asset_maintenance",
                String.valueOf(maintenanceId), null, null));
    }

    public List<AssetMaintenance> maintenance(SiteScope scope, String status) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return assets.maintenance(scope.organizationId(), status);
    }

    public long addMeterReading(SiteScope scope, String actor, long assetId, LocalDate readingDate,
                                BigDecimal meterValue) {
        BigDecimal previous = BigDecimal.ZERO;
        List<AssetMeterReading> readings = assets.meterReadings(assetId);
        if (!readings.isEmpty()) {
            previous = readings.get(0).meterValue();
        }
        BigDecimal consumption = meterValue.subtract(previous);
        return assets.insertMeterReading(assetId, readingDate, meterValue, previous, consumption);
    }

    public List<AssetDepreciation> depreciationOf(SiteScope scope, long assetId) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return assets.depreciationOf(assetId);
    }

    public List<AssetMeterReading> meterReadings(SiteScope scope, long assetId) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return assets.meterReadings(assetId);
    }

    private long expenseAccount(AssetCategory category) {
        if (category.depreciationExpenseAccountId() == null) {
            throw new OrderException("The asset category has no depreciation expense account");
        }
        return category.depreciationExpenseAccountId();
    }

    private long accumulatedAccount(AssetCategory category) {
        if (category.accumulatedDepreciationAccountId() == null) {
            throw new OrderException("The asset category has no accumulated depreciation account");
        }
        return category.accumulatedDepreciationAccountId();
    }

    private long cashAccount(SiteScope scope) {
        return accountByCode(scope, "1100", "Cash on Hand");
    }

    private long lossAccount(SiteScope scope) {
        return accountByCode(scope, "5600", "FX Gain/Loss");
    }

    private long gainAccount(SiteScope scope) {
        return accountByCode(scope, "4200", "Other Income");
    }

    private long accountByCode(SiteScope scope, String code, String name) {
        var account = accounts.byCode(scope, code);
        if (account == null) {
            throw new OrderException("Chart of accounts is missing account " + code + " (" + name + ")");
        }
        return account.id();
    }

    /** The depreciation rows of one period, for the depreciation report. */
    public List<AssetDepreciation> depreciationForPeriod(SiteScope scope, int year, int month) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return assets.depreciationForPeriod(scope.organizationId(), year, month);
    }

    /** The maintenance jobs of one asset. */
    public List<AssetMaintenance> maintenanceOf(SiteScope scope, long assetId) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return assets.maintenanceOf(assetId);
    }

    /** The maintenance plans. */
    public List<MaintenancePlan> plans(SiteScope scope) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return assets.plans(scope.organizationId());
    }
}
