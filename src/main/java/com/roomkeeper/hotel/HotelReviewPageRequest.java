package com.roomkeeper.hotel;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Which hotels to list for review: {@code status} (the pending queue by default), and
 * which page of them, {@code page} from 0, 20 hotels a page by default.
 */
public record HotelReviewPageRequest(ApprovalStatus status, @Min(0) Integer page, @Min(1) @Max(50) Integer size) {
}
