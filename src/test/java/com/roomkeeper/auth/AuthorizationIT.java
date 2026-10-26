package com.roomkeeper.auth;

import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@Import(AuthorizationIT.ProbeController.class)
class AuthorizationIT extends AbstractIntegrationTest {

	@Test
	void requestWithoutATokenIsUnauthorizedWithABearerChallenge() {
		api()
			.when().get("/api/v1/probe/me")
			.then()
				.statusCode(401)
				.header("WWW-Authenticate", startsWith("Bearer"))
				.body("type", equalTo("urn:roomkeeper:problem:unauthorized"));
	}

	@Test
	void tamperedTokenIsUnauthorized() {
		String token = signUpAndLogIn(aUser().build());
		String tampered = token.substring(0, token.length() - 4) + "AAAA";

		api(tampered)
			.when().get("/api/v1/probe/me")
			.then()
				.statusCode(401)
				.body("type", equalTo("urn:roomkeeper:problem:unauthorized"));
	}

	@Test
	void validTokenIdentifiesTheUserAndRole() {
		var user = aUser().build();
		long id = signUp(user);
		String token = logIn(user);

		api(token)
			.when().get("/api/v1/probe/me")
			.then()
				.statusCode(200)
				.body("userId", equalTo(String.valueOf(id)))
				.body("roles", contains("GUEST"));
	}

	@Test
	void guestOnAnAdminRouteIsForbidden() {
		String token = signUpAndLogIn(aUser().build());

		api(token)
			.when().get("/api/v1/admin/hotels")
			.then()
				.statusCode(403)
				.body("type", equalTo("urn:roomkeeper:problem:forbidden"));
	}

	@Test
	void hotelManagerOnAnAdminRouteIsAllowed() {
		String token = signUpAndLogIn(aUser().asHotelManager().build());

		api(token)
			.when().get("/api/v1/admin/hotels")
			.then()
				.statusCode(200);
	}

	@Test
	void authenticatedRequestToAnUnknownRouteIsNotFound() {
		String token = signUpAndLogIn(aUser().build());

		api(token)
			.when().get("/api/v1/does-not-exist")
			.then()
				.statusCode(404)
				.body("type", equalTo("urn:roomkeeper:problem:not-found"));
	}

	/** Echoes what the token says about the caller. */
	@RestController
	static class ProbeController {

		@GetMapping("/api/v1/probe/me")
		Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
			return Map.of("userId", jwt.getSubject(), "roles", jwt.getClaimAsStringList("roles"));
		}

	}

}
