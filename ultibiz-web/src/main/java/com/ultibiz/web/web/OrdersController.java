package com.ultibiz.web.web;

import com.ultibiz.core.application.OrderException;
import com.ultibiz.core.application.PageResult;
import com.ultibiz.core.application.PartyService;
import com.ultibiz.core.application.PurchaseOrderLineDraft;
import com.ultibiz.core.application.PurchaseOrderService;
import com.ultibiz.core.application.ReceiptLine;
import com.ultibiz.core.application.SalesOrderLineDraft;
import com.ultibiz.core.application.SalesOrderService;
import com.ultibiz.core.application.ShipmentLine;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.WarehouseService;
import com.ultibiz.core.application.ProductService;
import com.ultibiz.core.domain.Party;
import com.ultibiz.core.domain.Product;
import com.ultibiz.core.domain.PurchaseOrder;
import com.ultibiz.core.domain.PurchaseOrderLine;
import com.ultibiz.core.domain.SalesOrder;
import com.ultibiz.core.domain.SalesOrderLine;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Sales and purchase orders.
 */
@Controller
@RequestMapping("/orders")
public class OrdersController {

    private static final int FORM_LINES = 8;
    private static final Pattern LINE_PATTERN =
            Pattern.compile("^line(\\d+)(ProductId|Qty|Price|Discount|Cost)$");

    private final SalesOrderService salesOrders;
    private final PurchaseOrderService purchaseOrders;
    private final PartyService parties;
    private final ProductService products;
    private final WarehouseService warehouses;
    private final SiteScopeProvider scopeProvider;

    public OrdersController(SalesOrderService salesOrders, PurchaseOrderService purchaseOrders,
                            PartyService parties, ProductService products, WarehouseService warehouses,
                            SiteScopeProvider scopeProvider) {
        this.salesOrders = salesOrders;
        this.purchaseOrders = purchaseOrders;
        this.parties = parties;
        this.products = products;
        this.warehouses = warehouses;
        this.scopeProvider = scopeProvider;
    }

    // --------------------------------------------------------------- sales

    @GetMapping("/sales")
    public String sales(@RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<SalesOrder> result = salesOrders.search(scope, status, page, size);
        model.addAttribute("orders", result);
        model.addAttribute("status", status);
        model.addAttribute("size", size);
        return "orders/sales";
    }

    @GetMapping("/sales/new")
    public String newSales(Model model) {
        SiteScope scope = scopeProvider.current();
        fillForm(model, "CUSTOMER");
        return "orders/sales-form";
    }

    @PostMapping("/sales")
    public String createSales(@RequestParam long partyId,
                               @RequestParam(required = false) LocalDate orderDate,
                               @RequestParam(required = false) LocalDate deliveryDate,
                               @RequestParam(required = false) Long warehouseId,
                               @RequestParam(required = false) String notes,
                               @RequestParam Map<String, String> params,
                               @AuthenticationPrincipal UltiBizUserPrincipal principal,
                               Model model) {
        SiteScope scope = scopeProvider.current();
        try {
            List<SalesOrderLineDraft> lines = salesLines(params);
            SalesOrder order = salesOrders.create(scope, actor(principal), "ID", partyId,
                    orderDate == null ? LocalDate.now() : orderDate, deliveryDate, warehouseId,
                    "IDR", BigDecimal.ONE, notes, lines);
            return "redirect:/orders/sales/" + order.id();
        } catch (OrderException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("partyId", partyId);
            model.addAttribute("warehouseId", warehouseId);
            model.addAttribute("notes", notes);
            fillForm(model, "CUSTOMER");
            return "orders/sales-form";
        }
    }

    @GetMapping("/sales/{id}")
    public String salesDetail(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        SalesOrder order = salesOrders.byId(scope, id);
        if (order == null) {
            return notFound(model, "Sales order " + id);
        }
        List<SalesOrderLine> lines = salesOrders.lines(scope, id);
        model.addAttribute("order", order);
        model.addAttribute("lines", lines);
        model.addAttribute("party", parties.findById(scope, order.partyId()).orElse(null));
        model.addAttribute("productNames", productNames(scope));
        return "orders/sales-detail";
    }

