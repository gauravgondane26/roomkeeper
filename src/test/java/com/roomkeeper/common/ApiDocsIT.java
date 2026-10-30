package com.roomkeeper.common;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.endsWith;

/**
 * The API docs and Swagger UI, and / sending a browser to them. Off in prod unless
 * API_DOCS_ENABLED is set; then all three are 404.
 */
class ApiDocsIT extends AbstractIntegrationTest {

	@Test
	void theRootSendsBrowsersToSwaggerUi() {
		api().redirects().follow(false).when().get("/")
			.then().statusCode(302).header("Location", endsWith("/swagger-ui.html"));
		api().when().get("/v3/api-docs").then().statusCode(200);
		api().accept("text/html").when().get("/swagger-ui/index.html").then().statusCode(200);
	}

	@Nested
	@TestPropertySource(properties = { "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false" })
	class WhenTurnedOff extends AbstractIntegrationTest {

		@Test
		void docsUiAndTheRootRedirectAreAllNotFound() {
			api().redirects().follow(false).when().get("/").then().statusCode(404);
			api().when().get("/v3/api-docs").then().statusCode(404);
			api().accept("text/html").when().get("/swagger-ui.html").then().statusCode(404);
			api().accept("text/html").when().get("/swagger-ui/index.html").then().statusCode(404);
		}

	}

}
