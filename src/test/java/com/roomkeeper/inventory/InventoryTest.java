package com.roomkeeper.inventory;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

	@Test
	void confirmingAPaidHoldMovesItsRoomsFromHeldToBooked() {
		Inventory night = new Inventory(7L, LocalDate.parse("2026-03-10"), 5);
		night.hold(2);

		night.confirm(2);

		assertThat(night.getReservedCount()).isZero();
		assertThat(night.getBookedCount()).isEqualTo(2);
		assertThat(night.getAvailable()).isEqualTo(3);
	}

	@Test
	void confirmsOnlyRoomsThatAreHeld() {
		Inventory night = new Inventory(7L, LocalDate.parse("2026-03-10"), 5);
		night.hold(1);

		assertThatThrownBy(() -> night.confirm(2)).isInstanceOf(IllegalStateException.class);
		assertThat(night.getReservedCount()).isEqualTo(1);
		assertThat(night.getBookedCount()).isZero();
	}

	@Test
	void aStaySellsWhenEveryNightIsOnSaleWithTheRoomsFree() {
		LocalDate tuesday = LocalDate.parse("2026-03-10");
		LocalDate thursday = tuesday.plusDays(2);
		Inventory first = new Inventory(7L, tuesday, 2);
		Inventory second = new Inventory(7L, tuesday.plusDays(1), 2);

		assertThat(Inventory.canSellStay(List.of(first, second), tuesday, thursday, 2)).isTrue();
		assertThat(Inventory.canSellStay(List.of(first, second), tuesday, thursday, 3)).as("too many rooms").isFalse();
		assertThat(Inventory.canSellStay(List.of(first), tuesday, thursday, 1)).as("a night not on sale yet").isFalse();
		second.markClosed(true);
		assertThat(Inventory.canSellStay(List.of(first, second), tuesday, thursday, 1)).as("a night closed").isFalse();
	}

	@Test
	void aNightWithNoRoomLeftIsSoldOut() {
		Inventory open = new Inventory(7L, LocalDate.parse("2026-03-10"), 2);
		Inventory full = new Inventory(7L, LocalDate.parse("2026-03-11"), 1);
		full.hold(1);

		assertThat(Inventory.anySoldOut(List.of(open))).isFalse();
		assertThat(Inventory.anySoldOut(List.of(open, full))).isTrue();
	}

	@Test
	void unbookingPutsCancelledRoomsBackOnSale() {
		Inventory night = new Inventory(7L, LocalDate.parse("2026-03-10"), 5);
		night.book(3);

		night.unbook(2);

		assertThat(night.getBookedCount()).isEqualTo(1);
		assertThat(night.getAvailable()).isEqualTo(4);
		assertThatThrownBy(() -> night.unbook(2)).as("more than are booked").isInstanceOf(IllegalStateException.class);
		assertThat(night.getBookedCount()).isEqualTo(1);
	}

	@Test
	void bookingRoomsStraightAwayTakesThemFromSale() {
		Inventory night = new Inventory(7L, LocalDate.parse("2026-03-10"), 5);
		night.hold(2);

		night.book(3);

		assertThat(night.getBookedCount()).isEqualTo(3);
		assertThat(night.getReservedCount()).isEqualTo(2);
		assertThat(night.getAvailable()).isZero();
	}

	@Test
	void booksOnlyRoomsThatAreFree() {
		Inventory night = new Inventory(7L, LocalDate.parse("2026-03-10"), 2);
		night.hold(1);

		assertThatThrownBy(() -> night.book(2)).isInstanceOf(IllegalStateException.class);
		night.markClosed(true);
		assertThatThrownBy(() -> night.book(1)).isInstanceOf(IllegalStateException.class);
		assertThat(night.getBookedCount()).isZero();
	}

	@Test
	void holdsOnlyRoomsThatAreFreeOnAnOpenNight() {
		Inventory night = new Inventory(7L, LocalDate.parse("2026-03-10"), 2);
		night.hold(1);

		assertThatThrownBy(() -> night.hold(2)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("1 free");
		night.markClosed(true);
		assertThatThrownBy(() -> night.hold(1)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("closed");
		assertThat(night.getReservedCount()).isOne();
	}

	@Test
	void releasesOnlyRoomsThatAreHeld() {
		Inventory night = new Inventory(7L, LocalDate.parse("2026-03-10"), 5);
		night.hold(2);
		night.release(1);

		assertThatThrownBy(() -> night.release(2)).isInstanceOf(IllegalStateException.class);
		assertThat(night.getReservedCount()).isOne();
		assertThat(night.getAvailable()).isEqualTo(4);
	}

}
