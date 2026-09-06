package com.flashreserve.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record UserResponse(String id, String email, String role) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getPublicId().toString(), u.getEmail(), u.getRole());
        }
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {
    }

    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            UserResponse user) {
    }
}
