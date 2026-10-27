package com.roomkeeper.payment;

/** An open checkout session: its provider id, and the page where the guest pays. */
public record CheckoutSession(String id, String url) {
}
