package com.roomkeeper.common.error;

import java.util.List;

import org.springframework.http.ProblemDetail;

/**
 * A request field that's invalid in a way annotations can't express, such as a date range
 * that ends before it starts. Reported exactly like an annotation failure: a validation
 * problem whose {@code errors} list names the field.
 */
public class InvalidFieldException extends ApiException {

	private final String field;

	private final String reason;

	public InvalidFieldException(String field, String reason) {
		super(ProblemType.VALIDATION, "One or more fields are invalid");
		this.field = field;
		this.reason = reason;
	}

	@Override
	public ProblemDetail toProblemDetail() {
		ProblemDetail problem = super.toProblemDetail();
		problem.setProperty("errors", List.of(new ProblemDetailsHandler.FieldViolation(field, reason)));
		return problem;
	}

}
