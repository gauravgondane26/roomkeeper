package com.roomkeeper.booking;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** A stay to hold: {@code rooms} rooms of one room type, the nights from {@code checkIn} up to {@code checkOut}. */
public record BookingInitRequest(
		@NotNull Long roomId,
		@NotNull LocalDate checkIn,
		@NotNull LocalDate checkOut,
		@NotNull @Min(1) @Max(10) Integer rooms) {
}
