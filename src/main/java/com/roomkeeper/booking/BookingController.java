package com.roomkeeper.booking;

import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings", description = "Guests hold rooms for a stay, then add guests and pay before the hold runs out")
public class BookingController {

	private final BookingService bookingService;

	public BookingController(BookingService bookingService) {
		this.bookingService = bookingService;
	}

	@PostMapping("/init")
	public ResponseEntity<BookingResponse> init(@CurrentUserId long userId,
			@Valid @RequestBody BookingInitRequest request) {
		BookingResponse booking = bookingService.init(userId, request);
		var location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/v1/bookings/{id}")
			.buildAndExpand(booking.id())
			.toUri();
		return ResponseEntity.created(location).body(booking);
	}

	@GetMapping("/{bookingId}")
	public BookingResponse get(@CurrentUserId long userId, @PathVariable long bookingId) {
		return bookingService.get(userId, bookingId);
	}

	/** A hold that has run out reads EXPIRED, even before the expiry job releases its rooms. */
	@GetMapping("/{bookingId}/status")
	public BookingStatusResponse status(@CurrentUserId long userId, @PathVariable long bookingId) {
		return bookingService.status(userId, bookingId);
	}

	@PostMapping("/{bookingId}/guests")
	public BookingResponse putGuests(@CurrentUserId long userId, @PathVariable long bookingId,
			@Valid @RequestBody BookingGuestsRequest request) {
		return bookingService.putGuests(userId, bookingId, request);
	}

}
