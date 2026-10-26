package com.roomkeeper.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Changes every night from {@code from} to {@code to} (both included). Null fields are left
 * unchanged, but at least one of {@code closed}, {@code surgeFactor}, and {@code totalCount}
 * must be set.
 */
public record InventoryUpdateRequest(
		@NotNull LocalDate from,
		@NotNull LocalDate to,
		Boolean closed,
		@DecimalMin("0.01") @DecimalMax("10.00") @Digits(integer = 2, fraction = 2) BigDecimal surgeFactor,
		@Min(0) @Max(10_000) Integer totalCount) {

	boolean changesNothing() {
		return closed == null && surgeFactor == null && totalCount == null;
	}

}
