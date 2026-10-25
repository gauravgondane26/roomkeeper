package com.roomkeeper.common.security;

import java.io.IOException;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import com.roomkeeper.common.error.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Writes 401 and 403 responses from the security filters as problem details, in the same
 * shape as errors from controllers. These happen before any controller runs, so the MVC
 * exception handler never sees them.
 */
@Component
public class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ProblemDetailsWriter writer;

	public ProblemDetailsSecurityHandler(ProblemDetailsWriter writer) {
		this.writer = writer;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		// Missing, malformed, tampered, and expired tokens all land here (RFC 6750 challenge)
		response.setHeader("WWW-Authenticate", "Bearer");
		writer.write(request, response, ProblemType.UNAUTHORIZED.problem("A valid access token is required"));
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		writer.write(request, response, ProblemType.FORBIDDEN.problem("You don't have access to this resource"));
	}

}
