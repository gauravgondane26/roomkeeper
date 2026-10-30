package com.roomkeeper.common.security;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import io.restassured.http.ContentType;
import io.restassured.http.Method;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every admin and moderation endpoint against every kind of caller: anonymous (401), a
 * guest (403), a manager who doesn't own the hotel (403), and the one allowed (anything
 * but 401, 403 or a server error: what it does is tested elsewhere). The list is checked
 * against the app's mappings, so a new admin endpoint fails here until it's added. Each
 * call gets a fresh active hotel, so deleting or deactivating one doesn't affect the next.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAuthorizationMatrixIT extends AbstractIntegrationTest {

	/** {hotelId} and {roomId} are filled in with the owner's hotel and its room type. */
	record Endpoint(Method method, String path, Map<String, Object> body) {

		static Endpoint of(Method method, String path) {
			return new Endpoint(method, path, null);
		}

		static Endpoint of(Method method, String path, Map<String, Object> body) {
			return new Endpoint(method, path, body);
		}

		@Override
		public String toString() {
			return method + " " + path;
		}

	}

	private static final Map<String, Object> NIGHTS = Map.of("from", "2030-01-10", "to", "2030-01-12", "closed", true);

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	private String owner;

	private String otherManager;

	private String guest;

	private long hotelId;

	private long roomId;

	@BeforeAll
	void signUpTheCallers() {
		owner = signUpAndLogIn(aUser().asHotelManager().build());
		otherManager = signUpAndLogIn(aUser().asHotelManager().build());
		guest = signUpAndLogIn(aUser().build());
	}

	@BeforeEach
	void openAHotel() {
		hotelId = activeHotel(owner, aHotel().build(), aRoom().build());
		roomId = firstRoomOf(owner, hotelId);
	}

	/** Endpoints any hotel manager may call, about their own hotels. */
	static Stream<Endpoint> managerEndpoints() {
		return Stream.of(
				Endpoint.of(Method.POST, "/api/v1/admin/hotels", aHotel().build()),
				Endpoint.of(Method.GET, "/api/v1/admin/hotels"));
	}

	/** Endpoints about one hotel, for its own manager only. */
	static Stream<Endpoint> ownerEndpoints() {
		return Stream.of(
				Endpoint.of(Method.GET, "/api/v1/admin/hotels/{hotelId}"),
				Endpoint.of(Method.PUT, "/api/v1/admin/hotels/{hotelId}", aHotel().build()),
				Endpoint.of(Method.PATCH, "/api/v1/admin/hotels/{hotelId}/activate"),
				Endpoint.of(Method.PATCH, "/api/v1/admin/hotels/{hotelId}/deactivate"),
				Endpoint.of(Method.DELETE, "/api/v1/admin/hotels/{hotelId}"),
				Endpoint.of(Method.POST, "/api/v1/admin/hotels/{hotelId}/rooms", aRoom().ofType("Suite").build()),
				Endpoint.of(Method.GET, "/api/v1/admin/hotels/{hotelId}/rooms"),
				Endpoint.of(Method.GET, "/api/v1/admin/hotels/{hotelId}/rooms/{roomId}"),
				Endpoint.of(Method.PUT, "/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", aRoom().build()),
				Endpoint.of(Method.DELETE, "/api/v1/admin/hotels/{hotelId}/rooms/{roomId}"),
				Endpoint.of(Method.GET, "/api/v1/admin/hotels/{hotelId}/bookings"),
				Endpoint.of(Method.GET, "/api/v1/admin/hotels/{hotelId}/reports?from=2030-01-01&to=2030-01-31"),
				Endpoint.of(Method.GET, "/api/v1/admin/inventory/rooms/{roomId}?from=2030-01-01&to=2030-01-31"),
				Endpoint.of(Method.PATCH, "/api/v1/admin/inventory/rooms/{roomId}", NIGHTS));
	}

	/** Endpoints for the platform admin only. */
	static Stream<Endpoint> moderationEndpoints() {
		return Stream.of(
				Endpoint.of(Method.GET, "/api/v1/moderation/hotels"),
				Endpoint.of(Method.PATCH, "/api/v1/moderation/hotels/{hotelId}/approve"),
				Endpoint.of(Method.PATCH, "/api/v1/moderation/hotels/{hotelId}/reject",
						Map.of("reason", "Photos don't match the address")));
	}

	static Stream<Endpoint> allEndpoints() {
		return Stream.of(managerEndpoints(), ownerEndpoints(), moderationEndpoints()).flatMap(endpoints -> endpoints);
	}

	@Test
	void everyAdminAndModerationEndpointIsInTheMatrix() {
		Set<String> mapped = new TreeSet<>();
		handlerMapping.getHandlerMethods().keySet().forEach(mapping -> mapping.getPathPatternsCondition()
			.getPatternValues()
			.stream()
			.filter(path -> path.startsWith("/api/v1/admin") || path.startsWith("/api/v1/moderation"))
			.forEach(path -> mapping.getMethodsCondition().getMethods().forEach(method -> mapped.add(method + " " + path))));

		assertThat(allEndpoints().map(endpoint -> endpoint.method() + " " + endpoint.path().replaceAll("\\?.*", "")))
			.containsExactlyInAnyOrderElementsOf(mapped);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("allEndpoints")
	void anonymousCallersMustLogIn(Endpoint endpoint) {
		assertThat(call(api(), endpoint)).isEqualTo(401);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("allEndpoints")
	void guestsAreForbidden(Endpoint endpoint) {
		assertThat(call(api(guest), endpoint)).isEqualTo(403);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource({ "ownerEndpoints", "moderationEndpoints" })
	void anotherManagerIsForbidden(Endpoint endpoint) {
		assertThat(call(api(otherManager), endpoint)).isEqualTo(403);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource({ "managerEndpoints", "ownerEndpoints" })
	void theOwnerIsLetThrough(Endpoint endpoint) {
		assertThat(call(api(owner), endpoint)).isNotIn(401, 403).isLessThan(500);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("moderationEndpoints")
	void theAdminIsLetThrough(Endpoint endpoint) {
		assertThat(call(api(adminToken()), endpoint)).isNotIn(401, 403).isLessThan(500);
	}

	private int call(RequestSpecification request, Endpoint endpoint) {
		if (endpoint.body() != null) {
			request = request.contentType(ContentType.JSON).body(endpoint.body());
		}
		String path = endpoint.path()
			.replace("{hotelId}", String.valueOf(hotelId))
			.replace("{roomId}", String.valueOf(roomId));
		return request.when().request(endpoint.method(), path).then().extract().statusCode();
	}

}
