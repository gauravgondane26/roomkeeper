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

	/** Paid, then refunded in full: the booking couldn't take it. */
	REFUNDED

}
