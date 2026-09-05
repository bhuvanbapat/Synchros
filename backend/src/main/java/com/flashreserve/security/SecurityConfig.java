package com.flashreserve.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic auth for the demo (documented as a portfolio-scale choice in
 * docs/SECURITY.md — swap for JWT/OAuth in production without touching
 * ownership checks, which live in services via userId scoping).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final FlashUserDetailsService userDetailsService;

    public SecurityConfig(FlashUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    /**
     * CORS for the known frontend origins ONLY (explicit allow-list, never
     * a wildcard: credentials are Basic auth). Origins are configurable so
     * deployments can point at their own frontend host.
     */
    @Bean
    org.springframework.web.cors.UrlBasedCorsConfigurationSource corsConfigurationSource() {
        var config = new org.springframework.web.cors.CorsConfiguration();
        config.setAllowedOrigins(java.util.List.of(
                java.util.Optional.ofNullable(
                        System.getenv("FRONTEND_ORIGIN")).orElse("http://localhost:5173"),
                "http://localhost:5173"));
        config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type",
                "Idempotency-Key", "X-Request-Id"));
        config.setMaxAge(3600L);
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless API, no cookies
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/payment-webhooks").permitAll() // signature below
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll() // static frontend
                )
                .httpBasic(basic -> basic.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setHeader("WWW-Authenticate", "Basic realm=\"flashreserve\"");
                    res.setContentType("application/json");
                    res.getWriter().write(
                            "{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
                }))
                .exceptionHandling(eh -> eh
                        .accessDeniedHandler((req, res, ex) -> {
                            // Same structured error model as the rest of the API.
                            res.setStatus(HttpStatus.FORBIDDEN.value());
                            res.setContentType("application/json");
                            res.getWriter().write(
                                    "{\"code\":\"FORBIDDEN\",\"message\":\"You do not have access to this resource\"}");
                        }));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
