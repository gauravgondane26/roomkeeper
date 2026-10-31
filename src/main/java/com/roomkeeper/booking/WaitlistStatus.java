package com.roomkeeper.booking;

/** Where a place in line stands. */
public enum WaitlistStatus {

	/** In line for the stay. */
	WAITING,

	/** Rooms freed up and were held for the guest; the hold is a normal booking from there. */
	OFFERED,

	/** The guest gave up their place. */
	LEFT,

	/** The stay began before rooms freed up. */
	EXPIRED

}
