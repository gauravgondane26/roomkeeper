package com.roomkeeper.payment;

/** Where one payment attempt stands. A booking has at most one Pending or Succeeded payment. */
public enum PaymentStatus {

	/** The checkout session is open; the guest hasn't paid yet. */
	PENDING,

	/** Paid; the booking is confirmed. */
	SUCCEEDED,

	/** The checkout session expired unpaid. */
	EXPIRED,

	/** The payment failed. */
	FAILED,

	/** Paid, then refunded in full. */
	REFUNDED

}
