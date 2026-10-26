package com.roomkeeper.hotel;

import java.util.List;

public record HotelResponse(Long id, String name, String city, String timeZone, List<String> amenities,
		boolean active) {
}
