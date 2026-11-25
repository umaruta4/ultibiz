package com.ultibiz.web.web;

import com.ultibiz.core.application.ImportReport;
import com.ultibiz.core.application.MasterDataCsvService;
import com.ultibiz.core.application.MasterDataException;
import com.ultibiz.core.application.PageResult;
import com.ultibiz.core.application.PartyDraft;
import com.ultibiz.core.application.PartyService;
import com.ultibiz.core.application.ProductDraft;
import com.ultibiz.core.application.ProductService;
import com.ultibiz.core.application.ProductionLineService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.SiteService;
import com.ultibiz.core.application.WarehouseService;
import com.ultibiz.core.domain.Dock;
import com.ultibiz.core.domain.Party;
import com.ultibiz.core.domain.ProductionLine;
import com.ultibiz.core.domain.Product;
import com.ultibiz.core.domain.Rack;
import com.ultibiz.core.domain.Site;
import com.ultibiz.core.domain.StorageLocation;
import com.ultibiz.core.domain.Warehouse;
import com.ultibiz.core.domain.WorkCenter;
import com.ultibiz.core.infrastructure.UomRepository;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Master-data screens: products, parties, warehouses and production lines.
 *
 * <p>Every read is filtered by the caller's site scope inside the repository
 * layer; this controller only shapes the view.
 */
@Controller
@RequestMapping("/master")
public class MasterDataController {

    private static final String IMPORT_ERRORS = "ultibiz.import.errors.csv";

    private final ProductService products;
    private final PartyService parties;
    private final WarehouseService warehouses;
    private final ProductionLineService productionLines;
    private final MasterDataCsvService csv;
    private final UomRepository uoms;
    private final SiteService sites;
    private final SiteScopeProvider scopeProvider;

    public MasterDataController(ProductService products, PartyService parties,
                                WarehouseService warehouses, ProductionLineService productionLines,
                                MasterDataCsvService csv, UomRepository uoms,
                                SiteService sites, SiteScopeProvider scopeProvider) {
        this.products = products;
        this.parties = parties;
        this.warehouses = warehouses;
        this.productionLines = productionLines;
        this.csv = csv;
        this.uoms = uoms;
        this.sites = sites;
        this.scopeProvider = scopeProvider;
    }

    // ------------------------------------------------------------ products

    @GetMapping("/products")
    public String products(@RequestParam(required = false) String search,
                           @RequestParam(required = false) String type,
                           @RequestParam(defaultValue = "false") boolean activeOnly,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size,
                           Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<Product> result = products.search(scope, search, type, activeOnly, page, size);
        model.addAttribute("products", result);
        model.addAttribute("search", search);
        model.addAttribute("type", type);
        model.addAttribute("activeOnly", activeOnly);
        model.addAttribute("size", size);
        return "master/products";
    }

    @GetMapping("/products/new")
    public String newProduct(Model model) {
        model.addAttribute("product", null);
        model.addAttribute("uoms", uoms.codes());
        model.addAttribute("sites", sites.listVisibleTo(scopeProvider.current()));
        return "master/product-form";
    }

    @GetMapping("/products/{id}/edit")
    public String editProduct(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        Product product = products.findById(scope, id)
                .orElseThrow(() -> new MasterDataException("Product not found: " + id));
        model.addAttribute("product", product);
        model.addAttribute("uoms", uoms.codes());
        model.addAttribute("sites", sites.listVisibleTo(scope));
        return "master/product-form";
    }

    @PostMapping("/products")
    public String createProduct(@RequestParam(required = false) Long siteId,
                                 @RequestParam String code,
                                 @RequestParam String name,
                                 @RequestParam(defaultValue = "GOODS") String productType,
                                 @RequestParam String uomCode,
                                 @RequestParam(required = false) String barcode,
                                 @RequestParam(defaultValue = "false") boolean batchTracked,
                                 @RequestParam(defaultValue = "false") boolean serialTracked,
                                 @RequestParam(defaultValue = "true") boolean trackInventory,
                                 @RequestParam(defaultValue = "0") BigDecimal standardCost,
                                 @RequestParam(defaultValue = "IDR") String currency,
                                 @RequestParam(required = false) String description,
                                 @RequestParam(defaultValue = "true") boolean active,
                                 @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                 RedirectAttributes redirect) {
        products.create(scopeProvider.current(), actor(principal),
                new ProductDraft(siteId, code.trim(), name.trim(), productType.toUpperCase(),
                        uomCode.trim(), trimToNull(barcode), batchTracked, serialTracked,
                        trackInventory, standardCost, currency.toUpperCase(), null,
                        trimToNull(description), active));
        redirect.addFlashAttribute("flash", "md.saved");
        return "redirect:/master/products";
    }

