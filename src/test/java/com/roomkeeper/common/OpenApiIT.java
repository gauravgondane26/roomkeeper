package com.roomkeeper.common;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

/** Swagger UI is the API's client, so its spec and page must be reachable without a login. */
class OpenApiIT extends AbstractIntegrationTest {

	@Test
	void specDescribesTheApiAndBearerAuth() {
		api()
			.when().get("/v3/api-docs")
			.then()
				.statusCode(200)
				.body("info.title", equalTo("RoomKeeper API"))
				.body("paths", hasKey("/api/v1/auth/login"))
				.body("paths", hasKey("/api/v1/users/profile"))
				.body("components.securitySchemes.bearerAuth.type", equalTo("http"))
				.body("components.securitySchemes.bearerAuth.scheme", equalTo("bearer"))
				// Protected by default; the auth endpoints are marked as open
				.body("security[0]", hasKey("bearerAuth"))
				.body("paths.'/api/v1/users/profile'.get.security", equalTo(null))
				// The caller's id comes from the token, not a request parameter
				.body("paths.'/api/v1/users/profile'.get.parameters", equalTo(null))
				.body("paths.'/api/v1/auth/login'.post.security", empty());
	}

	@Test
	void specSaysWhichRequestsNeedAnIdempotencyKey() {
		api()
			.when().get("/v3/api-docs")
			.then()
				.statusCode(200)
				.body("paths.'/api/v1/bookings/init'.post.parameters.find { it.name == 'Idempotency-Key' }.in",
						equalTo("header"))
				.body("paths.'/api/v1/bookings/init'.post.parameters.find { it.name == 'Idempotency-Key' }.required",
						equalTo(true))
				.body("paths.'/api/v1/bookings/{bookingId}/payments'.post.parameters.find { it.name == 'Idempotency-Key' }"
						+ ".required", equalTo(true));
	}

	@Test
	void swaggerUiLoadsWithoutLoggingIn() {
		api().accept("text/html")
			.when().get("/swagger-ui/index.html")
			.then()
				.statusCode(200)
				.body(containsString("Swagger UI"));
	}

	@Test
	void docsRedirectIsPublic() {
		api().redirects().follow(false)
			.when().get("/swagger-ui.html")
			.then()
				.statusCode(302)
				.header("Location", notNullValue());
	}

}
