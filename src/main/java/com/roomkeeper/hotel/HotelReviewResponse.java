package com.roomkeeper.hotel;

import java.util.List;

/** An admin's view of a hotel under review, including who manages it. */
public record HotelReviewResponse(Long id, Long ownerId, String name, String city, String timeZone,
		List<String> amenities, boolean active, ApprovalStatus approvalStatus, String rejectionReason) {
}
