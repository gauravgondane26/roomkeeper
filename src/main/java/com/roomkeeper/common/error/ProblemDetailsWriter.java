package com.roomkeeper.common.error;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a problem detail straight to the servlet response, for errors raised in filters
 * (security, rate limiting) before any controller runs, where the MVC exception handler
 * can't help. Produces the same JSON shape as {@link ProblemDetailsHandler}.
 */
@Component
public class ProblemDetailsWriter {

	private final JsonMapper jsonMapper;

	public ProblemDetailsWriter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
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
