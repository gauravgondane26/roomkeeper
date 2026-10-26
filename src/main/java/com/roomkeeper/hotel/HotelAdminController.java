package com.roomkeeper.hotel;

import java.util.List;

import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/admin/hotels")
@Tag(name = "Hotel admin", description = "Hotel managers set up the hotels they own")
public class HotelAdminController {

	private final HotelService hotelService;

	public HotelAdminController(HotelService hotelService) {
		this.hotelService = hotelService;
	}

	@PostMapping
	public ResponseEntity<HotelResponse> create(@CurrentUserId long userId, @Valid @RequestBody HotelRequest request) {
		HotelResponse hotel = hotelService.create(userId, request);
		var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(hotel.id())
			.toUri();
		return ResponseEntity.created(location).body(hotel);
	}

	@GetMapping
	public List<HotelResponse> list(@CurrentUserId long userId) {
		return hotelService.listOwned(userId);
	}

	@GetMapping("/{hotelId}")
	public HotelResponse get(@CurrentUserId long userId, @PathVariable long hotelId) {
		return hotelService.get(userId, hotelId);
	}

	@PutMapping("/{hotelId}")
	public HotelResponse update(@CurrentUserId long userId, @PathVariable long hotelId,
			@Valid @RequestBody HotelRequest request) {
		return hotelService.update(userId, hotelId, request);
	}

	@PatchMapping("/{hotelId}/activate")
	public HotelResponse activate(@CurrentUserId long userId, @PathVariable long hotelId) {
		return hotelService.activate(userId, hotelId);
	}

	@DeleteMapping("/{hotelId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@CurrentUserId long userId, @PathVariable long hotelId) {
		hotelService.delete(userId, hotelId);
	}

}
