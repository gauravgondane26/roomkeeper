package com.roomkeeper.search;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** A stay to search for: {@code rooms} rooms in {@code city}, for the nights from check-in up to check-out. */
public record SearchCriteria(String city, LocalDate checkIn, LocalDate checkOut, int rooms) {

	public int nights() {
		return (int) ChronoUnit.DAYS.between(checkIn, checkOut);
	}

}
