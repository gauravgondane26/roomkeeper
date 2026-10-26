package com.roomkeeper.search;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels")
@Tag(name = "Browse", description = "Search hotels and see their details; no login needed")
@SecurityRequirements // open endpoints: no bearer token needed
public class HotelBrowseController {

	private final HotelSearchService searchService;

	private final HotelInfoService infoService;

	public HotelBrowseController(HotelSearchService searchService, HotelInfoService infoService) {
		this.searchService = searchService;
		this.infoService = infoService;
	}

	/** Hotels in a city with a room type free for every night of the stay, cheapest first. */
	@GetMapping("/search")
	public HotelSearchResponse search(@Valid @ParameterObject HotelSearchRequest request) {
		return searchService.search(request);
	}

	@GetMapping("/{hotelId}/info")
	public HotelInfoResponse info(@PathVariable long hotelId) {
		return infoService.get(hotelId);
	}

}
