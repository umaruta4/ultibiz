package com.ultibiz.web.web;

import com.ultibiz.core.application.MrpService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.web.security.SiteScopeProvider;
import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Material requirements planning: the purchase suggestions. */
@Controller
@RequestMapping("/planning")
public class PlanningController {

    private final MrpService mrp;
    private final SiteScopeProvider scopeProvider;

    public PlanningController(MrpService mrp, SiteScopeProvider scopeProvider) {
        this.mrp = mrp;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping("/mrp")
    public String suggestions(@RequestParam(required = false) LocalDate on,
                              Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("suggestions", mrp.suggestions(scope, on == null ? LocalDate.now() : on));
        model.addAttribute("on", on == null ? LocalDate.now() : on);
        return "planning/mrp";
    }
}
