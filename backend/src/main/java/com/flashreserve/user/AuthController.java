package com.flashreserve.user;

import com.flashreserve.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Auth endpoints. Login validates credentials via the AuthenticationManager
 * and returns a signed JWT; subsequent API calls present it as a Bearer
 * token. No session is created — everything is stateless.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDtos.UserResponse> register(
            @Valid @RequestBody AuthDtos.RegisterRequest request) {
        User created = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuthDtos.UserResponse.from(created));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        User user = userService.login(request);
        String token = jwtService.issue(user.getId(), user.getEmail(), user.getRole());
        return ResponseEntity.ok(new AuthDtos.LoginResponse(token, "Bearer", 3600,
                AuthDtos.UserResponse.from(user)));
    }
}
