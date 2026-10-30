package com.roomkeeper.search;

import org.springframework.stereotype.Service;

/** The public view of a hotel. Hotels that aren't on sale don't exist as far as guests can tell. */
@Service
public class HotelInfoService {

	private final HotelInfoReads reads;

	private final HotelInfoVersions versions;

	public HotelInfoService(HotelInfoReads reads, HotelInfoVersions versions) {
		this.reads = reads;
		this.versions = versions;
	}

	/** The version is taken before the load, so a change committed meanwhile retires what this read caches. */
	public HotelInfoResponse get(long hotelId) {
		return reads.load(hotelId, versions.current(hotelId));
	}

}
