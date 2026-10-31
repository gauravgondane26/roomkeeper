package com.roomkeeper.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.common.Stays;
import com.roomkeeper.common.error.InvalidFieldException;
import com.roomkeeper.common.metrics.BusinessMetrics;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.inventory.Inventory;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.pricing.PriceQuote;
import com.roomkeeper.pricing.PricingChain;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Makes a hold on nights the caller has already checked and, unless the caller reads them
 * optimistically, locked: a guest's own hold, or rooms offered to a waiting guest. Either
 * way the same price and hold time.
 */
@Component
class Holds {

	private final BookingRepository bookings;

	private final PricingChain pricing;

	private final BookingProperties properties;

	private final ApplicationEventPublisher events;

	private final BusinessMetrics metrics;

	private final Clock clock;

	Holds(BookingRepository bookings, PricingChain pricing, BookingProperties properties,
			ApplicationEventPublisher events, BusinessMetrics metrics, Clock clock) {
		this.bookings = bookings;
		this.pricing = pricing;
		this.properties = properties;
		this.events = events;
		this.metrics = metrics;
		this.clock = clock;
	}

	/**
	 * Holds {@code count} rooms on {@code nights}, the stay's nights, at the price the rules
	 * give now, until the hold time runs out.
	 */
	Booking hold(long userId, Room room, LocalDate checkIn, LocalDate checkOut, int count, List<Inventory> nights) {
		// Priced before the hold, as the guest saw it
		PriceQuote quote = pricing.quote(nights.stream().map(night -> night.toNight(room.getBasePrice())).toList(),
				count);
		nights.forEach(night -> night.hold(count));

		Instant now = clock.instant();
		Booking booking = bookings.save(new Booking(userId, room, checkIn, checkOut, count, quote.total(),
				now.plus(properties.holdDuration()), now));
		Hotel hotel = room.getHotel();
		events.publishEvent(InventoryChangedEvent.sale(hotel.getId(), hotel.getCity(), checkIn, checkOut.minusDays(1),
				Inventory.anySoldOut(nights)));
		metrics.bookingCreated();
		return booking;
	}

	/**
	 * The stay's last night, after checking it starts no earlier than the hotel's today and
	 * is 1 to {@value Stays#MAX_NIGHTS} nights.
	 */
	LocalDate lastNightOf(LocalDate checkIn, LocalDate checkOut, Hotel hotel) {
		if (checkIn.isBefore(hotel.today(clock))) {
			throw new InvalidFieldException("checkIn", "must not be in the past");
		}
		Stays.nights(checkIn, checkOut);
		return checkOut.minusDays(1);
	}

}
