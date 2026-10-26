package com.roomkeeper.search;

import com.roomkeeper.hotel.HotelChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops cached responses when the data behind them changes. Runs after the change commits,
 * so a request racing the change can't put the old data back into the cache afterwards.
 */
@Component
class HotelCacheEvictor {

	private static final Logger log = LoggerFactory.getLogger(HotelCacheEvictor.class);

	private final CacheManager caches;

	HotelCacheEvictor(CacheManager caches) {
		this.caches = caches;
	}

	@TransactionalEventListener
	void onHotelChanged(HotelChangedEvent event) {
		Cache hotelInfo = caches.getCache(CacheConfig.HOTEL_INFO);
		try {
			hotelInfo.evict(event.hotelId());
		}
		catch (RuntimeException ex) {
			// The change is committed; the entry still expires on its own
			log.warn("Could not evict cached info for hotel {}", event.hotelId(), ex);
		}
	}

}
