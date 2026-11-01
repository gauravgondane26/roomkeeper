package com.roomkeeper.hotel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A hotel's own rules: its local date, going on and off sale, and review by an admin. */
class HotelTest {

	private static final Instant CREATED_AT = Instant.parse("2026-03-10T09:00:00Z");

	private static final long ADMIN = 99L;

	@Test
	void todayIsTheDateWhereTheHotelIs() {
		// 20:00 in London is already 01:30 the next day in Goa
		Clock evening = Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneOffset.UTC);

		assertThat(hotel("Asia/Kolkata").today(evening)).isEqualTo(LocalDate.parse("2026-03-11"));
		assertThat(hotel("Europe/London").today(evening)).isEqualTo(LocalDate.parse("2026-03-10"));
	}

	@Test
	void keepsWhatItWasCreatedWith() {
		Hotel hotel = hotel("Asia/Kolkata");

		assertThat(hotel.getOwnerId()).isEqualTo(7L);
		assertThat(hotel.getName()).isEqualTo("Seaside Inn");
		assertThat(hotel.getCity()).isEqualTo("Goa");
		assertThat(hotel.getTimeZone()).isEqualTo("Asia/Kolkata");
		assertThat(hotel.getAmenities()).containsExactly("wifi", "pool");
		assertThat(hotel.isActive()).as("off sale until activated").isFalse();
		assertThat(hotel.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
		assertThat(hotel.getReviewedBy()).isNull();
	}

	@Test
	void goesOnAndOffSale() {
		Hotel hotel = hotel("Asia/Kolkata");

		hotel.activate();
		assertThat(hotel.isActive()).isTrue();

		hotel.deactivate();
		assertThat(hotel.isActive()).isFalse();
	}

	@Test
	void anApprovalRecordsWhoApprovedAndWhen() {
		Hotel hotel = hotel("Asia/Kolkata");
		Instant at = CREATED_AT.plusSeconds(3600);

		hotel.approve(ADMIN, at);

		assertThat(hotel.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
		assertThat(hotel.getRejectionReason()).isNull();
		assertThat(hotel.getReviewedBy()).isEqualTo(ADMIN);
		assertThat(hotel.getReviewedAt()).isEqualTo(at);
	}

	@Test
	void aRejectionRecordsWhyWhoAndWhen() {
		Hotel hotel = hotel("Asia/Kolkata");
		Instant at = CREATED_AT.plusSeconds(3600);

		hotel.reject("No photos of the rooms", ADMIN, at);

		assertThat(hotel.getApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED);
		assertThat(hotel.getRejectionReason()).isEqualTo("No photos of the rooms");
		assertThat(hotel.getReviewedBy()).isEqualTo(ADMIN);
		assertThat(hotel.getReviewedAt()).isEqualTo(at);
	}

	@Test
	void editingARejectedHotelSendsItBackForReview() {
		Hotel hotel = hotel("Asia/Kolkata");
		hotel.reject("No photos of the rooms", ADMIN, CREATED_AT);

		hotel.changeDetails("Seaside Inn & Spa", "North Goa", "Asia/Kolkata", List.of("wifi", "pool", "spa"));

		assertThat(hotel.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
		assertThat(hotel.getRejectionReason()).isNull();
		assertThat(hotel.getName()).isEqualTo("Seaside Inn & Spa");
		assertThat(hotel.getCity()).isEqualTo("North Goa");
		assertThat(hotel.getAmenities()).containsExactly("wifi", "pool", "spa");
	}

	@Test
	void anApprovedHotelsTimeZoneAndAmenitiesChangeWithoutAReview() {
		Hotel hotel = hotel("Asia/Kolkata");
		hotel.approve(ADMIN, CREATED_AT);
		hotel.activate();

		hotel.changeDetails("Seaside Inn", "Goa", "UTC", List.of());

		assertThat(hotel.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
		assertThat(hotel.isActive()).isTrue();
		assertThat(hotel.getReviewedBy()).isEqualTo(ADMIN);
		assertThat(hotel.getTimeZone()).isEqualTo("UTC");
		assertThat(hotel.getAmenities()).isEmpty();
	}

	@Test
	void renamingAnApprovedHotelSendsItBackForReviewOffSale() {
		Hotel hotel = hotel("Asia/Kolkata");
		hotel.approve(ADMIN, CREATED_AT);
		hotel.activate();

		hotel.changeDetails("Grand Palace Hotel", "Goa", "Asia/Kolkata", List.of("wifi", "pool"));

		assertThat(hotel.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
		assertThat(hotel.isActive()).as("off sale until approved again").isFalse();
		assertThat(hotel.getReviewedBy()).isNull();
		assertThat(hotel.getReviewedAt()).isNull();
		assertThat(hotel.getName()).isEqualTo("Grand Palace Hotel");
	}

	@Test
	void movingAnApprovedHotelSendsItBackForReviewOffSale() {
		Hotel hotel = hotel("Asia/Kolkata");
		hotel.approve(ADMIN, CREATED_AT);
		hotel.activate();

		hotel.changeDetails("Seaside Inn", "Mumbai", "Asia/Kolkata", List.of("wifi", "pool"));

		assertThat(hotel.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
		assertThat(hotel.isActive()).isFalse();
		assertThat(hotel.getCity()).isEqualTo("Mumbai");
	}

	@Test
	void renamingAHotelStillWaitingForReviewLeavesItWaiting() {
		Hotel hotel = hotel("Asia/Kolkata");

		hotel.changeDetails("Grand Palace Hotel", "Goa", "Asia/Kolkata", List.of("wifi", "pool"));

		assertThat(hotel.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
		assertThat(hotel.getName()).isEqualTo("Grand Palace Hotel");
	}

	private static Hotel hotel(String timeZone) {
		return new Hotel(7L, "Seaside Inn", "Goa", timeZone, List.of("wifi", "pool"), CREATED_AT);
	}

}
