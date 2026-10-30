package com.roomkeeper.search;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import com.roomkeeper.common.metrics.BusinessMetrics;
import com.roomkeeper.inventory.InventoryChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Scheduling recomputes after sales: one per hotel waiting, and a full queue never wedges a hotel. */
class HotelMinPriceUpdaterTest {

	private static final LocalDate TUESDAY = LocalDate.parse("2026-03-10");

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final List<Runnable> scheduled = new ArrayList<>();

	private boolean full;

	private final Executor repricing = task -> {
		if (full) {
			throw new TaskRejectedException("queue full");
		}
		scheduled.add(task);
	};

	private final HotelMinPriceUpdater updater = new HotelMinPriceUpdater(repricing, new BusinessMetrics(registry),
			null, null, null, null, mock(PlatformTransactionManager.class), null);

	@Test
	void salesAtAHotelWithARecomputeWaitingDontScheduleAnother() {
		updater.onSale(sale(1));
		updater.onSale(sale(1));
		updater.onSale(sale(2));

		assertThat(scheduled).hasSize(2);
	}

	@Test
	void aRecomputeTheQueueRefusesIsCountedAndTheHotelsNextSaleTriesAgain() {
		full = true;
		updater.onSale(sale(1));
		assertThat(dropped()).isOne();

		full = false;
		updater.onSale(sale(1));

		assertThat(scheduled).as("not stuck behind the dropped one").hasSize(1);
		assertThat(dropped()).isOne();
	}

	private double dropped() {
		return registry.get("roomkeeper.repricing.dropped").counter().count();
	}

	private static InventoryChangedEvent sale(long hotelId) {
		return InventoryChangedEvent.sale(hotelId, "Goa", TUESDAY, TUESDAY, false);
	}

}
