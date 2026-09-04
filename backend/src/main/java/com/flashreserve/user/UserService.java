package com.flashreserve.user;

import com.flashreserve.common.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @Transactional
    public User register(AuthDtos.RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new DomainException(DomainException.ErrorCode.INVALID_REQUEST,
                    "Email already registered");
        }
        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole("USER");
        return userRepository.save(user);
    }

    /** Validates credentials; returns the persisted user. */
    public User login(AuthDtos.LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (BadCredentialsException e) {
            throw new DomainException(DomainException.ErrorCode.UNAUTHORIZED,
                    "Invalid email or password");
        }
        return userRepository.findByEmail(request.email()).orElseThrow();
    }
}
