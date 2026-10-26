package com.roomkeeper.hotel;

/**
 * A hotel's details or room types changed (or it was deleted). Published inside the
 * transaction that made the change; caches of the hotel's public view listen for it.
 */
public record HotelChangedEvent(long hotelId) {
}
