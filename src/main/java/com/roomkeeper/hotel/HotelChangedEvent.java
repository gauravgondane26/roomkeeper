package com.roomkeeper.hotel;

import java.util.Set;

/**
 * A hotel's details or room types changed (or it was deleted). Published inside the
 * transaction that made the change; caches of the hotel's public view listen for it.
 * {@code cities} is where the hotel is, and was if the change moved it.
 */
public record HotelChangedEvent(long hotelId, Set<String> cities) {

	public HotelChangedEvent {
		cities = Set.copyOf(cities);
	}

	public static HotelChangedEvent of(Hotel hotel) {
		return new HotelChangedEvent(hotel.getId(), Set.of(hotel.getCity()));
	}

}
