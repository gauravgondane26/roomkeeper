package com.roomkeeper.inventory;

import java.time.LocalDate;

/**
 * A hotel's rooms, prices, or availability changed on the nights from {@code from} to
 * {@code to}, both included; a null {@code to} means every later night too. Published
 * inside the transaction that made the change, so listeners can update derived data
 * (cheapest prices, caches) in step with it. {@code city} is where the hotel is, for
 * evicting cached searches.
 * <p>
 * A {@code sale} is a booking holding or releasing rooms. Sales are frequent and contend
 * on the same nights, so their cheapest prices are recomputed after commit, off the
 * request thread, rather than while the booking holds its inventory locks; and a sale only
 * evicts cached searches ({@code evictSearches}) when a night sold out or reopened. Every
 * other change (managers, the inventory roll) is recomputed in its own transaction and
 * evicts.
 */
public record InventoryChangedEvent(long hotelId, String city, LocalDate from, LocalDate to, boolean sale,
		boolean evictSearches) {

	/** A change to what's on sale, over the nights from {@code from} to {@code to}. */
	public InventoryChangedEvent(long hotelId, String city, LocalDate from, LocalDate to) {
		this(hotelId, city, from, to, false, true);
	}

	/** Every night from {@code from} on. */
	public static InventoryChangedEvent fromNight(long hotelId, String city, LocalDate from) {
		return new InventoryChangedEvent(hotelId, city, from, null);
	}

	/** A booking held or released rooms; {@code soldOutOrReopened} if that emptied a night or freed one up. */
	public static InventoryChangedEvent sale(long hotelId, String city, LocalDate from, LocalDate to,
			boolean soldOutOrReopened) {
		return new InventoryChangedEvent(hotelId, city, from, to, true, soldOutOrReopened);
	}

}
