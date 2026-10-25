package com.roomkeeper.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(ProblemDetailsTest.TestController.class)
@Import(ProblemDetailsTest.TestController.class)
class ProblemDetailsTest {

	@Autowired
	MockMvcTester mvc;

	@Test
	void validationErrorListsEachInvalidField() {
		var result = mvc.post().uri("/test/widgets")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"name": "", "quantity": 0}
				""")
			.exchange();

		assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		var json = assertThat(result).bodyJson();
		json.extractingPath("$.type").isEqualTo("urn:roomkeeper:problem:validation");
		json.extractingPath("$.status").isEqualTo(400);
		json.extractingPath("$.errors[*].field").asArray().containsExactlyInAnyOrder("name", "quantity");
	}

	@Test
	void malformedJsonIsABadRequest() {
		var result = mvc.post().uri("/test/widgets")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{not json")
			.exchange();

		assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:roomkeeper:problem:bad-request");
	}

	@Test
	void apiExceptionUsesItsTypeStatusAndDetail() {
		var result = mvc.get().uri("/test/conflict").exchange();

		assertThat(result).hasStatus(409).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		var json = assertThat(result).bodyJson();
		json.extractingPath("$.type").isEqualTo("urn:roomkeeper:problem:conflict");
		json.extractingPath("$.title").isEqualTo("Conflict");
		json.extractingPath("$.detail").isEqualTo("Widget already exists");
		json.extractingPath("$.instance").isEqualTo("/test/conflict");
	}

	@Test
	void unexpectedExceptionIsA500WithoutInternalDetails() {
		var result = mvc.get().uri("/test/boom").exchange();

		assertThat(result).hasStatus(500).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		var json = assertThat(result).bodyJson();
		json.extractingPath("$.type").isEqualTo("urn:roomkeeper:problem:internal-error");
		json.extractingPath("$.detail").asString().doesNotContain("secret");
	}

	@Test
	void wrongMethodIsA405() {
		var result = mvc.delete().uri("/test/widgets").exchange();

		assertThat(result).hasStatus(405).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:roomkeeper:problem:method-not-allowed");
	}

	@RestController
	static class TestController {

		record WidgetRequest(@NotBlank String name, @Min(1) int quantity) {
		}

		@PostMapping("/test/widgets")
		WidgetRequest create(@Valid @RequestBody WidgetRequest request) {
			return request;
		}

		@GetMapping("/test/conflict")
		void conflict() {
			throw new ApiException(ProblemType.CONFLICT, "Widget already exists");
		}

		@GetMapping("/test/boom")
		void boom() {
			throw new IllegalStateException("secret internal state");
		}

	}

}
