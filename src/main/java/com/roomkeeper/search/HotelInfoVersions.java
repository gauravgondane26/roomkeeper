package com.roomkeeper.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A version number per hotel, part of its cached info's key ({@code hotel-version:{id}}).
 * A read takes the version before it loads the hotel, and a change bumps it after it
 * commits. A read that loaded the old details just before a change therefore caches them
 * under the old version, which nothing reads again, however late its cache write lands.
 * Deleting the entry instead would leave that late write in place for the whole TTL.
 */
@Component
class HotelInfoVersions {

	private static final Logger log = LoggerFactory.getLogger(HotelInfoVersions.class);

	private final StringRedisTemplate redis;

	HotelInfoVersions(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** The hotel's current version, or null if Redis can't be reached (the read then skips the cache). */
	Long current(long hotelId) {
		try {
			String version = redis.opsForValue().get(key(hotelId));
			return version != null ? Long.valueOf(version) : 0L;
		}
		catch (DataAccessException ex) {
			log.warn("Hotel info cache unavailable; reading without it", ex);
			return null;
		}
	}

	/** Makes the hotel's cached info stale. */
	void bump(long hotelId) {
		try {
			redis.opsForValue().increment(key(hotelId));
		}
		catch (DataAccessException ex) {
			log.warn("Could not evict cached info for hotel {}; it expires within the hotel info TTL", hotelId, ex);
		}
	}

	private static String key(long hotelId) {
		return "hotel-version:" + hotelId;
	}

}
