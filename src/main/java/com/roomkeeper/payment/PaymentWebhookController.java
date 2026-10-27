package com.roomkeeper.payment;

import java.nio.charset.StandardCharsets;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Webhooks", description = "Stripe reports payments here")
@SecurityRequirements // Stripe signs each delivery instead of sending a bearer token
public class PaymentWebhookController {

	private final StripeWebhookVerifier verifier;

	private final PaymentWebhookService webhooks;

	public PaymentWebhookController(StripeWebhookVerifier verifier, PaymentWebhookService webhooks) {
		this.verifier = verifier;
		this.webhooks = webhooks;
	}

	/** The raw bytes, since the signature covers the payload exactly as sent. */
	@PostMapping("/webhook/payment")
	@Operation(summary = "Receive a Stripe event",
			description = "Called by Stripe, not clients. 200 once applied (or already applied before); "
					+ "400 if the Stripe-Signature doesn't verify.")
	public ResponseEntity<Void> receive(@RequestBody byte[] payload,
			@RequestHeader(name = "Stripe-Signature", required = false) String signature) {
		webhooks.handle(verifier.verify(new String(payload, StandardCharsets.UTF_8), signature));
		return ResponseEntity.ok().build();
	}

}
