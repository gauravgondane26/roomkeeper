package com.roomkeeper.hotel;

import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/moderation/hotels")
@Tag(name = "Moderation", description = "Admins review hotels before they go on sale")
public class HotelModerationController {

	private final HotelModerationService moderationService;

	public HotelModerationController(HotelModerationService moderationService) {
		this.moderationService = moderationService;
	}

	/** Hotels in a review status, oldest first, a page at a time; the pending queue by default. */
	@GetMapping
	public HotelReviewPageResponse list(@Valid @ParameterObject HotelReviewPageRequest request) {
		return moderationService.list(request);
	}

	@PatchMapping("/{hotelId}/approve")
	public HotelReviewResponse approve(@CurrentUserId long adminId, @PathVariable long hotelId) {
		return moderationService.approve(adminId, hotelId);
	}

	@PatchMapping("/{hotelId}/reject")
	public HotelReviewResponse reject(@CurrentUserId long adminId, @PathVariable long hotelId,
			@Valid @RequestBody RejectHotelRequest request) {
		return moderationService.reject(adminId, hotelId, request);
	}

}
