package com.roomkeeper.pricing;

import java.math.BigDecimal;
import java.util.List;

/**
 * The price of a stay: each night's price for one room, their sum ({@code perRoomTotal}),
 * and the total for {@code rooms} rooms.
 */
public record PriceQuote(List<NightPrice> nights, BigDecimal perRoomTotal, int rooms, BigDecimal total) {

	public PriceQuote {
		nights = List.copyOf(nights);
	}

}
