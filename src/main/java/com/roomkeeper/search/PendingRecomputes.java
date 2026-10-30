package com.roomkeeper.search;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.roomkeeper.inventory.InventoryChangedEvent;

/**
 * Sales whose hotels' cheapest prices are still to be recomputed: at most one recompute
 * waits per hotel. A recompute reads inventory as it is when it runs, so one taken after a
 * burst of sales covers them all; queueing one per sale would only repeat the same work.
 */
final class PendingRecomputes {

	/** A hotel's nights to recompute ({@code to} null for no end), and whether to evict its city's searches. */
	record Recompute(long hotelId, String city, LocalDate from, LocalDate to, boolean evictSearches) {

		static Recompute of(InventoryChangedEvent sale) {
			return new Recompute(sale.hotelId(), sale.city(), sale.from(), sale.to(), sale.evictSearches());
		}

		/** Covers this one's nights and the sale's (and any between), and evicts if either would. */
		Recompute merge(InventoryChangedEvent sale) {
			LocalDate earliest = sale.from().isBefore(from) ? sale.from() : from;
			LocalDate latest = to == null || sale.to() == null ? null : sale.to().isAfter(to) ? sale.to() : to;
			return new Recompute(hotelId, city, earliest, latest, evictSearches || sale.evictSearches());
		}

	}

	private final ConcurrentMap<Long, Recompute> waiting = new ConcurrentHashMap<>();

	/**
	 * Adds the sale to its hotel's waiting recompute; true if none was waiting, so one must be
	 * scheduled.
	 */
	boolean add(InventoryChangedEvent sale) {
		boolean[] first = { false };
		waiting.compute(sale.hotelId(), (hotelId, recompute) -> {
			if (recompute == null) {
				first[0] = true;
				return Recompute.of(sale);
			}
			return recompute.merge(sale);
		});
		return first[0];
	}

	/**
	 * Takes the hotel's waiting recompute, or null if none is waiting. Taken just before it
	 * runs, so a sale committed after that schedules a new one, which reads its change.
	 */
	Recompute take(long hotelId) {
		return waiting.remove(hotelId);
	}

}
