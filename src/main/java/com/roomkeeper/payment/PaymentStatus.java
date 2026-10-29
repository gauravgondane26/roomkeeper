package com.roomkeeper.payment;

/** Where one payment attempt stands. A booking has at most one Pending or Succeeded payment. */
public enum PaymentStatus {

	/** The checkout session is open; the guest hasn't paid yet. */
	PENDING,

	/** Paid; the booking is confirmed. */
	SUCCEEDED,

	/** The checkout session closed unpaid: it ran out, or was closed when the hold ran out. */
	EXPIRED,

	/** A delayed payment (a bank transfer, say) didn't go through. */
	FAILED,

	/** Paid, and the booking was cancelled: the refund that's due hasn't been made yet. */
	REFUND_PENDING,

	/** Paid, then given back, in full or a cancellation's share (the refunded amount). */
	REFUNDED

}
