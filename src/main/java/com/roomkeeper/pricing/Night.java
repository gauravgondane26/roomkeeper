package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything the pricing rules need to know about one room type on one night.
 * {@code soldCount} is rooms booked or held.
 */
public record Night(LocalDate date, BigDecimal basePrice, BigDecimal surgeFactor, int totalCount, int soldCount) {
}
