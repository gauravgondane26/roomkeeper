package com.roomkeeper.hotel;

import java.time.temporal.ChronoUnit;

import com.roomkeeper.common.error.InvalidFieldException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Revenue and occupancy reports for the hotels a manager owns. */
@Service
public class HotelReportService {

	/** A year of nights, leap years included. */
	static final int MAX_NIGHTS = 366;

	private final HotelService hotels;

	private final HotelReportRepository reports;

	public HotelReportService(HotelService hotels, HotelReportRepository reports) {
		this.hotels = hotels;
		this.reports = reports;
	}

	/** The report on the nights asked for, at a hotel the manager owns: 404 or 403 for anyone else's. */
	@Transactional(readOnly = true)
	public HotelReport report(long ownerId, long hotelId, HotelReportRequest request) {
		hotels.findOwned(ownerId, hotelId);
		if (request.to().isBefore(request.from())) {
			throw new InvalidFieldException("to", "must not be before from");
		}
		if (ChronoUnit.DAYS.between(request.from(), request.to()) + 1 > MAX_NIGHTS) {
			throw new InvalidFieldException("to", "must be at most " + MAX_NIGHTS + " nights from from, both included");
		}
		return HotelReport.of(request.from(), request.to(), reports.nights(hotelId, request.from(), request.to()));
	}

}
