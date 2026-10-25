package com.roomkeeper.common.security;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.roomkeeper.common.error.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes 401 and 403 responses from the security filters as problem details, in the same
 * shape as errors from controllers. These happen before any controller runs, so the MVC
 * exception handler never sees them.
 */
@Component
public class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final JsonMapper jsonMapper;

	public ProblemDetailsSecurityHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		write(request, response, ProblemType.UNAUTHORIZED.problem("Authentication is required"));
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		write(request, response, ProblemType.FORBIDDEN.problem("You don't have access to this resource"));
	}

	private void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
			throws IOException {
		problem.setInstance(URI.create(request.getRequestURI()));
		response.setStatus(problem.getStatus());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		jsonMapper.writeValue(response.getOutputStream(), toMap(problem));
	}

	private static Map<String, Object> toMap(ProblemDetail problem) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", problem.getType().toString());
		body.put("title", problem.getTitle());
		body.put("status", problem.getStatus());
		body.put("detail", problem.getDetail());
		body.put("instance", problem.getInstance().toString());
		if (problem.getProperties() != null) {
			body.putAll(problem.getProperties());
		}
		return body;
	}

}
