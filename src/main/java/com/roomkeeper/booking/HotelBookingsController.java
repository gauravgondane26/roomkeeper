package com.roomkeeper.booking;

import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/hotels/{hotelId}/bookings")
@Tag(name = "Hotel admin")
public class HotelBookingsController {

	private final BookingService bookingService;

	public HotelBookingsController(BookingService bookingService) {
		this.bookingService = bookingService;
	}

	@GetMapping
	@Operation(summary = "List a hotel's bookings",
			description = "Every booking at the hotel, newest first, a page at a time. Only its manager sees them.")
	public BookingPageResponse list(@CurrentUserId long ownerId, @PathVariable long hotelId,
			@Valid @ParameterObject BookingPageRequest request) {
		return bookingService.listForHotel(ownerId, hotelId, request);
	}

}
