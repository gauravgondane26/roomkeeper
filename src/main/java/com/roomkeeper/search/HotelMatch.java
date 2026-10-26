package com.roomkeeper.search;

import java.math.BigDecimal;
import java.util.List;

/**
 * A hotel with a room type free for the whole stay. {@code fromPricePerNight} is the
 * cheapest nightly price of any bookable room during the stay, a "from" figure for the
 * listing; null if no price is recorded yet.
 */
public record HotelMatch(long id, String name, String city, List<String> amenities, BigDecimal fromPricePerNight) {
}
