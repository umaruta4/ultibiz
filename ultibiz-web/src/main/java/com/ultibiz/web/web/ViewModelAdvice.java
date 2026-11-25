package com.ultibiz.web.web;

import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Model attributes every page needs (user name, app version, server time).
 * A controller may still override them.
 */
@ControllerAdvice
public class ViewModelAdvice {

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String appVersion;

    public ViewModelAdvice(@Value("${info.app.version:dev}") String appVersion) {
        this.appVersion = appVersion;
    }

    @ModelAttribute("username")
    public String username(@AuthenticationPrincipal UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "";
    }

    @ModelAttribute("appVersion")
    public String appVersion() {
        return appVersion;
    }

    @ModelAttribute("serverTime")
    public String serverTime() {
        return ZonedDateTime.now(APP_ZONE).format(TS) + " WIB";
    }

    @ModelAttribute("uptime")
    public String uptime() {
        Duration d = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
        return d.toHours() + "h " + d.toMinutesPart() + "m";
    }
}
