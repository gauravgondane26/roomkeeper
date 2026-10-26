package com.roomkeeper.booking;

import java.util.List;

public record BookingPageResponse(List<BookingResponse> bookings, int page, int size, long totalBookings,
		int totalPages) {
}
