package com.ultibiz.web.web;

import com.ultibiz.core.application.ManufacturingService;
import com.ultibiz.core.application.OrderException;
import com.ultibiz.core.application.PageResult;
import com.ultibiz.core.application.ProductService;
import com.ultibiz.core.application.ProductionLineService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.WarehouseService;
import com.ultibiz.core.domain.WorkOrder;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/** Work orders: creation, release, issue and completion. */
@Controller
@RequestMapping("/manufacturing/work-orders")
public class ManufacturingController {

    private final ManufacturingService manufacturing;
    private final ProductService products;
    private final WarehouseService warehouses;
    private final ProductionLineService productionLines;
    private final SiteScopeProvider scopeProvider;

    public ManufacturingController(ManufacturingService manufacturing, ProductService products,
                                   WarehouseService warehouses, ProductionLineService productionLines,
                                   SiteScopeProvider scopeProvider) {
        this.manufacturing = manufacturing;
        this.products = products;
        this.warehouses = warehouses;
        this.productionLines = productionLines;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<WorkOrder> result = manufacturing.search(scope, status, page, size);
        model.addAttribute("orders", result);
        model.addAttribute("status", status);
        model.addAttribute("size", size);
        model.addAttribute("productNames", productNames(scope));
        return "manufacturing/work-orders";
    }

    @GetMapping("/new")
    public String newOrder(Model model) {
        fillForm(model);
        return "manufacturing/work-order-form";
    }

    @PostMapping
    public String create(@RequestParam long productId,
                         @RequestParam(required = false) Long bomId,
                         @RequestParam long warehouseId,
                         @RequestParam(required = false) Long productionLineId,
                         @RequestParam BigDecimal quantity,
                         @RequestParam(required = false) LocalDate plannedStart,
                         @RequestParam(required = false) LocalDate plannedEnd,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         Model model) {
        try {
            WorkOrder order = manufacturing.create(scopeProvider.current(), actor(principal), productId, bomId,
                    warehouseId, productionLineId, quantity, plannedStart, plannedEnd);
            return "redirect:/manufacturing/work-orders/" + order.id();
        } catch (OrderException ex) {
            model.addAttribute("error", ex.getMessage());
            fillForm(model);
            return "manufacturing/work-order-form";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        WorkOrder order = manufacturing.byId(scope, id);
        if (order == null) {
            model.addAttribute("status", 404);
            model.addAttribute("error", "Not found");
            model.addAttribute("message", "Work order " + id + " does not exist");
            return "error";
        }
        model.addAttribute("order", order);
        model.addAttribute("materials", manufacturing.materials(scope, id));
        model.addAttribute("productNames", productNames(scope));
        return "manufacturing/work-order-detail";
    }

    @PostMapping("/{id}/release")
    public String release(@PathVariable long id, @AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            manufacturing.release(scopeProvider.current(), actor(principal), id);
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manufacturing/work-orders/" + id;
    }

    @PostMapping("/{id}/issue")
    public String issue(@PathVariable long id, @AuthenticationPrincipal UltiBizUserPrincipal principal,
                        RedirectAttributes redirect) {
        try {
            manufacturing.issue(scopeProvider.current(), actor(principal), id);
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manufacturing/work-orders/" + id;
    }

    @PostMapping("/{id}/complete")
    public String complete(@PathVariable long id,
                           @RequestParam(required = false) BigDecimal producedQuantity,
                           @AuthenticationPrincipal UltiBizUserPrincipal principal,
                           RedirectAttributes redirect) {
        try {
            manufacturing.complete(scopeProvider.current(), actor(principal), id, producedQuantity);
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manufacturing/work-orders/" + id;
    }

    private void fillForm(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("products", products.all(scope, null, null, true));
        model.addAttribute("warehouses", warehouses.search(scope, null, true, 0, 100).items());
        model.addAttribute("productionLines", productionLines.search(scope, null, true, 0, 100).items());
        model.addAttribute("today", LocalDate.now());
    }

    private java.util.Map<Long, String> productNames(SiteScope scope) {
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (var product : products.all(scope, null, null, false)) {
            names.put(product.id(), product.code() + " — " + product.name());
        }
        return names;
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
