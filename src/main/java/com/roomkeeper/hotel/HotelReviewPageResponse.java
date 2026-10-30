package com.roomkeeper.hotel;

import java.util.List;

public record HotelReviewPageResponse(List<HotelReviewResponse> hotels, int page, int size, long totalHotels,
		int totalPages) {
}
