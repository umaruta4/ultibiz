package com.ultibiz.web.web;

import com.ultibiz.core.settings.SettingDefinition;
import com.ultibiz.core.settings.SettingDefinitions;
import com.ultibiz.core.settings.SystemSettingsService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The configuration of the deployment (Administration -> System settings).
 * Every item of the environment can be changed here; the environment variable
 * stays the fallback and the items that are read at the start of the
 * application need a restart.
 */
@Controller
@RequestMapping("/admin/settings/system")
public class SystemSettingsController {

    private final SystemSettingsService settings;

    public SystemSettingsController(SystemSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public String view(Model model) {
        List<SettingDefinition> definitions = SettingDefinitions.all();
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> secrets = new LinkedHashSet<>();
        for (SettingDefinition definition : definitions) {
            values.put(definition.key(), settings.formValue(definition.key()));
            if (definition.secret() && settings.hasSecret(definition.key())) {
                secrets.add(definition.key());
            }
        }
        model.addAttribute("definitions", definitions);
        model.addAttribute("values", values);
        model.addAttribute("secrets", secrets);
        model.addAttribute("groups", groups(definitions));
        return "admin/system-settings";
    }

    @PostMapping
    public String save(@RequestParam Map<String, String> parameters,
                       @AuthenticationPrincipal com.ultibiz.web.security.UltiBizUserPrincipal principal,
                       RedirectAttributes redirectAttributes) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> clear = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (entry.getKey().startsWith("value_")) {
                values.put(entry.getKey().substring("value_".length()), entry.getValue());
            } else if (entry.getKey().startsWith("clear_")
                    && "true".equalsIgnoreCase(entry.getValue())) {
                clear.add(entry.getKey().substring("clear_".length()));
            }
        }
        try {
            List<String> restart = settings.save(values, clear, principal.getUsername());
            redirectAttributes.addFlashAttribute("flash", "admin.system.saved");
            if (!restart.isEmpty()) {
                redirectAttributes.addFlashAttribute("restartNeeded", String.join(", ", restart));
            }
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/settings/system";
    }

    /** The groups of the page in the order of the registry. */
    private List<String> groups(List<SettingDefinition> definitions) {
        List<String> groups = new ArrayList<>();
        for (SettingDefinition definition : definitions) {
            if (!groups.contains(definition.group())) {
                groups.add(definition.group());
            }
        }
        return groups;
    }
}
