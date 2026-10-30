package com.roomkeeper.search;

import com.roomkeeper.hotel.HotelChangedEvent;
import com.roomkeeper.inventory.InventoryChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops cached responses when the data behind them changes. Runs after the change commits,
 * so a request racing the change can't put the old data back into the cache afterwards.
 * <p>
 * Both caches are evicted by bumping a version that's part of their keys, never by
 * deleting entries: a hotel's info by its own version ({@link HotelInfoVersions}), and
 * searches per city, a change to one hotel retiring every cached search for its city,
 * which covers every search that could include it, while other cities keep their entries.
 */
@Component
class HotelCacheEvictor {

	private final HotelInfoVersions hotelVersions;

	private final SearchCacheVersions searchVersions;

	HotelCacheEvictor(HotelInfoVersions hotelVersions, SearchCacheVersions searchVersions) {
		this.hotelVersions = hotelVersions;
		this.searchVersions = searchVersions;
	}

	/** Sales are left to HotelMinPriceUpdater, which evicts once it has recomputed their prices. */
	@TransactionalEventListener(condition = "!#event.sale()")
	void onInventoryChanged(InventoryChangedEvent event) {
		searchVersions.bump(event.city());
	}

	/** Search results show the hotel's name and city, so they go too. */
	@TransactionalEventListener
	void onHotelChanged(HotelChangedEvent event) {
		hotelVersions.bump(event.hotelId());
		event.cities().forEach(searchVersions::bump);
	}

}
