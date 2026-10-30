package com.roomkeeper.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Changes every night from {@code from} to {@code to} (both included). Null fields are left
 * unchanged, but at least one of {@code closed}, {@code surgeFactor}, {@code totalCount} and
 * {@code followRoomType} must be set. {@code totalCount} sets the nights' total by hand, so
 * later changes to the room type's total pass them by; {@code followRoomType: true} undoes
 * that, giving them the room type's total again. Only one of the two can be set.
 */
public record InventoryUpdateRequest(
		@NotNull LocalDate from,
		@NotNull LocalDate to,
		Boolean closed,
		@DecimalMin("0.01") @DecimalMax("10.00") @Digits(integer = 2, fraction = 2) BigDecimal surgeFactor,
		@Min(0) @Max(10_000) Integer totalCount,
		@AssertTrue(message = "can only be true; leave it out to keep the nights as they are") Boolean followRoomType) {

	boolean changesNothing() {
		return closed == null && surgeFactor == null && totalCount == null && followRoomType == null;
	}

	boolean followsRoomType() {
		return Boolean.TRUE.equals(followRoomType);
	}

}
