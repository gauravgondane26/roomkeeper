package com.roomkeeper.hotel;

import java.util.List;

/** A manager's view of their hotel, including where it is in admin review. */
public record HotelResponse(Long id, String name, String city, String timeZone, List<String> amenities,
		boolean active, ApprovalStatus approvalStatus, String rejectionReason) {
}
