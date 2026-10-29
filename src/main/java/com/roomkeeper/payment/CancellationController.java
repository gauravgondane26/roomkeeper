package com.roomkeeper.payment;

import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings/{bookingId}/cancel")
@Tag(name = "Bookings")
public class CancellationController {

	private final CancellationService cancellations;

	public CancellationController(CancellationService cancellations) {
		this.cancellations = cancellations;
	}

	@PostMapping
	@Operation(summary = "Cancel a paid booking",
			description = "Puts the rooms back on sale and refunds what the cancellation policy allows: everything with "
					+ "48 hours' notice before check-in (2 PM hotel time), half with less. The refund follows within "
					+ "seconds (refundStatus PENDING, then REFUNDED). Once the stay has begun it's 409 "
					+ "cancellation-closed. Cancelling again returns the same cancellation.")
	public CancellationResponse cancel(@CurrentUserId long userId, @PathVariable long bookingId) {
		return cancellations.cancel(userId, bookingId);
	}

}
