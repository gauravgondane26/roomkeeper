package com.roomkeeper.outbox;

/**
 * An email to send. The idempotency key is what makes a resend harmless: the sender keeps
 * one email per key, as a real provider's idempotency key or Message-ID does.
 */
public record Email(String idempotencyKey, String to, String subject, String body) {
}
