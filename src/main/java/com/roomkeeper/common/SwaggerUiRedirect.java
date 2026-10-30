package com.roomkeeper.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Sends a browser opening the bare URL to Swagger UI, which is the API's client. Only while
 * Swagger UI is on: in prod it's off unless API_DOCS_ENABLED is set, and then / is 404.
 */
@Controller
@ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", matchIfMissing = true)
class SwaggerUiRedirect {

	@GetMapping("/")
	String root() {
		return "redirect:/swagger-ui.html";
	}

}
