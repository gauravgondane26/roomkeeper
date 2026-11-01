package com.roomkeeper.common.security;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Every response says what a browser may do with it: the API's JSON may load nothing and be
 * framed nowhere, Stripe's return pages may use their own inline styles but no scripts,
 * and Swagger UI may load its own scripts and styles. No response sends a referrer on.
 */
class SecurityHeadersIT extends AbstractIntegrationTest {

	@Test
	void apiResponsesMayLoadNothing() {
		api().when().get("/api/v1/hotels/search?city=Nowhere&checkIn=2026-12-01&checkOut=2026-12-02")
			.then().statusCode(200)
			.header("Content-Security-Policy", equalTo("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
			.header("Referrer-Policy", equalTo("no-referrer"))
			.header("Permissions-Policy", containsString("camera=()"))
			.header("X-Content-Type-Options", equalTo("nosniff"));
	}

	@Test
	void errorsCarryThemToo() {
		api().when().get("/api/v1/hotels/999999999/info")
			.then().statusCode(404)
			.header("Content-Security-Policy", containsString("default-src 'none'"))
			.header("Referrer-Policy", equalTo("no-referrer"));
	}

	@Test
	void stripesReturnPagesMayStyleThemselvesButRunNoScripts() {
		api().accept("text/html").when().get("/payments/complete.html")
			.then().statusCode(200)
			.header("Content-Security-Policy", containsString("default-src 'none'"))
			.header("Content-Security-Policy", containsString("style-src 'unsafe-inline'"))
			.header("Content-Security-Policy", not(containsString("script-src")));
	}

	@Test
	void swaggerUiMayLoadItsOwnScriptsAndStyles() {
		api().accept("text/html").when().get("/swagger-ui/index.html")
			.then().statusCode(200)
			.header("Content-Security-Policy", containsString("default-src 'self'"))
			.header("Content-Security-Policy", containsString("frame-ancestors 'none'"))
			.header("Referrer-Policy", notNullValue());
	}

}
