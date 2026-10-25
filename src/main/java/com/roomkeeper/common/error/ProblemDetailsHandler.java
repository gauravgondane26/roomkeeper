package com.roomkeeper.common.error;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception into an RFC 9457 problem detail with a stable {@code type}.
 * Spring's own exceptions (bad JSON, unknown route, wrong method) are handled by the
 * superclass; this class gives them a {@link ProblemType} instead of leaving the type blank.
 */
@RestControllerAdvice
public class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

	private static final URI ABOUT_BLANK = URI.create("about:blank");

	public record FieldViolation(String field, String message) {
	}

	@ExceptionHandler(ApiException.class)
	ProblemDetail handleApiException(ApiException ex) {
		return ex.toProblemDetail();
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ProblemType.INTERNAL_ERROR.problem("An unexpected error occurred");
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
			.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
			.toList();
		ProblemDetail problem = ProblemType.VALIDATION.problem("One or more fields are invalid");
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		// Spring reports unknown routes as missing static resources; say what the client asked for
		ProblemDetail problem = ProblemType.NOT_FOUND
			.problem("No endpoint " + ex.getHttpMethod() + " /" + ex.getResourcePath());
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		// The superclass builds the body for some exceptions (e.g. 405), so adjust it afterwards.
		// Spring 7 leaves the type unset (null) on its own problems; older versions used about:blank.
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail problem
				&& (problem.getType() == null || ABOUT_BLANK.equals(problem.getType()))) {
			ProblemType.forStatus(statusCode.value()).ifPresent(type -> {
				problem.setType(type.uri());
				problem.setTitle(type.title());
			});
		}
		return response;
	}

}
