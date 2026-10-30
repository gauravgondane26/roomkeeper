package com.roomkeeper.hotel;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.user.User;
import com.roomkeeper.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin review of hotels: only an approved hotel can go on sale. */
@Service
public class HotelModerationService {

	static final int DEFAULT_PAGE_SIZE = 20;

	private final HotelRepository hotels;

	private final UserRepository users;

	private final HotelMapper mapper;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public HotelModerationService(HotelRepository hotels, UserRepository users, HotelMapper mapper,
			ApplicationEventPublisher events, Clock clock) {
		this.hotels = hotels;
		this.users = users;
		this.mapper = mapper;
		this.events = events;
		this.clock = clock;
	}

	/** One page of the hotels in a review status, oldest first; the pending queue by default. */
	@Transactional(readOnly = true)
	public HotelReviewPageResponse list(HotelReviewPageRequest request) {
		ApprovalStatus status = request.status() != null ? request.status() : ApprovalStatus.PENDING;
		int page = request.page() != null ? request.page() : 0;
		int size = request.size() != null ? request.size() : DEFAULT_PAGE_SIZE;
		Page<Hotel> found = hotels.findByApprovalStatus(status,
				PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
		Map<Long, HotelReviewResponse.Reviewer> reviewers = reviewers(found.getContent());
		return new HotelReviewPageResponse(
				found.map(hotel -> mapper.toReview(hotel, reviewers.get(hotel.getReviewedBy()))).getContent(), page,
				size, found.getTotalElements(), found.getTotalPages());
	}

	/** Approving an approved hotel again changes nothing. */
	@Transactional
	public HotelReviewResponse approve(long adminId, long hotelId) {
		Hotel hotel = find(hotelId);
		if (hotel.getApprovalStatus() != ApprovalStatus.APPROVED) {
			hotel.approve(adminId, clock.instant());
		}
		return review(hotel);
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
		return review(hotel);
	}

	private HotelReviewResponse review(Hotel hotel) {
		return mapper.toReview(hotel, reviewers(List.of(hotel)).get(hotel.getReviewedBy()));
	}

	/** Who reviewed each of the hotels, by user id, looked up in one query. */
	private Map<Long, HotelReviewResponse.Reviewer> reviewers(Collection<Hotel> reviewed) {
		List<Long> ids = reviewed.stream().map(Hotel::getReviewedBy).filter(Objects::nonNull).distinct().toList();
		return users.findAllById(ids).stream()
			.collect(Collectors.toMap(User::getId, user -> new HotelReviewResponse.Reviewer(user.getId(), user.getEmail())));
	}

	private Hotel find(long hotelId) {
		return hotels.findById(hotelId)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Hotel " + hotelId + " not found"));
	}

}
