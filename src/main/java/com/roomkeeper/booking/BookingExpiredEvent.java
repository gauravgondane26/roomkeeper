package com.roomkeeper.booking;

/**
 * A hold ran out unpaid and its rooms went back on sale. Published inside the transaction
 * that released them. {@code paymentStarted} if the guest had opened a checkout session,
 * which may still be open on Stripe's side.
 */
public record BookingExpiredEvent(long bookingId, boolean paymentStarted) {
}
