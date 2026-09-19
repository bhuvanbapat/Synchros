package com.synchros.user;

import com.synchros.security.JwtService;
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
    private final long ttlSeconds;

    public AuthController(UserService userService, JwtService jwtService,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${synchros.jwt.ttl-seconds:3600}") long ttlSeconds) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.ttlSeconds = ttlSeconds;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDtos.UserResponse> register(
            @Valid @RequestBody AuthDtos.RegisterRequest request) {
        User created = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuthDtos.UserResponse.from(created));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                        jakarta.servlet.http.HttpServletRequest httpRequest) {
        User user = userService.login(request, clientIp(httpRequest));
        String token = jwtService.issue(user.getId(), user.getEmail(), user.getRole());
        return ResponseEntity.ok(new AuthDtos.LoginResponse(token, "Bearer", ttlSeconds,
                AuthDtos.UserResponse.from(user)));
    }

    /** Behind compose/LBs the socket peer is the proxy; honor X-Forwarded-For. */
    private static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
