package com.roomkeeper.inventory;

import java.time.LocalDate;

/**
 * A hotel's rooms, prices, or availability changed on the nights from {@code from} to
 * {@code to}, both included; a null {@code to} means every later night too. Published
 * inside the transaction that made the change, so listeners can update derived data
 * (cheapest prices, caches) in step with it. {@code city} is where the hotel is, for
 * evicting cached searches.
 */
public record InventoryChangedEvent(long hotelId, String city, LocalDate from, LocalDate to) {

	/** Every night from {@code from} on. */
	public static InventoryChangedEvent fromNight(long hotelId, String city, LocalDate from) {
		return new InventoryChangedEvent(hotelId, city, from, null);
	}

}
