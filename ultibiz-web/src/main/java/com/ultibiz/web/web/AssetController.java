package com.ultibiz.web.web;

import com.ultibiz.core.application.AssetService;
import com.ultibiz.core.application.OrderException;
import com.ultibiz.core.application.PageResult;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.domain.Asset;
import com.ultibiz.core.domain.AssetCategory;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The asset register: registration, the monthly depreciation run, disposal,
 * transfers and maintenance.
 */
@Controller
@RequestMapping("/assets")
public class AssetController {

    private final AssetService assets;
    private final SiteScopeProvider scopeProvider;

    public AssetController(AssetService assets, SiteScopeProvider scopeProvider) {
        this.assets = assets;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<Asset> result = assets.search(scope, status, page, size);
        model.addAttribute("assets", result);
        model.addAttribute("status", status);
        model.addAttribute("size", size);
        model.addAttribute("categories", categoryNames(scope));
        return "assets/list";
    }

    @GetMapping("/new")
    public String newAsset(Model model) {
        fillForm(model);
        return "assets/form";
    }

    @PostMapping
    public String register(@RequestParam long categoryId,
                           @RequestParam String code,
                           @RequestParam String name,
                           @RequestParam LocalDate acquisitionDate,
                           @RequestParam BigDecimal acquisitionCost,
                           @RequestParam(required = false) BigDecimal salvageValue,
                           @RequestParam(required = false) String serialNumber,
                           @RequestParam(required = false) LocalDate warrantyEnd,
                           @AuthenticationPrincipal UltiBizUserPrincipal principal,
                           Model model) {
        SiteScope scope = scopeProvider.current();
        try {
            Asset asset = assets.register(scope, actor(principal), categoryId, code.trim().toUpperCase(),
                    name.trim(), acquisitionDate, acquisitionCost, salvageValue, null,
                    serialNumber, null, warrantyEnd);
            return "redirect:/assets/" + asset.id();
        } catch (OrderException ex) {
            model.addAttribute("error", ex.getMessage());
            fillForm(model);
            return "assets/form";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        Asset asset = assets.byId(scope, id);
        if (asset == null) {
            model.addAttribute("status", 404);
            model.addAttribute("error", "Not found");
            model.addAttribute("message", "Asset " + id + " does not exist");
            return "error";
        }
        model.addAttribute("asset", asset);
        model.addAttribute("category", categoryName(scope, asset.categoryId()));
        model.addAttribute("depreciations", assets.depreciationOf(scope, id));
        model.addAttribute("maintenance", assets.maintenanceOf(scope, id));
        model.addAttribute("readings", assets.meterReadings(scope, id));
        return "assets/detail";
    }

    @PostMapping("/{id}/dispose")
    public String dispose(@PathVariable long id,
                          @RequestParam(required = false) BigDecimal proceeds,
                          @RequestParam(required = false) String reason,
                          @AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            assets.dispose(scopeProvider.current(), actor(principal), id, LocalDate.now(),
                    proceeds, reason);
            redirect.addFlashAttribute("flash", "asset.disposed");
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/assets/" + id;
    }

    @PostMapping("/{id}/transfer")
    public String transfer(@PathVariable long id,
                           @RequestParam long toSiteId,
                           @AuthenticationPrincipal UltiBizUserPrincipal principal,
                           RedirectAttributes redirect) {
        try {
            assets.transfer(scopeProvider.current(), actor(principal), id, toSiteId);
            redirect.addFlashAttribute("flash", "asset.transferred");
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/assets/" + id;
    }

    @PostMapping("/{id}/meter")
    public String meter(@PathVariable long id,
                        @RequestParam LocalDate readingDate,
                        @RequestParam BigDecimal meterValue,
                        @AuthenticationPrincipal UltiBizUserPrincipal principal,
                        RedirectAttributes redirect) {
        assets.addMeterReading(scopeProvider.current(), actor(principal), id, readingDate, meterValue);
        redirect.addFlashAttribute("flash", "asset.readingAdded");
        return "redirect:/assets/" + id;
    }

    @GetMapping("/depreciation")
    public String depreciation(@RequestParam(required = false) Integer year,
                               @RequestParam(required = false) Integer month,
                               Model model) {
        SiteScope scope = scopeProvider.current();
        YearMonth period = YearMonth.of(year == null ? LocalDate.now().getYear() : year,
                month == null ? LocalDate.now().getMonthValue() : month);
        model.addAttribute("period", period);
        model.addAttribute("rows", assets.depreciationForPeriod(scope, period.getYear(),
                period.getMonthValue()));
        model.addAttribute("assets", assets.search(scope, null, 0, 500));
        return "assets/depreciation";
    }

    @PostMapping("/depreciation/run")
    public String runDepreciation(@RequestParam int year,
                                  @RequestParam int month,
                                  @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                  RedirectAttributes redirect) {
        try {
            var rows = assets.depreciate(scopeProvider.current(), actor(principal), year, month);
            redirect.addFlashAttribute("flash", rows.isEmpty() ? "asset.nothingToDepreciate"
                    : "asset.depreciationPosted");
            redirect.addFlashAttribute("depreciatedCount", rows.size());
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/assets/depreciation?year=" + year + "&month=" + month;
    }

    @GetMapping("/maintenance")
    public String maintenance(@RequestParam(required = false) String status, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("maintenance", assets.maintenance(scope, status));
        model.addAttribute("status", status);
        model.addAttribute("plans", assets.plans(scope));
        model.addAttribute("assets", assets.search(scope, null, 0, 500));
        model.addAttribute("today", LocalDate.now());
        return "assets/maintenance";
    }

    @PostMapping("/maintenance")
    public String scheduleMaintenance(@RequestParam long assetId,
                                      @RequestParam(required = false) Long planId,
                                      @RequestParam LocalDate dueDate,
                                      @RequestParam(required = false) String note,
                                      @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                      RedirectAttributes redirect) {
        assets.scheduleMaintenance(scopeProvider.current(), actor(principal), assetId, planId, dueDate, note);
        redirect.addFlashAttribute("flash", "asset.maintenanceScheduled");
        return "redirect:/assets/maintenance";
    }

    @PostMapping("/maintenance/{id}/complete")
    public String completeMaintenance(@PathVariable long id,
                                      @RequestParam(required = false) BigDecimal cost,
                                      @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                      RedirectAttributes redirect) {
        assets.completeMaintenance(scopeProvider.current(), actor(principal), id, LocalDate.now(),
                cost == null ? BigDecimal.ZERO : cost);
        redirect.addFlashAttribute("flash", "asset.maintenanceCompleted");
        return "redirect:/assets/maintenance";
    }

    private void fillForm(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("categories", assets.categories(scope));
        model.addAttribute("today", LocalDate.now());
    }

    private java.util.Map<Long, String> categoryNames(SiteScope scope) {
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (AssetCategory category : assets.categories(scope)) {
            names.put(category.id(), category.code() + " — " + category.name());
        }
        return names;
    }

    private String categoryName(SiteScope scope, long categoryId) {
        for (AssetCategory category : assets.categories(scope)) {
            if (category.id() == categoryId) {
                return category.code() + " — " + category.name();
            }
        }
        return "";
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
