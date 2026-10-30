package com.roomkeeper.common.error;

import java.net.URI;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
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

	/** A database outage or a full pool usually passes within seconds. */
	private static final int RETRY_AFTER_SECONDS = 2;

	/** What HikariCP throws when code uses a connection the pool has closed. */
	private static final String HIKARI_CLOSED = "Connection is closed";

	public record FieldViolation(String field, String message) {
	}

	@ExceptionHandler(ApiException.class)
	ProblemDetail handleApiException(ApiException ex) {
		return ex.toProblemDetail();
	}

	/**
	 * Postgres can't be reached, or every pooled connection is busy for longer than the pool
	 * waits: nothing is wrong with the request, so the client is told to try again shortly.
	 * A store failing at the connection level (Redis included, where nothing else handled it)
	 * is the same to the client.
	 */
	@ExceptionHandler({ CannotCreateTransactionException.class, DataAccessResourceFailureException.class })
	ResponseEntity<ProblemDetail> handleStoreUnavailable(Exception ex) {
		log.warn("A data store is unavailable: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
			.body(ProblemType.SERVICE_UNAVAILABLE.problem("The service can't reach its data right now; try again shortly"));
	}

	/**
	 * Anything else is a bug, except a lost database connection that surfaced as some other
	 * exception: a query that hit the read timeout closes its connection, and the rollback
	 * after it then fails with "Connection is closed", hiding the timeout.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		if (isLostConnection(ex)) {
			return handleStoreUnavailable(ex);
		}
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(ProblemType.INTERNAL_ERROR.problem("An unexpected error occurred"));
	}

	/**
	 * Whether a cause is a JDBC connection failure: SQL state class 08, the driver saying it
	 * can't recover, or HikariCP's "Connection is closed" (no SQL state) for a connection it
	 * already dropped as broken.
	 */
	static boolean isLostConnection(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLTransientConnectionException || cause instanceof SQLRecoverableException) {
				return true;
			}
			if (cause instanceof SQLException sql && (HIKARI_CLOSED.equals(sql.getMessage())
					|| sql.getSQLState() != null && sql.getSQLState().startsWith("08"))) {
				return true;
			}
			for (Throwable suppressed : cause.getSuppressed()) {
				if (isLostConnection(suppressed)) {
					return true;
				}
			}
		}
		return false;
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
