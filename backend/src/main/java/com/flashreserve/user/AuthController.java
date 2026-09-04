package com.flashreserve.user;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Auth endpoints. With HTTP Basic the "login" endpoint merely validates
 * credentials (used by the frontend to prove them early); subsequent API
 * calls send Basic credentials per request. No session is created.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDtos.UserResponse> register(
            @Valid @RequestBody AuthDtos.RegisterRequest request) {
        User created = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuthDtos.UserResponse.from(created));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        userService.login(request);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
