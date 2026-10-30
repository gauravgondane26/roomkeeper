package com.roomkeeper.hotel;

import java.time.Instant;
import java.util.List;

/**
 * An admin's view of a hotel under review, including who manages it, and who last
 * approved or rejected it and when (null until an admin has). A hotel edited after a
 * rejection is back in the queue, still showing that last review.
 */
public record HotelReviewResponse(Long id, Long ownerId, String name, String city, String timeZone,
		List<String> amenities, boolean active, ApprovalStatus approvalStatus, String rejectionReason,
		Reviewer reviewedBy, Instant reviewedAt) {

	/** The admin who reviewed the hotel. */
	public record Reviewer(Long id, String email) {
	}

}
