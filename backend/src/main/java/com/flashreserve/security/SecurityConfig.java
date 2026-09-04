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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless API, no cookies
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
                .addFilterBefore(new RequestIdAuthBridge(),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Copies request-scoped context (none currently) before auth runs. */
    static class RequestIdAuthBridge extends org.springframework.web.filter.OncePerRequestFilter {
        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            chain.doFilter(request, response);
        }
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
