package com.roomkeeper.booking;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.roomkeeper.booking.BookingStatus.CANCELLED;
import static com.roomkeeper.booking.BookingStatus.CONFIRMED;
import static com.roomkeeper.booking.BookingStatus.EXPIRED;
import static com.roomkeeper.booking.BookingStatus.GUESTS_ADDED;
import static com.roomkeeper.booking.BookingStatus.PAYMENT_PENDING;
import static com.roomkeeper.booking.BookingStatus.RESERVED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every pair of statuses, checked against the state diagram in the design doc: a hold can
 * time out at any point before it's confirmed, and a confirmed booking can only be
 * cancelled. Guests can be changed while the booking is still on hold, and a payment that
 * arrives after the hold ran out can still confirm it.
 */
class BookingStatusTest {

	private static final Map<BookingStatus, Set<BookingStatus>> DIAGRAM = Map.of(
			RESERVED, EnumSet.of(GUESTS_ADDED, EXPIRED),
			GUESTS_ADDED, EnumSet.of(GUESTS_ADDED, PAYMENT_PENDING, EXPIRED),
			PAYMENT_PENDING, EnumSet.of(CONFIRMED, EXPIRED),
			CONFIRMED, EnumSet.of(CANCELLED),
			EXPIRED, EnumSet.of(CONFIRMED),
			CANCELLED, EnumSet.noneOf(BookingStatus.class));

	static Stream<Arguments> allowedMoves() {
		return everyPair(true);
	}

	static Stream<Arguments> rejectedMoves() {
		return everyPair(false);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("allowedMoves")
	void allows(BookingStatus from, BookingStatus to) {
		assertThat(from.canMoveTo(to)).isTrue();
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("rejectedMoves")
	void rejects(BookingStatus from, BookingStatus to) {
		assertThat(from.canMoveTo(to)).isFalse();
	}

	@ParameterizedTest
	@MethodSource("statuses")
	void onlyHoldsCanExpire(BookingStatus status) {
		assertThat(status.isHold()).isEqualTo(status.canMoveTo(EXPIRED));
	}

	static Stream<BookingStatus> statuses() {
		return Arrays.stream(BookingStatus.values());
	}

	/** Every (from, to) pair the diagram allows, or every pair it doesn't. */
	private static Stream<Arguments> everyPair(boolean allowed) {
		return statuses().flatMap(from -> statuses()
			.filter(to -> DIAGRAM.get(from).contains(to) == allowed)
			.map(to -> Arguments.of(from, to)));
	}

}
