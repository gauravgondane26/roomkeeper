package com.roomkeeper.search;

import java.time.temporal.ChronoUnit;
import java.util.Locale;

import com.roomkeeper.common.error.InvalidFieldException;
import org.springframework.stereotype.Service;

@Service
public class HotelSearchService {

	static final int MAX_NIGHTS = 30;

	static final int DEFAULT_PAGE_SIZE = 20;

	private final HotelSearchResults results;

	private final SearchCacheVersions cacheVersions;

	public HotelSearchService(HotelSearchResults results, SearchCacheVersions cacheVersions) {
		this.results = results;
		this.cacheVersions = cacheVersions;
	}

	/** Validates the request, fills in defaults, and searches (through the cache). */
	public HotelSearchResponse search(HotelSearchRequest request) {
		long nights = ChronoUnit.DAYS.between(request.checkIn(), request.checkOut());
		if (nights < 1) {
			throw new InvalidFieldException("checkOut", "must be after checkIn");
		}
		if (nights > MAX_NIGHTS) {
			throw new InvalidFieldException("checkOut", "must be at most " + MAX_NIGHTS + " nights after checkIn");
		}
		var criteria = new SearchCriteria(request.city().strip().toLowerCase(Locale.ROOT), request.checkIn(),
				request.checkOut(), request.rooms() != null ? request.rooms() : 1);
		return results.find(new SearchQuery(criteria, request.page() != null ? request.page() : 0,
				request.size() != null ? request.size() : DEFAULT_PAGE_SIZE, cacheVersions.current(criteria.city())));
	}

}
