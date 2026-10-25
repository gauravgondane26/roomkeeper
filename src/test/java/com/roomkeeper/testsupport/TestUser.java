package com.roomkeeper.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;

/** A user that tests can sign up with. Build one with {@link UserBuilder#aUser()}. */
public record TestUser(String email, String password, String name, String role) {

	public Map<String, Object> signupRequest() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", password);
		body.put("name", name);
		if (role != null) {
			body.put("role", role);
		}
		return body;
	}

}
