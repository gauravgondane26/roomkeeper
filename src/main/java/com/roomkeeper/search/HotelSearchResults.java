package com.roomkeeper.search;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Runs a search against Postgres, through the search cache. */
@Component
class HotelSearchResults {

	private final HotelSearchRepository repository;

	private final Clock clock;

	HotelSearchResults(HotelSearchRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	// No version means Redis is unreachable: search without the cache
	@Cacheable(cacheNames = CacheConfig.SEARCH, key = "#query.cacheKey()",
			condition = "#query.cacheVersion() != null")
	@Transactional(readOnly = true)
	public HotelSearchResponse find(SearchQuery query) {
		Instant now = clock.instant();
		long offset = (long) query.page() * query.size();
		long total = repository.count(query.criteria(), now);
		List<HotelMatch> hotels = total > offset
				? repository.search(query.criteria(), now, query.size(), offset)
				: List.of();
		return new HotelSearchResponse(hotels, query.page(), query.size(), total,
				(int) ((total + query.size() - 1) / query.size()));
	}

}
