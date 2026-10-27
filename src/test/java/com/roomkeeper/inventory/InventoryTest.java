package com.roomkeeper.inventory;

import java.time.LocalDate;

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

}