    @PostMapping("/products/{id}")
    public String updateProduct(@PathVariable long id,
                                 @RequestParam(required = false) Long siteId,
                                 @RequestParam String code,
                                 @RequestParam String name,
                                 @RequestParam(defaultValue = "GOODS") String productType,
                                 @RequestParam String uomCode,
                                 @RequestParam(required = false) String barcode,
                                 @RequestParam(defaultValue = "false") boolean batchTracked,
                                 @RequestParam(defaultValue = "false") boolean serialTracked,
                                 @RequestParam(defaultValue = "true") boolean trackInventory,
                                 @RequestParam(defaultValue = "0") BigDecimal standardCost,
                                 @RequestParam(defaultValue = "IDR") String currency,
                                 @RequestParam(required = false) String description,
                                 @RequestParam(defaultValue = "true") boolean active,
                                 @RequestParam(defaultValue = "0") long rowVersion,
                                 @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                 RedirectAttributes redirect) {
        products.update(scopeProvider.current(), actor(principal), id,
                new ProductDraft(siteId, code.trim(), name.trim(), productType.toUpperCase(),
                        uomCode.trim(), trimToNull(barcode), batchTracked, serialTracked,
                        trackInventory, standardCost, currency.toUpperCase(), null,
                        trimToNull(description), active),
                rowVersion);
        redirect.addFlashAttribute("flash", "md.saved");
        return "redirect:/master/products";
    }

    @PostMapping("/products/{id}/delete")
    public String deleteProduct(@PathVariable long id,
                                @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                RedirectAttributes redirect) {
        products.delete(scopeProvider.current(), actor(principal), id);
        redirect.addFlashAttribute("flash", "md.deleted");
        return "redirect:/master/products";
    }

    @GetMapping("/products/export.csv")
    public ResponseEntity<String> exportProducts(@RequestParam(required = false) String search,
                                                  @RequestParam(required = false) String type,
                                                  @RequestParam(defaultValue = "false") boolean activeOnly) {
        String body = csv.exportProducts(products.all(scopeProvider.current(), search, type, activeOnly));
        return csvResponse(body, "products.csv");
    }

    // ------------------------------------------------------------- parties

    @GetMapping("/parties")
    public String parties(@RequestParam(required = false) String search,
                          @RequestParam(required = false) String type,
                          @RequestParam(defaultValue = "false") boolean activeOnly,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size,
                          Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<Party> result = parties.search(scope, search, type, activeOnly, page, size);
        model.addAttribute("parties", result);
        model.addAttribute("search", search);
        model.addAttribute("type", type);
        model.addAttribute("activeOnly", activeOnly);
        model.addAttribute("size", size);
        return "master/parties";
    }

    @GetMapping("/parties/export.csv")
    public ResponseEntity<String> exportParties(@RequestParam(required = false) String search,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(defaultValue = "false") boolean activeOnly) {
        String body = csv.exportParties(parties.all(scopeProvider.current(), search, type, activeOnly));
        return csvResponse(body, "parties.csv");
    }

    // ---------------------------------------------------------- warehouses

    @GetMapping("/warehouses")
    public String warehouses(@RequestParam(required = false) String search,
                             @RequestParam(defaultValue = "false") boolean activeOnly,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size,
                             Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<Warehouse> result = warehouses.search(scope, search, activeOnly, page, size);
        model.addAttribute("warehouses", result);
        model.addAttribute("search", search);
        model.addAttribute("activeOnly", activeOnly);
        model.addAttribute("size", size);
        model.addAttribute("structures", result.items().stream()
                .map(w -> new WarehouseStructure(w, warehouses.racks(w.id()),
                        warehouses.locations(w.id()), warehouses.docks(w.id())))
                .toList());
        return "master/warehouses";
    }

    // ---------------------------------------------------- production lines

    @GetMapping("/production-lines")
    public String productionLines(@RequestParam(required = false) String search,
                                  @RequestParam(defaultValue = "false") boolean activeOnly,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<ProductionLine> result = productionLines.search(scope, search, activeOnly, page, size);
        model.addAttribute("lines", result);
        model.addAttribute("search", search);
        model.addAttribute("activeOnly", activeOnly);
        model.addAttribute("size", size);
        model.addAttribute("workCenters", productionLines.workCenters(scope));
        return "master/production-lines";
    }

    // ---------------------------------------------------------- CSV import

    @PostMapping("/products/import")
    public String importProducts(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
                                 HttpServletRequest request,
                                 Model model) {
        ImportReport report = csv.importProducts(scopeProvider.current(), readCsv(file), dryRun);
        storeErrors(request, report);
        model.addAttribute("report", report);
        model.addAttribute("kind", "md.products");
        return "master/import";
    }

    @PostMapping("/parties/import")
    public String importParties(@RequestParam("file") MultipartFile file,
                                @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
                                HttpServletRequest request,
                                Model model) {
        ImportReport report = csv.importParties(scopeProvider.current(), readCsv(file), dryRun);
        storeErrors(request, report);
        model.addAttribute("report", report);
        model.addAttribute("kind", "md.parties");
        return "master/import";
    }

    @GetMapping("/import/errors.csv")
    public ResponseEntity<String> importErrors(HttpSession session) {
        Object stored = session.getAttribute(IMPORT_ERRORS);
        String body = stored instanceof String s ? s : "";
        return csvResponse(body, "import-errors.csv");
    }

    /** A warehouse with its structure, ready for the view. */
    public record WarehouseStructure(Warehouse warehouse, List<Rack> racks,
                                     List<StorageLocation> locations, List<Dock> docks) {
    }

    // ------------------------------------------------------------ helpers

    private void storeErrors(HttpServletRequest request, ImportReport report) {
        HttpSession session = request.getSession();
        session.setAttribute(IMPORT_ERRORS, csv.errorCsv(report));
    }

    private String readCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MasterDataException("No CSV file was uploaded");
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new MasterDataException("Could not read the uploaded file: " + ex.getMessage());
        }
    }

    private static ResponseEntity<String> csvResponse(String body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
