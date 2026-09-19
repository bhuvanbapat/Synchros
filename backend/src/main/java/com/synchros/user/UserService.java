package com.synchros.user;

import com.synchros.common.DomainException;
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
    private final com.synchros.security.LoginAttemptLimiter loginLimiter;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       com.synchros.security.LoginAttemptLimiter loginLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.loginLimiter = loginLimiter;
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
        try {
            return userRepository.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Concurrent register with the same email lost the UNIQUE race —
            // surface the same clean 400 the pre-check would have produced.
            throw new DomainException(DomainException.ErrorCode.INVALID_REQUEST,
                    "Email already registered");
        }
    }

    /**
     * Validates credentials through the AuthenticationManager and returns
     * the persisted user. Brute-force lockout runs first: 5 failures per
     * (email, ip) inside 15 minutes refuses further attempts with 429
     * (fail-open when the counter store is unavailable).
     */
    public User login(AuthDtos.LoginRequest request, String remoteAddr) {
        if (!loginLimiter.isAllowed(request.email(), remoteAddr)) {
            throw new DomainException(DomainException.ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many failed sign-in attempts; try again later");
        }
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (BadCredentialsException e) {
            loginLimiter.recordFailure(request.email(), remoteAddr);
            throw new DomainException(DomainException.ErrorCode.UNAUTHORIZED,
                    "Invalid email or password");
        }
        loginLimiter.recordSuccess(request.email(), remoteAddr);
        return userRepository.findByEmail(request.email()).orElseThrow();
    }
}
