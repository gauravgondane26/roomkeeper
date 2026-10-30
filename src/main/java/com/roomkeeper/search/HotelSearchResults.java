package com.roomkeeper.search;

import java.time.Clock;

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
		long offset = (long) query.page() * query.size();
		HotelSearchRepository.Page found = repository.search(query.criteria(), clock.instant(), query.size(), offset);
		long total = found.total();
		return new HotelSearchResponse(found.hotels(), query.page(), query.size(), total,
				(int) ((total + query.size() - 1) / query.size()));
	}

}
