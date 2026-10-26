package com.roomkeeper.inventory;

import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/inventory/rooms/{roomId}")
@Tag(name = "Inventory admin", description = "Per-night availability, closures, and surge pricing for a room type")
public class InventoryAdminController {

	private final InventoryAdminService inventoryAdminService;

	public InventoryAdminController(InventoryAdminService inventoryAdminService) {
		this.inventoryAdminService = inventoryAdminService;
	}

	/** Nights from {@code from} to {@code to}, both included; the next 30 nights by default. */
	@GetMapping
	public List<InventoryResponse> list(@CurrentUserId long userId, @PathVariable long roomId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return inventoryAdminService.list(userId, roomId, from, to);
	}

	@PatchMapping
	public List<InventoryResponse> update(@CurrentUserId long userId, @PathVariable long roomId,
			@Valid @RequestBody InventoryUpdateRequest request) {
		return inventoryAdminService.update(userId, roomId, request);
	}

}
