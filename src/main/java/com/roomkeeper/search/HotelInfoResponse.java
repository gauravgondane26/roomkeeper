package com.roomkeeper.search;

import java.math.BigDecimal;
import java.util.List;

/** What guests see about a hotel: its details and room types, without availability or owner. */
public record HotelInfoResponse(Long id, String name, String city, String timeZone, List<String> amenities,
		List<RoomInfo> rooms) {

	public record RoomInfo(Long id, String type, int capacity, BigDecimal basePrice) {
	}

}
