package com.ultibiz.web.web;

import com.ultibiz.core.application.MasterDataException;
import com.ultibiz.core.application.NumberingException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Turns application-level rule violations into a readable error page instead of
 * a raw 500. Domain rules (duplicate code, stale row_version, missing numbering
 * sequence) are the user's problem, not a server fault.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MasterDataException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleMasterData(MasterDataException ex, HttpServletRequest request, Model model) {
        LOG.warn("master data rule violated: {}", ex.getMessage());
        model.addAttribute("status", 400);
        model.addAttribute("error", "Bad Request");
        model.addAttribute("message", ex.getMessage());
        model.addAttribute("path", request.getRequestURI());
        return "error";
    }

    @ExceptionHandler(NumberingException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleNumbering(NumberingException ex, HttpServletRequest request, Model model) {
        LOG.warn("numbering failed: {}", ex.getMessage());
        model.addAttribute("status", 409);
        model.addAttribute("error", "Conflict");
        model.addAttribute("message", ex.getMessage());
        model.addAttribute("path", request.getRequestURI());
        return "error";
    }
}
