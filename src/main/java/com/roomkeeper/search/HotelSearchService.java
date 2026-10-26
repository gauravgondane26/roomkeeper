package com.roomkeeper.search;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.roomkeeper.common.error.InvalidFieldException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HotelSearchService {

	static final int MAX_NIGHTS = 30;

	static final int DEFAULT_PAGE_SIZE = 20;

	private final HotelSearchRepository repository;

	private final Clock clock;

	public HotelSearchService(HotelSearchRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public HotelSearchResponse search(HotelSearchRequest request) {
		long nights = ChronoUnit.DAYS.between(request.checkIn(), request.checkOut());
		if (nights < 1) {
			throw new InvalidFieldException("checkOut", "must be after checkIn");
		}
		if (nights > MAX_NIGHTS) {
			throw new InvalidFieldException("checkOut", "must be at most " + MAX_NIGHTS + " nights after checkIn");
		}
		var criteria = new SearchCriteria(request.city().strip(), request.checkIn(), request.checkOut(),
				request.rooms() != null ? request.rooms() : 1);
		int page = request.page() != null ? request.page() : 0;
		int size = request.size() != null ? request.size() : DEFAULT_PAGE_SIZE;

		Instant now = clock.instant();
		long total = repository.count(criteria, now);
		List<HotelMatch> hotels = total > (long) page * size
				? repository.search(criteria, now, size, (long) page * size)
				: List.of();
		return new HotelSearchResponse(hotels, page, size, total, (int) ((total + size - 1) / size));
	}

}
