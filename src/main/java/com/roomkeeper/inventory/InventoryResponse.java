package com.roomkeeper.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One night of one room type. {@code available} is total minus booked minus held;
 * {@code totalOverridden} means the total was set by hand and no longer follows the room
 * type's total.
 */
public record InventoryResponse(LocalDate date, int totalCount, boolean totalOverridden, int bookedCount,
		int reservedCount, int available, BigDecimal surgeFactor, boolean closed) {
}
