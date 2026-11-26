package com.ultibiz.web.web;

import com.ultibiz.core.application.HelpdeskService;
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
