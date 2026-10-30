package com.roomkeeper.search;

import java.time.LocalDate;

import com.roomkeeper.inventory.InventoryChangedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sales waiting for a hotel's cheapest prices to be recomputed: at most one recompute per
 * hotel waits, and later sales merge into it instead of queueing their own.
 */
class PendingRecomputesTest {

	private static final LocalDate TUESDAY = LocalDate.parse("2026-03-10");

	private final PendingRecomputes pending = new PendingRecomputes();

	@Test
	void onlyAHotelsFirstSaleNeedsARecomputeScheduled() {
		assertThat(pending.add(sale(1, TUESDAY, TUESDAY.plusDays(1), false))).isTrue();
		assertThat(pending.add(sale(1, TUESDAY.plusDays(3), TUESDAY.plusDays(4), false))).as("merged").isFalse();
		assertThat(pending.add(sale(2, TUESDAY, TUESDAY, false))).as("another hotel").isTrue();
	}

	@Test
	void theWaitingRecomputeCoversEveryMergedSalesNightsAndEvictsIfAnyWould() {
		pending.add(sale(1, TUESDAY.plusDays(2), TUESDAY.plusDays(3), false));
		pending.add(sale(1, TUESDAY, TUESDAY, true));
		pending.add(sale(1, TUESDAY.plusDays(5), TUESDAY.plusDays(6), false));

		assertThat(pending.take(1)).isEqualTo(
				new PendingRecomputes.Recompute(1, "Goa", TUESDAY, TUESDAY.plusDays(6), true));
	}

	@Test
	void aSaleAfterTheRecomputeWasTakenSchedulesAnother() {
		pending.add(sale(1, TUESDAY, TUESDAY, false));
		pending.take(1);

		assertThat(pending.add(sale(1, TUESDAY, TUESDAY, false))).isTrue();
		assertThat(pending.take(1).evictSearches()).isFalse();
	}

	@Test
	void takingWhatIsntWaitingGivesNothing() {
		assertThat(pending.take(7)).isNull();
	}

	private static InventoryChangedEvent sale(long hotelId, LocalDate from, LocalDate to, boolean evict) {
		return InventoryChangedEvent.sale(hotelId, "Goa", from, to, evict);
	}

}
