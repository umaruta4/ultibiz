package com.ultibiz.web.web;

import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.OrganizationService;
import com.ultibiz.core.application.SiteService;
import com.ultibiz.core.application.UserService;
import com.ultibiz.web.config.SecurityConfig;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserDetailsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {HomeController.class, ThemeController.class})
@Import(SecurityConfig.class)
class WebSmokeTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OrganizationService organizations;
    @MockitoBean
    private SiteService sites;
    @MockitoBean
    private UserService users;
    @MockitoBean
    private AuditService audit;
    @MockitoBean
    private SiteScopeProvider scopeProvider;
    @MockitoBean
    private UltiBizUserDetailsService userDetailsService;

    @Test
    void loginPageIsPublic() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("UltiBiz")));
    }

    @Test
    void homeRequiresAuthentication() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void homeRendersForAuthenticatedUser() throws Exception {
        when(organizations.count()).thenReturn(1L);
        when(sites.countVisibleTo(any())).thenReturn(2L);
        when(users.count()).thenReturn(1L);
        when(audit.recent(any(Long.class), any(Integer.class))).thenReturn(List.of());

        mvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("UltiBiz")));
    }
}
