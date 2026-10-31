package com.roomkeeper.search;

import java.util.Locale;

import com.roomkeeper.common.Stays;
import org.springframework.stereotype.Service;

@Service
public class HotelSearchService {

	static final int DEFAULT_PAGE_SIZE = 20;

	private final HotelSearchResults results;

	private final SearchCacheVersions cacheVersions;

	public HotelSearchService(HotelSearchResults results, SearchCacheVersions cacheVersions) {
		this.results = results;
		this.cacheVersions = cacheVersions;
	}

	/** Validates the request, fills in defaults, and searches (through the cache). */
	public HotelSearchResponse search(HotelSearchRequest request) {
		Stays.nights(request.checkIn(), request.checkOut());
		var criteria = new SearchCriteria(request.city().strip().toLowerCase(Locale.ROOT), request.checkIn(),
				request.checkOut(), request.rooms() != null ? request.rooms() : 1);
		return results.find(new SearchQuery(criteria, request.page() != null ? request.page() : 0,
				request.size() != null ? request.size() : DEFAULT_PAGE_SIZE, cacheVersions.current(criteria.city())));
	}

}