    @PostMapping("/sales/{id}/confirm")
    public String confirmSales(@PathVariable long id, @AuthenticationPrincipal UltiBizUserPrincipal principal,
                               RedirectAttributes redirect) {
        try {
            salesOrders.confirm(scopeProvider.current(), actor(principal), id);
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/orders/sales/" + id;
    }

    @PostMapping("/sales/{id}/deliver")
    public String deliverSales(@PathVariable long id, @AuthenticationPrincipal UltiBizUserPrincipal principal,
                               RedirectAttributes redirect) {
        SiteScope scope = scopeProvider.current();
        try {
            List<ShipmentLine> shipment = new ArrayList<>();
            for (SalesOrderLine line : salesOrders.lines(scope, id)) {
                BigDecimal remaining = line.quantity().subtract(line.quantityShipped());
                if (remaining.signum() > 0) {
                    shipment.add(new ShipmentLine(line.id(), remaining));
                }
            }
            salesOrders.deliver(scope, actor(principal), id, shipment);
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/orders/sales/" + id;
    }

    // ------------------------------------------------------------ purchase

    @GetMapping("/purchase")
    public String purchase(@RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size,
                           Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<PurchaseOrder> result = purchaseOrders.search(scope, status, page, size);
        model.addAttribute("orders", result);
        model.addAttribute("status", status);
        model.addAttribute("size", size);
        return "orders/purchase";
    }

    @GetMapping("/purchase/new")
    public String newPurchase(Model model) {
        SiteScope scope = scopeProvider.current();
        fillForm(model, "SUPPLIER");
        return "orders/purchase-form";
    }

    @PostMapping("/purchase")
    public String createPurchase(@RequestParam long partyId,
                                  @RequestParam(required = false) LocalDate orderDate,
                                  @RequestParam(required = false) LocalDate expectedDate,
                                  @RequestParam(required = false) Long warehouseId,
                                  @RequestParam(required = false) String notes,
                                  @RequestParam Map<String, String> params,
                                  @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                  Model model) {
        SiteScope scope = scopeProvider.current();
        try {
            List<PurchaseOrderLineDraft> lines = purchaseLines(params);
            PurchaseOrder order = purchaseOrders.create(scope, actor(principal), "ID", partyId,
                    orderDate == null ? LocalDate.now() : orderDate, expectedDate, warehouseId,
                    "IDR", BigDecimal.ONE, notes, lines);
            return "redirect:/orders/purchase/" + order.id();
        } catch (OrderException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("partyId", partyId);
            model.addAttribute("warehouseId", warehouseId);
            model.addAttribute("notes", notes);
            fillForm(model, "SUPPLIER");
            return "orders/purchase-form";
        }
    }

    @GetMapping("/purchase/{id}")
    public String purchaseDetail(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        PurchaseOrder order = purchaseOrders.byId(scope, id);
        if (order == null) {
            return notFound(model, "Purchase order " + id);
        }
        List<PurchaseOrderLine> lines = purchaseOrders.lines(scope, id);
        model.addAttribute("order", order);
        model.addAttribute("lines", lines);
        model.addAttribute("party", parties.findById(scope, order.partyId()).orElse(null));
        model.addAttribute("productNames", productNames(scope));
        return "orders/purchase-detail";
    }

    @PostMapping("/purchase/{id}/confirm")
    public String confirmPurchase(@PathVariable long id, @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                  RedirectAttributes redirect) {
        try {
            purchaseOrders.confirm(scopeProvider.current(), actor(principal), id);
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/orders/purchase/" + id;
    }

    @PostMapping("/purchase/{id}/receive")
    public String receivePurchase(@PathVariable long id, @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                  RedirectAttributes redirect) {
        SiteScope scope = scopeProvider.current();
        try {
            List<ReceiptLine> receipt = new ArrayList<>();
            for (PurchaseOrderLine line : purchaseOrders.lines(scope, id)) {
                BigDecimal remaining = line.quantity().subtract(line.quantityReceived());
                if (remaining.signum() > 0) {
                    receipt.add(new ReceiptLine(line.id(), remaining, line.unitPrice()));
                }
            }
            purchaseOrders.receive(scope, actor(principal), id, receipt);
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/orders/purchase/" + id;
    }

    // ------------------------------------------------------------- helpers

    private void fillForm(Model model, String partyType) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("parties", parties.all(scope, null, partyType, true));
        model.addAttribute("products", products.all(scope, null, null, true));
        model.addAttribute("warehouses", warehouses.search(scope, null, true, 0, 100).items());
        model.addAttribute("lineCount", FORM_LINES);
        model.addAttribute("today", LocalDate.now());
    }

    private List<SalesOrderLineDraft> salesLines(Map<String, String> params) {
        List<SalesOrderLineDraft> lines = new ArrayList<>();
        for (int index = 1; index <= FORM_LINES; index++) {
            Long productId = longOrNull(params.get("line" + index + "ProductId"));
            if (productId == null) {
                continue;
            }
            BigDecimal quantity = amount(params.get("line" + index + "Qty"));
            BigDecimal price = amount(params.get("line" + index + "Price"));
            BigDecimal discount = amount(params.get("line" + index + "Discount"));
            if (quantity.signum() <= 0) {
                continue;
            }
            Long productIdValue = productId;
            Product product = products.findById(scopeProvider.current(), productIdValue).orElse(null);
            lines.add(new SalesOrderLineDraft(productIdValue,
                    product != null ? product.name() : "Product " + productIdValue,
                    quantity, price, discount, product != null ? product.taxCategoryId() : null));
        }
        return lines;
    }

    private List<PurchaseOrderLineDraft> purchaseLines(Map<String, String> params) {
        List<PurchaseOrderLineDraft> lines = new ArrayList<>();
        for (int index = 1; index <= FORM_LINES; index++) {
            Long productId = longOrNull(params.get("line" + index + "ProductId"));
            if (productId == null) {
                continue;
            }
            BigDecimal quantity = amount(params.get("line" + index + "Qty"));
            BigDecimal price = amount(params.get("line" + index + "Price"));
            if (quantity.signum() <= 0) {
                continue;
            }
            lines.add(new PurchaseOrderLineDraft(productId, "Product " + productId, quantity, price, null));
        }
        return lines;
    }

    private Map<Long, String> productNames(SiteScope scope) {
        Map<Long, String> names = new HashMap<>();
        for (Product product : products.all(scope, null, null, false)) {
            names.put(product.id(), product.code() + " — " + product.name());
        }
        return names;
    }

    private static Long longOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal amount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }

    private static String notFound(Model model, String what) {
        model.addAttribute("status", 404);
        model.addAttribute("error", "Not found");
        model.addAttribute("message", what + " does not exist");
        return "error";
    }
}
