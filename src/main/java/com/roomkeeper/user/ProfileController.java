package com.roomkeeper.user;

import com.roomkeeper.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/profile")
@Tag(name = "Profile", description = "The signed-in user's own profile")
public class ProfileController {

	private final UserService userService;

	public ProfileController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping
	public UserResponse get(@CurrentUserId long userId) {
		return userService.get(userId);
	}

	@PatchMapping
	public UserResponse update(@CurrentUserId long userId, @Valid @RequestBody UpdateProfileRequest request) {
		return userService.updateProfile(userId, request);
	}

}
