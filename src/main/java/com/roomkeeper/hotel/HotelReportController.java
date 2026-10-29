package com.roomkeeper.hotel;

import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/hotels/{hotelId}/reports")
@Tag(name = "Hotel admin")
public class HotelReportController {

	private final HotelReportService reports;

	public HotelReportController(HotelReportService reports) {
		this.reports = reports;
	}

	@GetMapping
	@Operation(summary = "Revenue and occupancy over a range of nights",
			description = "Each night from `from` to `to` (both included, at most 366): rooms booked by paid stays, "
					+ "rooms on sale, and revenue, with totals, occupancy, and the average daily rate. Revenue is "
					+ "counted by night stayed: each paid booking's amount, less any refund, spread over its nights.")
	public HotelReport report(@CurrentUserId long ownerId, @PathVariable long hotelId,
			@Valid @ParameterObject HotelReportRequest request) {
		return reports.report(ownerId, hotelId, request);
	}

}
