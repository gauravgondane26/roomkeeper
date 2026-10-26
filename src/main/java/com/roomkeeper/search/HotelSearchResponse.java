package com.roomkeeper.search;

import java.util.List;

/** One page of search results, cheapest first. */
public record HotelSearchResponse(List<HotelMatch> hotels, int page, int size, long totalHotels, int totalPages) {
}
