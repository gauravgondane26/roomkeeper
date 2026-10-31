package com.roomkeeper.common;

import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.common.error.InvalidFieldException;
import com.roomkeeper.common.error.ProblemDetailsHandler.FieldViolation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class StaysTest {

	private static final LocalDate CHECK_IN = LocalDate.of(2026, 12, 1);

	@Test
	void countsTheNightsOfAStayOfOneToThirtyNights() {
		assertThat(Stays.nights(CHECK_IN, CHECK_IN.plusDays(1))).isEqualTo(1);
		assertThat(Stays.nights(CHECK_IN, CHECK_IN.plusDays(Stays.MAX_NIGHTS))).isEqualTo(30);
	}

	@Test
	void aStayMustEndAfterItStarts() {
		assertThat(violationOf(CHECK_IN, CHECK_IN)).isEqualTo(new FieldViolation("checkOut", "must be after checkIn"));
		assertThat(violationOf(CHECK_IN, CHECK_IN.minusDays(1)))
			.isEqualTo(new FieldViolation("checkOut", "must be after checkIn"));
	}

	@Test
	void aStayIsAtMostThirtyNights() {
		assertThat(violationOf(CHECK_IN, CHECK_IN.plusDays(31)))
			.isEqualTo(new FieldViolation("checkOut", "must be at most 30 nights after checkIn"));
	}

	private static FieldViolation violationOf(LocalDate checkIn, LocalDate checkOut) {
		var thrown = catchThrowableOfType(InvalidFieldException.class, () -> Stays.nights(checkIn, checkOut));
		assertThat(thrown).as("expected an InvalidFieldException").isNotNull();
		var errors = (List<?>) thrown.toProblemDetail().getProperties().get("errors");
		assertThat(errors).hasSize(1);
		return (FieldViolation) errors.getFirst();
	}

}
