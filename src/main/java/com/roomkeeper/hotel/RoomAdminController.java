package com.roomkeeper.hotel;

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
@RequestMapping("/api/v1/admin/hotels/{hotelId}/rooms")
@Tag(name = "Room admin", description = "Room types in a hotel the manager owns")
public class RoomAdminController {

	private final RoomService roomService;

	public RoomAdminController(RoomService roomService) {
		this.roomService = roomService;
	}

	@PostMapping
	public ResponseEntity<RoomResponse> create(@CurrentUserId long userId, @PathVariable long hotelId,
			@Valid @RequestBody RoomRequest request) {
		RoomResponse room = roomService.create(userId, hotelId, request);
		var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(room.id())
			.toUri();
		return ResponseEntity.created(location).body(room);
	}

	@GetMapping
	public List<RoomResponse> list(@CurrentUserId long userId, @PathVariable long hotelId) {
		return roomService.list(userId, hotelId);
	}

	@GetMapping("/{roomId}")
	public RoomResponse get(@CurrentUserId long userId, @PathVariable long hotelId, @PathVariable long roomId) {
		return roomService.get(userId, hotelId, roomId);
	}

	@PutMapping("/{roomId}")
	public RoomResponse update(@CurrentUserId long userId, @PathVariable long hotelId, @PathVariable long roomId,
			@Valid @RequestBody RoomRequest request) {
		return roomService.update(userId, hotelId, roomId, request);
	}

	@DeleteMapping("/{roomId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@CurrentUserId long userId, @PathVariable long hotelId, @PathVariable long roomId) {
		roomService.delete(userId, hotelId, roomId);
	}

}
