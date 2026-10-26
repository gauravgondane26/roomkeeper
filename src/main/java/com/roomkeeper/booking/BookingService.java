package com.roomkeeper.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.InvalidFieldException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.hotel.RoomRepository;
import com.roomkeeper.inventory.Inventory;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.inventory.InventoryRepository;
import com.roomkeeper.pricing.PriceQuote;
import com.roomkeeper.pricing.PricingChain;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A guest's bookings: holding rooms for a stay, and what happens to the hold after. */
@Service
public class BookingService {

	static final int MAX_NIGHTS = 30;

	private final BookingRepository bookings;

	private final RoomRepository rooms;

	private final InventoryRepository inventory;

	private final PricingChain pricing;

	private final BookingMapper mapper;

	private final BookingProperties properties;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public BookingService(BookingRepository bookings, RoomRepository rooms, InventoryRepository inventory,
			PricingChain pricing, BookingMapper mapper, BookingProperties properties, ApplicationEventPublisher events,
			Clock clock) {
		this.bookings = bookings;
		this.rooms = rooms;
		this.inventory = inventory;
		this.pricing = pricing;
		this.mapper = mapper;
		this.properties = properties;
		this.events = events;
		this.clock = clock;
	}

	/**
	 * Holds the rooms on every night of the stay, at the price the rules give now, until the
	 * hold runs out. Sold out if any night is closed or short of rooms.
	 */
	@Transactional
	public BookingResponse init(long userId, BookingInitRequest request) {
		// Hotels that aren't on sale don't exist as far as guests can tell
		Room room = rooms.findById(request.roomId())
			.filter(found -> found.getHotel().isActive())
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Room " + request.roomId() + " not found"));
		Hotel hotel = room.getHotel();
		LocalDate lastNight = lastNightOf(request, hotel);
		int count = request.rooms();

		// Locked until commit, in date order like every other writer, so two requests for the last
		// room can't both see it free, and two overlapping stays can't deadlock each other
		List<Inventory> nights = inventory.lockNights(room.getId(), request.checkIn(), lastNight);
		// A night without a row isn't on sale yet
		boolean everyNight = nights.size() == ChronoUnit.DAYS.between(request.checkIn(), request.checkOut());
		if (!everyNight || nights.stream().anyMatch(night -> !night.canSell(count))) {
			throw new SoldOutException(room.getId(), count, request.checkIn(), request.checkOut());
		}
		// Priced before the hold, as the guest saw it
		PriceQuote quote = pricing.quote(nights.stream().map(night -> night.toNight(room.getBasePrice())).toList(),
				count);
		nights.forEach(night -> night.hold(count));

		Instant now = clock.instant();
		Booking booking = bookings.save(new Booking(userId, room, request.checkIn(), request.checkOut(), count,
				quote.total(), now.plus(properties.holdDuration()), now));
		events.publishEvent(new InventoryChangedEvent(hotel.getId(), hotel.getCity(), request.checkIn(), lastNight));
		return mapper.toResponse(booking);
	}

	/** The stay's last night, after checking the stay is 1 to {@value #MAX_NIGHTS} nights from the hotel's today. */
	private LocalDate lastNightOf(BookingInitRequest request, Hotel hotel) {
		if (request.checkIn().isBefore(hotel.today(clock))) {
			throw new InvalidFieldException("checkIn", "must not be in the past");
		}
		long nights = ChronoUnit.DAYS.between(request.checkIn(), request.checkOut());
		if (nights < 1) {
			throw new InvalidFieldException("checkOut", "must be after checkIn");
		}
		if (nights > MAX_NIGHTS) {
			throw new InvalidFieldException("checkOut", "must be at most " + MAX_NIGHTS + " nights after checkIn");
		}
		return request.checkOut().minusDays(1);
	}

}
