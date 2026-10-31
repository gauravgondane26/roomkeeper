package com.roomkeeper.booking;

import java.util.List;

import com.roomkeeper.common.idempotency.IdempotencyKeyHeader;
import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/waitlist")
@Tag(name = "Waitlist", description = "Guests wait for a sold-out stay; rooms a hold or a cancellation gives back are "
		+ "held for the oldest waiter whose stay then fits, and they're emailed")
public class WaitlistController {

	private final WaitlistService waitlist;

	public WaitlistController(WaitlistService waitlist) {
		this.waitlist = waitlist;
	}

	@PostMapping
	@IdempotencyKeyHeader
	public ResponseEntity<WaitlistEntryResponse> joinWaitlist(@CurrentUserId long userId,
			@Valid @RequestBody WaitlistJoinRequest request) {
		WaitlistEntryResponse entry = waitlist.join(userId, request);
		var location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/v1/waitlist/{id}")
			.buildAndExpand(entry.id())
			.toUri();
		return ResponseEntity.created(location).body(entry);
	}

	/** The caller's places in line, newest first; an offered one names the booking holding their rooms. */
	@GetMapping
	public List<WaitlistEntryResponse> listWaitlist(@CurrentUserId long userId) {
		return waitlist.list(userId);
	}

	@GetMapping("/{entryId}")
	public WaitlistEntryResponse getWaitlistEntry(@CurrentUserId long userId, @PathVariable long entryId) {
		return waitlist.get(userId, entryId);
	}

	@DeleteMapping("/{entryId}")
	public ResponseEntity<Void> leaveWaitlist(@CurrentUserId long userId, @PathVariable long entryId) {
		waitlist.leave(userId, entryId);
		return ResponseEntity.noContent().build();
	}

}
