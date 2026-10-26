package com.roomkeeper.booking;

import java.time.Clock;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A user's saved guests. Another user's guests, and removed ones, are not found. */
@Service
public class GuestService {

	private final GuestRepository guests;

	private final GuestMapper mapper;

	private final Clock clock;

	public GuestService(GuestRepository guests, GuestMapper mapper, Clock clock) {
		this.guests = guests;
		this.mapper = mapper;
		this.clock = clock;
	}

	@Transactional
	public GuestResponse create(long userId, GuestRequest request) {
		return mapper.toResponse(
				guests.save(new Guest(userId, request.name().strip(), request.age(), request.gender())));
	}

	@Transactional(readOnly = true)
	public List<GuestResponse> list(long userId) {
		return guests.findByUserIdAndRemovedAtIsNullOrderById(userId).stream().map(mapper::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public GuestResponse get(long userId, long guestId) {
		return mapper.toResponse(findOwned(userId, guestId));
	}

	@Transactional
	public GuestResponse update(long userId, long guestId, GuestRequest request) {
		Guest guest = findOwned(userId, guestId);
		guest.changeDetails(request.name().strip(), request.age(), request.gender());
		return mapper.toResponse(guest);
	}

	@Transactional
	public void remove(long userId, long guestId) {
		findOwned(userId, guestId).remove(clock.instant());
	}

	/** The user's guest, if it exists and wasn't removed: 404 otherwise. */
	Guest findOwned(long userId, long guestId) {
		return guests.findByIdAndUserIdAndRemovedAtIsNull(guestId, userId)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Guest " + guestId + " not found"));
	}

}
