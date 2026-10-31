package com.roomkeeper.common;

import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.equalTo;

/**
 * VIRTUAL_THREADS=true serves requests on virtual threads (docs/virtual-threads-benchmark.md
 * has the before-and-after numbers).
 */
@TestPropertySource(properties = "VIRTUAL_THREADS=true")
@Import(VirtualThreadsIT.ThreadProbe.class)
class VirtualThreadsIT extends AbstractIntegrationTest {

	@Test
	void requestsRunOnVirtualThreads() {
		String token = signUpAndLogIn(aUser().build());

		api(token).when().get("/api/v1/probe/thread")
			.then().statusCode(200).body("virtual", equalTo(true));
	}

	@RestController
	static class ThreadProbe {

		@GetMapping("/api/v1/probe/thread")
		Map<String, Object> thread() {
			return Map.of("virtual", Thread.currentThread().isVirtual());
		}

	}

}
