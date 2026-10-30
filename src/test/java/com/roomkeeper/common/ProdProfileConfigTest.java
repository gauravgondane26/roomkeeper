package com.roomkeeper.common;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** What application-prod.yaml resolves to, from the environment variables a deployment sets. */
class ProdProfileConfigTest {

	@Test
	void apiDocsAndSwaggerUiAreOffUnlessEnabled() throws IOException {
		assertThat(apiDocs(Map.of())).containsExactly(false, false);
		assertThat(apiDocs(Map.of("API_DOCS_ENABLED", "true"))).containsExactly(true, true);
	}

	private static Boolean[] apiDocs(Map<String, Object> variables) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().addFirst(new MapPropertySource("deployment", variables));
		new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yaml"))
			.forEach(environment.getPropertySources()::addLast);
		Binder binder = Binder.get(environment);
		return new Boolean[] { binder.bind("springdoc.api-docs.enabled", Boolean.class).orElse(true),
				binder.bind("springdoc.swagger-ui.enabled", Boolean.class).orElse(true) };
	}

}
