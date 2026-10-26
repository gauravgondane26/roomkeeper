package com.roomkeeper.hotel;

import java.time.Clock;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin review of hotels: only an approved hotel can go on sale. */
@Service
public class HotelModerationService {

	private final HotelRepository hotels;

	private final HotelMapper mapper;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public HotelModerationService(HotelRepository hotels, HotelMapper mapper, ApplicationEventPublisher events,
			Clock clock) {
		this.hotels = hotels;
		this.mapper = mapper;
		this.events = events;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<HotelReviewResponse> list(ApprovalStatus status) {
		return hotels.findByApprovalStatusOrderByCreatedAtAscIdAsc(status).stream().map(mapper::toReview).toList();
	}

	/** Approving an approved hotel again changes nothing. */
	@Transactional
	public HotelReviewResponse approve(long adminId, long hotelId) {
		Hotel hotel = find(hotelId);
		if (hotel.getApprovalStatus() != ApprovalStatus.APPROVED) {
			hotel.approve(adminId, clock.instant());
		}
		return mapper.toReview(hotel);
	}

	/**
	 * Rejecting an approved hotel also takes it off sale, and it stays off until its manager
	 * edits it and an admin approves it again. Bookings it already has carry on.
	 */
	@Transactional
	public HotelReviewResponse reject(long adminId, long hotelId, RejectHotelRequest request) {
		Hotel hotel = find(hotelId);
		hotel.reject(request.reason().strip(), adminId, clock.instant());
		if (hotel.isActive()) {
			hotel.deactivate();
			events.publishEvent(HotelChangedEvent.of(hotel));
		}
		return mapper.toReview(hotel);
	}

	private Hotel find(long hotelId) {
		return hotels.findById(hotelId)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Hotel " + hotelId + " not found"));
	}

}
