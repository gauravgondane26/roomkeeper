package com.roomkeeper.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.inventory.Inventory;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.inventory.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives back the rooms of holds that ran out unpaid: the nights' reserved_count goes down
 * and the booking becomes EXPIRED. Each booking is released in its own transaction, so one
 * that fails doesn't keep the others' rooms off sale.
 */
@Service
class HoldReleaser {

	private static final Logger log = LoggerFactory.getLogger(HoldReleaser.class);

	/** Holds found per query; a backlog bigger than this is worked through a batch at a time. */
	static final int BATCH = 200;

	private final BookingRepository bookings;

	private final InventoryRepository inventory;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transaction;

	private final Clock clock;

	HoldReleaser(BookingRepository bookings, InventoryRepository inventory, ApplicationEventPublisher events,
			PlatformTransactionManager transactions, Clock clock) {
		this.bookings = bookings;
		this.inventory = inventory;
		this.events = events;
		this.transaction = new TransactionTemplate(transactions);
		this.clock = clock;
	}

	/** Releases every hold that has run out by now; returns how many. */
	int releaseExpired() {
		Instant now = clock.instant();
		int released = 0;
		List<Long> ids;
		int releasedInBatch;
		do {
			ids = bookings.findIdsHeldUntil(BookingStatus.holds(), now, Limit.of(BATCH));
			releasedInBatch = 0;
			for (long id : ids) {
				if (release(id, now)) {
					releasedInBatch++;
				}
			}
			released += releasedInBatch;
		}
		// A batch that released nothing would come back the same; leave it for the next run
		while (ids.size() == BATCH && releasedInBatch > 0);
		return released;
	}

	private boolean release(long bookingId, Instant now) {
		try {
			return Boolean.TRUE.equals(transaction.execute(status -> releaseLocked(bookingId, now)));
		}
		catch (RuntimeException ex) {
			log.error("Could not release the hold on booking {}; trying again next run", bookingId, ex);
			return false;
		}
	}

	/** Locks the booking, then its nights in date order, as every writer does. */
	private boolean releaseLocked(long bookingId, Instant now) {
		Booking booking = bookings.lockById(bookingId).orElse(null);
		// Since it was found it may have been paid for, or released by another run
		if (booking == null || !booking.getStatus().isHold() || !booking.holdRanOut(now)) {
			return false;
		}
		boolean paying = booking.getStatus() == BookingStatus.PAYMENT_PENDING;
		Room room = booking.getRoom();
		LocalDate lastNight = booking.lastNight();
		List<Inventory> nights = inventory.lockNights(room.getId(), booking.getCheckIn(), lastNight);
		// A night with no room left before the release has one again after it
		boolean reopened = Inventory.anySoldOut(nights);
		nights.forEach(night -> night.release(booking.getRoomsCount()));
		booking.expire();

		Hotel hotel = room.getHotel();
		events.publishEvent(
				InventoryChangedEvent.sale(hotel.getId(), hotel.getCity(), booking.getCheckIn(), lastNight, reopened));
		events.publishEvent(new BookingExpiredEvent(booking.getId(), paying));
		return true;
	}

}
