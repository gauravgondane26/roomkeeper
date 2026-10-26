package com.roomkeeper.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A version number per city, part of every cached search key for that city
 * ({@code search-version:{city hash}}). Evicting a city's cached searches is one INCR: new
 * searches use the next version, and entries under the old one are never read again and
 * expire on their own. That costs the same however much else Redis holds, unlike finding
 * and deleting the old entries by pattern.
 */
@Component
class SearchCacheVersions {

	private static final Logger log = LoggerFactory.getLogger(SearchCacheVersions.class);

	private final StringRedisTemplate redis;

	SearchCacheVersions(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** The city's current version, or null if Redis can't be reached (search then skips the cache). */
	Long current(String city) {
		try {
			String version = redis.opsForValue().get(key(city));
			return version != null ? Long.valueOf(version) : 0L;
		}
		catch (DataAccessException ex) {
			log.warn("Search cache unavailable; searching without it", ex);
			return null;
		}
	}

	/** Makes every cached search for the city stale. */
	void bump(String city) {
		try {
			redis.opsForValue().increment(key(city));
		}
		catch (DataAccessException ex) {
			log.warn("Could not evict cached searches for {}; they expire within the search TTL", city, ex);
		}
	}

	private static String key(String city) {
		return "search-version:" + SearchQuery.cityKey(city);
	}

}
