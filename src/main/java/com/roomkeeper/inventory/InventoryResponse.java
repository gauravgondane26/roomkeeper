package com.roomkeeper.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One night of one room type. {@code available} is total minus booked minus held. */
public record InventoryResponse(LocalDate date, int totalCount, int bookedCount, int reservedCount, int available,
		BigDecimal surgeFactor, boolean closed) {
}
