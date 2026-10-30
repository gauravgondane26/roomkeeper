package com.roomkeeper.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The OpenAPI spec must match the snapshot in {@value #SNAPSHOT}, so a change to the API's
 * shape (a renamed field, a new required parameter, a removed route) fails the build
 * until it's accepted on purpose. To accept one, rerun with
 * {@code -Dopenapi.snapshot.update=true} and review the snapshot's diff with the change.
 * Keys are sorted and the server URL (a random port here) is left out, so only a change
 * to the API itself differs.
 */
class OpenApiSnapshotIT extends AbstractIntegrationTest {

	static final String SNAPSHOT = "src/test/resources/openapi.json";

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.build();

	@Test
	void theSpecMatchesTheSnapshot() throws IOException {
		String spec = normalized(api().when().get("/v3/api-docs").then().statusCode(200).extract().asString());
		Path snapshot = Path.of(SNAPSHOT);

		if (Boolean.getBoolean("openapi.snapshot.update")) {
			Files.writeString(snapshot, spec, StandardCharsets.UTF_8);
			return;
		}
		if (!Files.exists(snapshot)) {
			fail("No snapshot at " + SNAPSHOT + "; create it with -Dopenapi.snapshot.update=true");
		}
		assertThat(spec)
			.as("The API changed. If that's intended, rerun with -Dopenapi.snapshot.update=true and commit "
					+ SNAPSHOT + " with the change")
			.isEqualTo(lf(Files.readString(snapshot, StandardCharsets.UTF_8)));
	}

	@SuppressWarnings("unchecked")
	private static String normalized(String spec) {
		Map<String, Object> tree = JSON.readValue(spec, Map.class);
		tree.remove("servers");
		return lf(JSON.writeValueAsString(tree) + "\n");
	}

	/** The pretty printer ends lines the platform's way; the snapshot is stored with LF, as Git checks it out. */
	private static String lf(String text) {
		return text.replace("\r\n", "\n");
	}

}
