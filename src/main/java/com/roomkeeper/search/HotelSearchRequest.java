package com.roomkeeper.search;

import java.time.LocalDate;

import com.roomkeeper.common.validation.NoControlCharacters;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Search query parameters. The stay is the nights from {@code checkIn} up to
 * {@code checkOut}. {@code rooms} defaults to 1; {@code page} (from 0) and {@code size}
 * default to the first 20 hotels.
 */
public record HotelSearchRequest(
		@NotBlank @Size(max = 100) @NoControlCharacters String city,
		@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
		@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
		@Min(1) @Max(10) Integer rooms,
		@Min(0) Integer page,
		@Min(1) @Max(50) Integer size) {
}
