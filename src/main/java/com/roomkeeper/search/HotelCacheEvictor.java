package com.roomkeeper.search;

import com.roomkeeper.hotel.HotelChangedEvent;
import com.roomkeeper.inventory.InventoryChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops cached responses when the data behind them changes. Runs after the change commits,
 * so a request racing the change can't put the old data back into the cache afterwards.
 * <p>
 * Cached searches are evicted per city: a change to one hotel retires every cached search
 * for its city by bumping the city's cache version, which covers every search that could
 * include it, while other cities keep their entries.
 */
@Component
class HotelCacheEvictor {

	private static final Logger log = LoggerFactory.getLogger(HotelCacheEvictor.class);

	private final CacheManager caches;

	private final SearchCacheVersions searchVersions;

	HotelCacheEvictor(CacheManager caches, SearchCacheVersions searchVersions) {
		this.caches = caches;
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
		try {
			caches.getCache(CacheConfig.HOTEL_INFO).evict(event.hotelId());
		}
		catch (RuntimeException ex) {
			// The change is committed; the entry still expires on its own
			log.warn("Could not evict cached info for hotel {}", event.hotelId(), ex);
		}
		event.cities().forEach(searchVersions::bump);
	}

}
