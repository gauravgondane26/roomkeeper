package com.roomkeeper.hotel;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Creates a room type, or replaces its details. {@code totalCount} is how many rooms of
 * this type the hotel has; {@code basePrice} is the price of one room for one night.
 */
public record RoomRequest(
		@NotBlank @Size(max = 100) String type,
		@NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal basePrice,
		@NotNull @Min(1) @Max(20) Integer capacity,
		@NotNull @Min(0) @Max(10_000) Integer totalCount) {
}
