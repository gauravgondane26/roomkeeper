package com.roomkeeper.hotel;

import java.util.Set;

/**
 * A hotel's details or room types changed (or it was deleted). Published inside the
 * transaction that made the change; caches of the hotel's public view listen for it.
 * {@code cities} are the cities whose cached searches show this hotel's details: where
 * it is, and was if the change moved it. Empty when only its room types changed, which
 * search results don't show.
 */
public record HotelChangedEvent(long hotelId, Set<String> cities) {

	public HotelChangedEvent {
		cities = Set.copyOf(cities);
	}

	public static HotelChangedEvent of(Hotel hotel) {
		return new HotelChangedEvent(hotel.getId(), Set.of(hotel.getCity()));
	}

	/** Only the hotel's room types changed: its info, not its search listing. */
	public static HotelChangedEvent roomTypesOf(Hotel hotel) {
		return new HotelChangedEvent(hotel.getId(), Set.of());
	}

}
