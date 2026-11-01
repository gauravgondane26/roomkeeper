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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.notNullValue;
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
			.body("approvalStatus", equalTo("APPROVED"))
			.body("reviewedBy.id", equalTo((int) signedInUserId(adminToken())))
			.body("reviewedBy.email", equalTo(signedInEmail(adminToken())))
			.body("reviewedAt", notNullValue());

		activate()
			.statusCode(200)
			.body("active", equalTo(true));
	}

	@Test
	void anAdminRejectsAHotelWithAReason() {
		moderate("reject", Map.of("reason", "Photos don't match the address"))
			.statusCode(200)
			.body("approvalStatus", equalTo("REJECTED"))
			.body("reviewedBy.email", equalTo(signedInEmail(adminToken())))
			.body("reviewedAt", notNullValue());

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

	/** Renaming or moving it sends it back for review instead (ApprovedHotelChangesIT). */
	@Test
	void editingAnApprovedHotelsAmenitiesKeepsItApproved() {
		moderate("approve", Map.of());

		api(manager).contentType(ContentType.JSON).body(aHotel().named("Seaside Inn").withAmenities("wifi", "spa").build())
			.when().put("/api/v1/admin/hotels/{id}", hotelId)
			.then()
				.statusCode(200)
				.body("approvalStatus", equalTo("APPROVED"));
	}

	@Test
	void adminsSeeTheReviewQueueAPageAtATimeOldestFirst() {
		long second = createHotel(manager, aHotel().named("Hillside").build());
		long third = createHotel(manager, aHotel().named("Lakeside").build());
		long approved = createHotel(manager, aHotel().named("Approved").build());
		approveHotel(approved);
		// Other tests' hotels wait too; these three are the newest, so the last in the queue
		long waiting = queue("PENDING", 0, 1).extract().jsonPath().getLong("totalHotels");

		// One hotel a page, so page n is the queue's hotel n
		queue("PENDING", (int) waiting - 3, 1)
			.body("hotels.id", contains((int) hotelId))
			.body("hotels[0].ownerId", equalTo((int) signedInUserId(manager)))
			.body("hotels[0].name", equalTo("Seaside Inn"))
			.body("hotels[0].reviewedBy", nullValue())
			.body("page", equalTo((int) waiting - 3))
			.body("size", equalTo(1))
			.body("totalHotels", equalTo((int) waiting))
			.body("totalPages", equalTo((int) waiting));
		queue("PENDING", (int) waiting - 2, 1).body("hotels.id", contains((int) second));
		queue("PENDING", (int) waiting - 1, 1).body("hotels.id", contains((int) third));
		queue("PENDING", (int) waiting, 1).body("hotels", empty());
		// The default page is the head of the queue, 20 hotels
		api(adminToken()).when().get("/api/v1/moderation/hotels")
			.then().statusCode(200).body("page", equalTo(0)).body("size", equalTo(20));
	}

	@Test
	void reviewedHotelsShowWhoReviewedThem() {
		moderate("approve", Map.of()).statusCode(200);
		long last = queue("APPROVED", 0, 1).extract().jsonPath().getLong("totalHotels") - 1;

		queue("APPROVED", (int) last, 1)
			.body("hotels[0].id", equalTo((int) hotelId))
			.body("hotels[0].reviewedBy.email", equalTo(signedInEmail(adminToken())))
			.body("hotels[0].reviewedAt", notNullValue());
	}

	@Test
	void theQueueHasAPageSizeLimit() {
		queueRequest("PENDING", 0, 51).statusCode(400).body("errors.field", contains("size"));
	}

	@Test
	void aRejectionNeedsAReason() {
		moderate("reject", Map.of("reason", " "))
			.statusCode(400)
			.body("errors.field", contains("reason"));
	}

	@Test
	void rejectingAnApprovedHotelTakesItOffSaleUntilItsReviewedAgain() {
		moderate("approve", Map.of());
		activate().statusCode(200);

		moderate("reject", Map.of("reason", "Guests report the pool is closed"))
			.statusCode(200)
			.body("approvalStatus", equalTo("REJECTED"));

		api(manager).when().get("/api/v1/admin/hotels/{id}", hotelId)
			.then()
				.body("active", equalTo(false))
				.body("rejectionReason", equalTo("Guests report the pool is closed"));
		activate().statusCode(409).body("type", equalTo("urn:roomkeeper:problem:hotel-not-approved"));
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

	private ValidatableResponse queue(String status, int page, int size) {
		return queueRequest(status, page, size).statusCode(200);
	}

	private ValidatableResponse queueRequest(String status, int page, int size) {
		return api(adminToken()).queryParam("status", status).queryParam("page", page).queryParam("size", size)
			.when().get("/api/v1/moderation/hotels")
			.then();
	}

	private String signedInEmail(String token) {
		return api(token).when().get("/api/v1/users/profile").then().extract().jsonPath().getString("email");
	}

	private long signedInUserId(String token) {
		return api(token).when().get("/api/v1/users/profile").then().extract().jsonPath().getLong("id");
	}

}
