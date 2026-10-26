package com.roomkeeper.hotel;

import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Anyone can sign up as a hotel manager, but a hotel only goes on sale once an admin has
 * approved it, so guests only ever see reviewed hotels.
 */
class HotelApprovalIT extends AbstractIntegrationTest {

	private String manager;

	private long hotelId;

	@BeforeEach
	void createAHotelWithARoom() {
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = createHotel(manager, aHotel().named("Seaside Inn").build());
		createRoom(manager, hotelId, aRoom().build());
	}

	@Test
	void newHotelsWaitForReview() {
		api(manager).when().get("/api/v1/admin/hotels/{id}", hotelId)
			.then()
				.statusCode(200)
				.body("approvalStatus", equalTo("PENDING"))
				.body("rejectionReason", nullValue());
	}

	@Test
	void aHotelCannotGoOnSaleBeforeApproval() {
		activate()
			.statusCode(409)
			.body("type", equalTo("urn:roomkeeper:problem:hotel-not-approved"));
	}

	@Test
	void onceAnAdminApprovesItTheManagerCanActivateIt() {
		moderate("approve", Map.of())
			.statusCode(200)
			.body("approvalStatus", equalTo("APPROVED"));

		activate()
			.statusCode(200)
			.body("active", equalTo(true));
	}

	@Test
	void anAdminRejectsAHotelWithAReason() {
		moderate("reject", Map.of("reason", "Photos don't match the address"))
			.statusCode(200)
			.body("approvalStatus", equalTo("REJECTED"));

		api(manager).when().get("/api/v1/admin/hotels/{id}", hotelId)
			.then()
				.body("approvalStatus", equalTo("REJECTED"))
				.body("rejectionReason", equalTo("Photos don't match the address"));
		activate().statusCode(409);
	}

	@Test
	void editingARejectedHotelSendsItBackForReview() {
		moderate("reject", Map.of("reason", "Wrong city"));

		api(manager).contentType(ContentType.JSON).body(aHotel().named("Seaside Inn").inCity("Pune").build())
			.when().put("/api/v1/admin/hotels/{id}", hotelId)
			.then()
				.statusCode(200)
				.body("approvalStatus", equalTo("PENDING"))
				.body("rejectionReason", nullValue());
	}

	@Test
	void editingAnApprovedHotelKeepsItApproved() {
		moderate("approve", Map.of());

		api(manager).contentType(ContentType.JSON).body(aHotel().named("Seaside Resort").build())
			.when().put("/api/v1/admin/hotels/{id}", hotelId)
			.then()
				.statusCode(200)
				.body("approvalStatus", equalTo("APPROVED"));
	}

	@Test
	void adminsSeeTheReviewQueue() {
		long approved = createHotel(manager, aHotel().named("Hillside").build());
		approveHotel(approved);

		api(adminToken()).when().get("/api/v1/moderation/hotels")
			.then()
				.statusCode(200)
				.body("id", hasItem((int) hotelId))
				.body("id", not(hasItem((int) approved)))
				.body("find { it.id == %d }.ownerId".formatted(hotelId), equalTo((int) signedInUserId(manager)))
				.body("find { it.id == %d }.name".formatted(hotelId), equalTo("Seaside Inn"));

		api(adminToken()).queryParam("status", "APPROVED").when().get("/api/v1/moderation/hotels")
			.then()
				.body("id", hasItem((int) approved));
	}

	@Test
	void aRejectionNeedsAReason() {
		moderate("reject", Map.of("reason", " "))
			.statusCode(400)
			.body("errors.field", contains("reason"));
	}

	@Test
	void anApprovedHotelCannotBeRejected() {
		moderate("approve", Map.of());

		moderate("reject", Map.of("reason", "Changed my mind"))
			.statusCode(409)
			.body("type", equalTo("urn:roomkeeper:problem:conflict"));
	}

	@Test
	void onlyAdminsModerate() {
		String guest = signUpAndLogIn(aUser().build());

		for (String token : new String[] { manager, guest }) {
			api(token).when().patch("/api/v1/moderation/hotels/{id}/approve", hotelId)
				.then()
					.statusCode(403)
					.body("type", equalTo("urn:roomkeeper:problem:forbidden"));
		}
		api().when().patch("/api/v1/moderation/hotels/{id}/approve", hotelId).then().statusCode(401);
		api(manager).when().get("/api/v1/moderation/hotels").then().statusCode(403);
	}

	@Test
	void theConfiguredAdminCanSignIn() {
		api(adminToken()).when().get("/api/v1/users/profile")
			.then()
				.statusCode(200)
				.body("role", equalTo("ADMIN"));
	}

	@Test
	void unknownHotelIsNotFound() {
		api(adminToken()).when().patch("/api/v1/moderation/hotels/{id}/approve", Long.MAX_VALUE)
			.then()
				.statusCode(404)
				.body("type", equalTo("urn:roomkeeper:problem:not-found"));
	}

	private ValidatableResponse activate() {
		return api(manager).when().patch("/api/v1/admin/hotels/{id}/activate", hotelId).then();
	}

	private ValidatableResponse moderate(String action, Map<String, Object> body) {
		var request = api(adminToken());
		if (!body.isEmpty()) {
			request.contentType(ContentType.JSON).body(body);
		}
		return request.when().patch("/api/v1/moderation/hotels/{id}/" + action, hotelId).then();
	}

	private long signedInUserId(String token) {
		return api(token).when().get("/api/v1/users/profile").then().extract().jsonPath().getLong("id");
	}

}
