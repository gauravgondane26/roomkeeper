package com.roomkeeper.common.error;

import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Every kind of error the API returns. Clients and tests match on {@link #uri()} (the
 * problem's {@code type}), which never changes, rather than on human-readable text.
 * <p>
 * General types come first, one per status; specific types follow. {@link #forStatus}
 * relies on that order.
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

	// Specific
	VALIDATION("validation", HttpStatus.BAD_REQUEST, "Validation failed"),
	EMAIL_ALREADY_REGISTERED("email-already-registered", HttpStatus.CONFLICT, "Email already registered"),
	INVALID_CREDENTIALS("invalid-credentials", HttpStatus.UNAUTHORIZED, "Invalid credentials");

	private static final String URN_PREFIX = "urn:roomkeeper:problem:";

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

	public HttpStatus status() {
		return status;
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

	/** The general type for a status, used for errors raised by the framework itself. */
	static Optional<ProblemType> forStatus(int status) {
		return Arrays.stream(values())
			.filter(type -> type.status.value() == status)
			.findFirst();
	}

}
