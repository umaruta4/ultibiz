package com.ultibiz.web.web;

import com.ultibiz.core.application.ExchangeService;
import com.ultibiz.core.application.HelpdeskService;
import com.ultibiz.core.application.OrderException;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.helpdesk.HelpdeskException;
import com.ultibiz.web.config.SecurityConfig;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The machine endpoints: a partner calls the inbound webhook without a browser
 * (there is no CSRF token in a partner request), and a customer opens the
 * portal with a token that may be unknown.
 *
 * Regression tests for the two defects the end-to-end run found: the webhook
 * answered a redirect to the login page instead of a machine answer, and an
 * unknown portal token answered 500 instead of 404.
 */
@WebMvcTest(controllers = {ExchangeApiController.class, CustomerPortalController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = "ultibiz.exchange.webhook-secret=qa-secret")
class MachineEndpointsTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ExchangeService exchange;
    @MockitoBean
    private HelpdeskService helpdesk;
    @MockitoBean
    private SiteScopeProvider scopeProvider;
    @MockitoBean
    private UltiBizUserDetailsService userDetailsService;

    @Test
    void thePartnerWebhookIsNotBlockedByTheCsrfProtection() throws Exception {
        // a wrong secret is the answer of the controller (401), not a csrf refusal
        mvc.perform(post("/api/exchange/inbound/qa").content("{}")
                        .header("X-Exchange-Secret", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePartnerWebhookAcceptsAPayloadWithTheSharedSecret() throws Exception {
        when(exchange.scopeOfPartner("qa")).thenReturn(new SiteScope(1L, java.util.Set.of(), false));
        when(exchange.receive(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ExchangeService.ReceiveResult(7L, false));
        mvc.perform(post("/api/exchange/inbound/qa").content("{\"invoice\":\"INV-1\"}")
                        .header("X-Exchange-Secret", "qa-secret")
                        .header("X-Idempotency-Key", "qa-key"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageId").value(7));
    }

    @Test
    void anUnknownPartnerIsRefusedWithNotFound() throws Exception {
        when(exchange.scopeOfPartner("unknown"))
                .thenThrow(new OrderException("Unknown exchange partner: unknown"));
        mvc.perform(post("/api/exchange/inbound/unknown").content("{}")
                        .header("X-Exchange-Secret", "qa-secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePortalAnswersNotFoundForAnUnknownToken() throws Exception {
        when(helpdesk.portalTicket("missing"))
                .thenThrow(new HelpdeskException("This link is not valid any more"));
        mvc.perform(get("/portal/missing"))
                .andExpect(status().isNotFound());
    }
}
