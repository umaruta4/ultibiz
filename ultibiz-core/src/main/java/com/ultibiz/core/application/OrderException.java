package com.ultibiz.core.application;

/**
 * Raised when an order, receipt or work order violates a business rule
 * (credit limit, insufficient stock, wrong status). Nothing is written.
 */
public class OrderException extends RuntimeException {

    public OrderException(String message) {
        super(message);
    }
}
