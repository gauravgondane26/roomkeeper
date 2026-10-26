package com.roomkeeper.search;

import com.roomkeeper.hotel.HotelChangedEvent;
import com.roomkeeper.inventory.InventoryChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops cached responses when the data behind them changes. Runs after the change commits,
 * so a request racing the change can't put the old data back into the cache afterwards.
 * <p>
 * Cached searches are evicted per city: a change to one hotel clears every cached search
 * for its city ({@code search:{city hash}:*}), which covers every search that could
 * include it, while other cities keep their entries.
 */
@Component
class HotelCacheEvictor {

	private static final Logger log = LoggerFactory.getLogger(HotelCacheEvictor.class);

	private final CacheManager caches;

	HotelCacheEvictor(CacheManager caches) {
		this.caches = caches;
	}

	@TransactionalEventListener
	void onInventoryChanged(InventoryChangedEvent event) {
		evictSearches(event.city());
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
		event.cities().forEach(this::evictSearches);
	}

	private void evictSearches(String city) {
		try {
			((RedisCache) caches.getCache(CacheConfig.SEARCH)).clear(SearchQuery.cityKey(city) + ":*");
		}
		catch (RuntimeException ex) {
			log.warn("Could not evict cached searches for {}; they expire within the search TTL", city, ex);
		}
	}

}
