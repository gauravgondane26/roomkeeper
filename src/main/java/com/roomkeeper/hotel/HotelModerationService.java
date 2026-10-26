package com.roomkeeper.hotel;

import java.time.Clock;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin review of hotels: only an approved hotel can go on sale. */
@Service
public class HotelModerationService {

	private final HotelRepository hotels;

	private final HotelMapper mapper;

	private final Clock clock;

	public HotelModerationService(HotelRepository hotels, HotelMapper mapper, Clock clock) {
		this.hotels = hotels;
		this.mapper = mapper;
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

	@Transactional
	public HotelReviewResponse reject(long adminId, long hotelId, RejectHotelRequest request) {
		Hotel hotel = find(hotelId);
		if (hotel.getApprovalStatus() == ApprovalStatus.APPROVED) {
			// Taking a hotel off sale is deactivation, which also has to deal with its bookings
			throw new ApiException(ProblemType.CONFLICT, "Hotel " + hotelId + " is already approved");
		}
		hotel.reject(request.reason().strip(), adminId, clock.instant());
		return mapper.toReview(hotel);
	}

	private Hotel find(long hotelId) {
		return hotels.findById(hotelId)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Hotel " + hotelId + " not found"));
	}

}
