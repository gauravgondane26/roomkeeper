package com.roomkeeper.hotel;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.inventory.InventoryService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Room type admin. Rooms are reached through their hotel, so the hotel's owner check covers them. */
@Service
public class RoomService {

	private final RoomRepository rooms;

	private final HotelService hotels;

	private final InventoryService inventory;

	private final RoomMapper mapper;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public RoomService(RoomRepository rooms, HotelService hotels, InventoryService inventory, RoomMapper mapper,
			ApplicationEventPublisher events, Clock clock) {
		this.rooms = rooms;
		this.hotels = hotels;
		this.inventory = inventory;
		this.mapper = mapper;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public RoomResponse create(long ownerId, long hotelId, RoomRequest request) {
		Hotel hotel = hotels.findOwned(ownerId, hotelId);
		String type = request.type().strip();
		if (rooms.existsByHotelIdAndTypeIgnoreCase(hotelId, type)) {
			throw new RoomTypeExistsException(type);
		}
		Room room = saveAndFlush(
				new Room(hotel, type, price(request), request.capacity(), request.totalCount(), clock.instant()), type);
		// An active hotel is already on sale, so the new room goes on sale with it
		if (hotel.isActive()) {
			inventory.openYear(room.getId(), room.getTotalCount(), hotel.today(clock));
			inventoryChanged(hotel);
		}
		events.publishEvent(HotelChangedEvent.roomTypesOf(hotel));
		return mapper.toResponse(room);
	}

	@Transactional(readOnly = true)
	public List<RoomResponse> list(long ownerId, long hotelId) {
		hotels.findOwned(ownerId, hotelId);
		return rooms.findByHotelIdOrderById(hotelId).stream().map(mapper::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public RoomResponse get(long ownerId, long hotelId, long roomId) {
		return mapper.toResponse(findOwned(ownerId, hotelId, roomId));
	}

	@Transactional
	public RoomResponse update(long ownerId, long hotelId, long roomId, RoomRequest request) {
		Room room = findOwned(ownerId, hotelId, roomId);
		String type = request.type().strip();
		if (rooms.existsByHotelIdAndTypeIgnoreCaseAndIdNot(hotelId, type, roomId)) {
			throw new RoomTypeExistsException(type);
		}
		Hotel hotel = room.getHotel();
		boolean totalChanges = request.totalCount() != room.getTotalCount();
		// Only the price and the room count affect what's sold and for how much; a new name or
		// capacity leaves every night's price and availability as it was
		boolean salesChange = totalChanges || price(request).compareTo(room.getBasePrice()) != 0;
		if (hotel.isActive() && totalChanges) {
			inventory.applyRoomTotal(roomId, request.totalCount(), hotel.today(clock));
		}
		room.changeDetails(type, price(request), request.capacity(), request.totalCount());
		RoomResponse response = mapper.toResponse(saveAndFlush(room, type));
		if (hotel.isActive() && salesChange) {
			inventoryChanged(hotel);
		}
		events.publishEvent(HotelChangedEvent.roomTypesOf(hotel));
		return response;
	}

	/** Only a room type that has never had a booking: bookings are history the hotel keeps. */
	@Transactional
	public void delete(long ownerId, long hotelId, long roomId) {
		Room room = findOwned(ownerId, hotelId, roomId);
		Hotel hotel = room.getHotel();
		if (rooms.hasBookings(roomId)) {
			throw hasBookings(roomId);
		}
		try {
			rooms.delete(room);
			rooms.flush();
		}
		catch (DataIntegrityViolationException ex) {
			// A booking came in after the check; the foreign key from bookings caught it
			throw hasBookings(roomId);
		}
		if (hotel.isActive()) {
			inventoryChanged(hotel);
		}
		events.publishEvent(HotelChangedEvent.roomTypesOf(hotel));
	}

	private static ApiException hasBookings(long roomId) {
		return new ApiException(ProblemType.HAS_BOOKINGS,
				"Room type " + roomId + " has bookings, so it can't be deleted; close its nights instead");
	}

	/** The hotel's rooms changed, so every night it sells from today on may have changed too. */
	private void inventoryChanged(Hotel hotel) {
		events.publishEvent(InventoryChangedEvent.fromNight(hotel.getId(), hotel.getCity(), hotel.today(clock)));
	}

	/** The room, if it exists and the caller owns its hotel: 404 or 403 otherwise. */
	public Room findOwnedRoom(long ownerId, long roomId) {
		Room room = rooms.findById(roomId)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Room " + roomId + " not found"));
		hotels.findOwned(ownerId, room.getHotel().getId());
		return room;
	}

	private Room findOwned(long ownerId, long hotelId, long roomId) {
		hotels.findOwned(ownerId, hotelId);
		return rooms.findByIdAndHotelId(roomId, hotelId)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND,
					"Room " + roomId + " not found in hotel " + hotelId));
	}

	private Room saveAndFlush(Room room, String type) {
		try {
			return rooms.saveAndFlush(room);
		}
		catch (DataIntegrityViolationException ex) {
			// Two requests with the same type raced past the check; the unique index caught it
			throw new RoomTypeExistsException(type);
		}
	}

	/** Always two decimal places, so 4500 and 4500.00 come back the same way. */
	private static BigDecimal price(RoomRequest request) {
		return request.basePrice().setScale(2);
	}

}
