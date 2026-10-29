package com.roomkeeper.common.error;

import java.net.URI;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Every kind of error the API returns. Clients and tests match on {@link #uri()} (the
 * problem's {@code type}), which never changes, rather than on human-readable text.
 * <p>
 * General types come first, one per status, from {@code BAD_REQUEST} to
 * {@code SERVICE_UNAVAILABLE}; specific types follow. {@link #forStatus} relies on that order.
 */
public enum ProblemType {

	// General: one per status, used for errors raised by the framework
	BAD_REQUEST("bad-request", HttpStatus.BAD_REQUEST, "Bad request"),
	UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED, "Unauthorized"),
	FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "Forbidden"),
	NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Not found"),
	METHOD_NOT_ALLOWED("method-not-allowed", HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
	CONFLICT("conflict", HttpStatus.CONFLICT, "Conflict"),
	UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
	RATE_LIMITED("rate-limited", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
	INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
	SERVICE_UNAVAILABLE("service-unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),

	// Specific
	VALIDATION("validation", HttpStatus.BAD_REQUEST, "Validation failed"),
	INVALID_CREDENTIALS("invalid-credentials", HttpStatus.UNAUTHORIZED, "Invalid credentials"),
	INVALID_REFRESH_TOKEN("invalid-refresh-token", HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
	ROOM_TYPE_EXISTS("room-type-exists", HttpStatus.CONFLICT, "Room type already exists"),
	TOTAL_BELOW_SOLD("total-below-sold", HttpStatus.CONFLICT, "Total below rooms sold"),
	HOTEL_NOT_APPROVED("hotel-not-approved", HttpStatus.CONFLICT, "Hotel not approved"),
	SOLD_OUT("sold-out", HttpStatus.CONFLICT, "Sold out"),
	HOLD_EXPIRED("hold-expired", HttpStatus.CONFLICT, "Hold expired"),
	BOOKING_STATE("booking-state", HttpStatus.CONFLICT, "Not allowed in the booking's status"),
	CANCELLATION_CLOSED("cancellation-closed", HttpStatus.CONFLICT, "Cancellation closed"),
	HAS_BOOKINGS("has-bookings", HttpStatus.CONFLICT, "Has bookings"),
	IDEMPOTENCY_KEY_REQUIRED("idempotency-key-required", HttpStatus.BAD_REQUEST, "Idempotency key required"),
	IDEMPOTENCY_KEY_IN_USE("idempotency-key-in-use", HttpStatus.CONFLICT, "Idempotency key in use"),
	IDEMPOTENCY_KEY_REUSED("idempotency-key-reused", HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused"),
	PAYMENTS_UNAVAILABLE("payments-unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Payments unavailable"),
	INVALID_WEBHOOK_SIGNATURE("invalid-webhook-signature", HttpStatus.BAD_REQUEST, "Invalid webhook signature");

	private static final String URN_PREFIX = "urn:roomkeeper:problem:";

	private static final Set<ProblemType> GENERAL = EnumSet.range(BAD_REQUEST, SERVICE_UNAVAILABLE);

	private final URI uri;

	private final HttpStatus status;

	private final String title;

	ProblemType(String slug, HttpStatus status, String title) {
		this.uri = URI.create(URN_PREFIX + slug);
		this.status = status;
		this.title = title;
	}

	public URI uri() {
		return uri;
	}

	public String title() {
		return title;
	}

	public ProblemDetail problem(String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(uri);
		problem.setTitle(title);
		return problem;
	}

	/**
	 * The general type for a status, used for errors raised by the framework itself. Never a
	 * specific type, even for a status only specific types have.
	 */
	static Optional<ProblemType> forStatus(int status) {
		return GENERAL.stream()
			.filter(type -> type.status.value() == status)
			.findFirst();
	}

}
