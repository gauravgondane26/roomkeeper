package com.roomkeeper.booking;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.inventory.Inventory;
import com.roomkeeper.inventory.InventoryRepository;
import com.roomkeeper.outbox.OutboxWriter;
import com.roomkeeper.outbox.WaitlistOffered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offers rooms that just came free to the guests waiting for them. Called by whatever frees
 * them (an expired hold, a cancellation), inside its transaction, after the rooms are back:
 * the offer commits with the release, so the rooms are never on sale while someone waits.
 * <p>
 * The room type's line is gone through oldest first. A waiter is offered rooms when every
 * night of their stay can now be sold, as a normal hold (same price, same hold time) and an
 * email; a waiter whose stay doesn't fit is skipped and keeps their place, and one whose
 * stay has begun is dropped. The releasing transaction already holds the freed nights, so
 * it takes a waiter's other nights, and their entry, only if nobody else holds them: it
 * never waits, so it can't deadlock with a hold locking nights in date order. A waiter
 * skipped for that is offered rooms the next time some come free.
 */
@Component
public class WaitlistOffers {

	private static final Logger log = LoggerFactory.getLogger(WaitlistOffers.class);

	private final WaitlistRepository entries;

	private final InventoryRepository inventory;

	private final Holds holds;

	private final OutboxWriter outbox;

	private final Clock clock;

	WaitlistOffers(WaitlistRepository entries, InventoryRepository inventory, Holds holds, OutboxWriter outbox,
			Clock clock) {
		this.entries = entries;
		this.inventory = inventory;
		this.holds = holds;
		this.outbox = outbox;
		this.clock = clock;
	}

	/** Offers the room type's freed rooms down its line; returns how many waiters got some. */
	@Transactional(propagation = Propagation.MANDATORY)
	public int offerFreedRooms(Room room) {
		Hotel hotel = room.getHotel();
		LocalDate today = hotel.today(clock);
		int offered = 0;
		for (long id : entries.findIdsInLine(room.getId(), WaitlistStatus.WAITING)) {
			WaitlistEntry entry = entries.lockWaiting(id).orElse(null);
			if (entry == null) {
				continue;
			}
			if (entry.hasBegunBy(today)) {
				entry.expire();
				continue;
			}
			List<Inventory> nights = inventory.lockFreeNights(room.getId(), entry.getCheckIn(), entry.lastNight());
			// A night left out is busy elsewhere: as good as not fitting, for now
			if (!Inventory.canSellStay(nights, entry.getCheckIn(), entry.getCheckOut(), entry.getRoomsCount())) {
				continue;
			}
			Booking booking = holds.hold(entry.getUserId(), room, entry.getCheckIn(), entry.getCheckOut(),
					entry.getRoomsCount(), nights);
			entry.offer(booking.getId(), clock.instant());
			outbox.write(new WaitlistOffered(booking.getId(), entry.getUserId(), hotel.getName(), room.getType(),
					entry.getCheckIn(), entry.getCheckOut(), entry.getRoomsCount(), booking.getAmount(),
					booking.getHoldExpiresAt()));
			log.info("Waitlist entry {} offered rooms as booking {}", entry.getId(), booking.getId());
			offered++;
		}
		return offered;
	}

}
