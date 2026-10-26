package com.roomkeeper.hotel;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Room type admin. Rooms are reached through their hotel, so the hotel's owner check covers them. */
@Service
public class RoomService {

	private final RoomRepository rooms;

	private final HotelService hotels;

	private final RoomMapper mapper;

	private final Clock clock;

	public RoomService(RoomRepository rooms, HotelService hotels, RoomMapper mapper, Clock clock) {
		this.rooms = rooms;
		this.hotels = hotels;
		this.mapper = mapper;
		this.clock = clock;
	}

	@Transactional
	public RoomResponse create(long ownerId, long hotelId, RoomRequest request) {
		Hotel hotel = hotels.findOwned(ownerId, hotelId);
		String type = request.type().strip();
		if (rooms.existsByHotelIdAndTypeIgnoreCase(hotelId, type)) {
			throw new RoomTypeExistsException(type);
		}
		Room room = new Room(hotel, type, price(request), request.capacity(), request.totalCount(), clock.instant());
		return mapper.toResponse(saveAndFlush(room, type));
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
		room.changeDetails(type, price(request), request.capacity(), request.totalCount());
		return mapper.toResponse(saveAndFlush(room, type));
	}

	@Transactional
	public void delete(long ownerId, long hotelId, long roomId) {
		rooms.delete(findOwned(ownerId, hotelId, roomId));
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
