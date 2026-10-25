package com.roomkeeper.common.error;

import org.springframework.http.ProblemDetail;

/**
 * An error to report to the client as a problem detail. Throw it directly, or subclass it
 * for errors worth naming (e.g. {@code EmailAlreadyRegisteredException}).
 */
public class ApiException extends RuntimeException {

	private final ProblemType type;

	public ApiException(ProblemType type, String detail) {
		super(detail);
		this.type = type;
	}

	public ProblemType type() {
		return type;
	}

	public ProblemDetail toProblemDetail() {
		return type.problem(getMessage());
	}

}
