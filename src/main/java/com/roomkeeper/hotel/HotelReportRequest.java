package com.roomkeeper.hotel;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

/** The nights to report on, {@code from} to {@code to}, both included. */
public record HotelReportRequest(
		@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
}
