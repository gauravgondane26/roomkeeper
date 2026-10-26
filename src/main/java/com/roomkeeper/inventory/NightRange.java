package com.roomkeeper.inventory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.roomkeeper.common.error.InvalidFieldException;

/** Nights from {@code from} to {@code to}, both included. At most a year long. */
record NightRange(LocalDate from, LocalDate to) {

	static NightRange of(LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			throw new InvalidFieldException("to", "must be on or after from");
		}
		if (ChronoUnit.DAYS.between(from, to) >= InventoryService.NIGHTS_AHEAD) {
			throw new InvalidFieldException("to", "must be less than " + InventoryService.NIGHTS_AHEAD
					+ " nights after from");
		}
		return new NightRange(from, to);
	}

}
