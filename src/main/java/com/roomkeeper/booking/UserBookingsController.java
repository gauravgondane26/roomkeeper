package com.roomkeeper.booking;

import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/bookings")
@Tag(name = "Bookings", description = "Guests hold rooms for a stay, then add guests and pay before the hold runs out")
public class UserBookingsController {

	private final BookingService bookingService;

	public UserBookingsController(BookingService bookingService) {
		this.bookingService = bookingService;
	}

	/** The signed-in user's bookings, newest first. */
	@GetMapping
	public BookingPageResponse list(@CurrentUserId long userId, @Valid @ParameterObject BookingPageRequest request) {
		return bookingService.list(userId, request);
	}

}
