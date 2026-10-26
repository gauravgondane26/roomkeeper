package com.roomkeeper.booking;

import java.time.LocalDate;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;

/** Some night of the stay doesn't have the rooms asked for: closed, booked up, or not on sale yet. */
public class SoldOutException extends ApiException {

	public SoldOutException(long roomId, int rooms, LocalDate checkIn, LocalDate checkOut) {
		super(ProblemType.SOLD_OUT, "Room type " + roomId + " has fewer than " + rooms
				+ (rooms == 1 ? " room" : " rooms") + " free on some night of the stay from " + checkIn + " to "
				+ checkOut);
	}

}
