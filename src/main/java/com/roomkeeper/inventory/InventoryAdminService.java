package com.roomkeeper.inventory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.InvalidFieldException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.hotel.RoomService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A manager's view of a room type's nights, and bulk changes over a date range. */
@Service
public class InventoryAdminService {

	static final int DEFAULT_NIGHTS = 30;

	private final InventoryRepository inventory;

	private final RoomService rooms;

	private final InventoryMapper mapper;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public InventoryAdminService(InventoryRepository inventory, RoomService rooms, InventoryMapper mapper,
			ApplicationEventPublisher events, Clock clock) {
		this.inventory = inventory;
		this.rooms = rooms;
		this.mapper = mapper;
		this.events = events;
		this.clock = clock;
	}

	/** The nights in the range; by default the next {@value #DEFAULT_NIGHTS} from the hotel's today. */
	@Transactional(readOnly = true)
	public List<InventoryResponse> list(long ownerId, long roomId, LocalDate from, LocalDate to) {
		Room room = rooms.findOwnedRoom(ownerId, roomId);
		LocalDate start = from != null ? from : room.getHotel().today(clock);
		NightRange range = NightRange.of(start, to != null ? to : start.plusDays(DEFAULT_NIGHTS - 1));
		return inventory.findByRoomIdAndDateBetweenOrderByDate(roomId, range.from(), range.to()).stream()
			.map(mapper::toResponse)
			.toList();
	}

	/** Applies the request to every night in its range and returns the changed nights. */
	@Transactional
	public List<InventoryResponse> update(long ownerId, long roomId, InventoryUpdateRequest request) {
		Room room = rooms.findOwnedRoom(ownerId, roomId);
		if (request.changesNothing()) {
			throw new ApiException(ProblemType.VALIDATION, "Set at least one of closed, surgeFactor, or totalCount");
		}
		NightRange range = NightRange.of(request.from(), request.to());
		if (range.from().isBefore(room.getHotel().today(clock))) {
			throw new InvalidFieldException("from", "must not be in the past");
		}
		if (!room.getHotel().isActive()) {
			throw new ApiException(ProblemType.CONFLICT,
					"Hotel " + room.getHotel().getId() + " isn't active yet, so room " + roomId
							+ " has no inventory to change");
		}

		// Locked, so no booking can take a room between this check and the update
		List<Inventory> nights = inventory.lockNights(roomId, range.from(), range.to());
		if (request.totalCount() != null) {
			InventoryService.requireNotBelowSold(nights, request.totalCount());
		}
		for (Inventory night : nights) {
			if (request.closed() != null) {
				night.markClosed(request.closed());
			}
			if (request.surgeFactor() != null) {
				night.changeSurgeFactor(request.surgeFactor().setScale(2));
			}
			if (request.totalCount() != null) {
				night.overrideTotalCount(request.totalCount());
			}
		}
		Hotel hotel = room.getHotel();
		events.publishEvent(new InventoryChangedEvent(hotel.getId(), hotel.getCity(), range.from(), range.to()));
		return nights.stream().map(mapper::toResponse).toList();
	}

}
