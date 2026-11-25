package com.ultibiz.web.web;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Persists the light/dark theme choice: in the HTTP session immediately, and
 * into the user profile (deferred to P1 user-settings work — for P0 the
 * session value is authoritative and the JS keeps a localStorage copy
 * per-device).
 */
@RestController
@RequestMapping("/api/me")
public class ThemeController {

    public record ThemeRequest(
            @NotBlank @Pattern(regexp = "light|dark") String theme) {
    }

    @PostMapping("/theme")
    public ResponseEntity<Map<String, String>> setTheme(@RequestBody ThemeRequest request,
                                                        HttpSession session) {
        session.setAttribute("theme", request.theme());
        return ResponseEntity.ok(Map.of("theme", request.theme()));
    }
}
