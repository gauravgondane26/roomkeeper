package com.roomkeeper.booking;

import java.util.List;

import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/users/guests")
@Tag(name = "Saved guests", description = "Travellers the signed-in user keeps on file to put on bookings")
public class GuestController {

	private final GuestService guestService;

	public GuestController(GuestService guestService) {
		this.guestService = guestService;
	}

	@PostMapping
	public ResponseEntity<GuestResponse> create(@CurrentUserId long userId, @Valid @RequestBody GuestRequest request) {
		GuestResponse guest = guestService.create(userId, request);
		var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(guest.id())
			.toUri();
		return ResponseEntity.created(location).body(guest);
	}

	@GetMapping
	public List<GuestResponse> list(@CurrentUserId long userId) {
		return guestService.list(userId);
	}

	@GetMapping("/{guestId}")
	public GuestResponse get(@CurrentUserId long userId, @PathVariable long guestId) {
		return guestService.get(userId, guestId);
	}

	@PutMapping("/{guestId}")
	public GuestResponse update(@CurrentUserId long userId, @PathVariable long guestId,
			@Valid @RequestBody GuestRequest request) {
		return guestService.update(userId, guestId, request);
	}

	/** Removed guests stay on the bookings they're already on. */
	@DeleteMapping("/{guestId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@CurrentUserId long userId, @PathVariable long guestId) {
		guestService.remove(userId, guestId);
	}

}
