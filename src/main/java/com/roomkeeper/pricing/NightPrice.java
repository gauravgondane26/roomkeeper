package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The price of one room for one night. */
public record NightPrice(LocalDate date, BigDecimal price) {
}
