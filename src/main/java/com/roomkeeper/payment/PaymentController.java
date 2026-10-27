package com.roomkeeper.payment;

import com.roomkeeper.common.idempotency.IdempotencyKeyHeader;
import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings/{bookingId}/payments")
@Tag(name = "Payments", description = "Guests pay for a held booking on Stripe's checkout page")
public class PaymentController {

	private final PaymentService paymentService;

	public PaymentController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@PostMapping
	@IdempotencyKeyHeader
	@Operation(summary = "Start paying for a booking",
			description = "Opens a checkout session for the booking's frozen amount (201), or returns the one already "
					+ "open (200). Pay at checkoutUrl; the booking is confirmed when Stripe reports the payment.")
	public ResponseEntity<PaymentResponse> start(@CurrentUserId long userId, @PathVariable long bookingId) {
		PaymentService.StartedPayment started = paymentService.start(userId, bookingId);
		return ResponseEntity.status(started.opened() ? HttpStatus.CREATED : HttpStatus.OK).body(started.payment());
	}

}
