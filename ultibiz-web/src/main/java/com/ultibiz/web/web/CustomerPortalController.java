package com.ultibiz.web.web;

import com.ultibiz.core.application.HelpdeskService;
import com.ultibiz.core.helpdesk.HelpdeskException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The customer portal: the read-only page a customer opens with the link of
 * the ticket. The token in the link is the secret — no account is needed.
 */
@Controller
@RequestMapping("/portal")
public class CustomerPortalController {

    private final HelpdeskService helpdesk;

    public CustomerPortalController(HelpdeskService helpdesk) {
        this.helpdesk = helpdesk;
    }

    /**
     * A token that is not known any more is a dead link of the customer: the
     * answer is 404, not an error of the server.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(HelpdeskException.class)
    public void deadLink(HttpServletResponse response) throws java.io.IOException {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @GetMapping("/{token}")
    public String ticket(@PathVariable String token, Model model) {
        var ticket = helpdesk.portalTicket(token);
        model.addAttribute("ticket", ticket);
        model.addAttribute("activities", helpdesk.portalActivities(token));
        return "helpdesk/portal";
    }

    @PostMapping("/{token}")
    public String reply(@PathVariable String token,
                        @RequestParam String body) {
        var ticket = helpdesk.portalTicket(token);
        helpdesk.portalReply(token, body, ticket.requesterName());
        return "redirect:/portal/" + token;
    }
}
