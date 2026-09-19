package com.Synchros.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bearer-token authentication: verifies the JWT, then loads a FRESH
 * principal from the database so suspension/role changes take effect
 * immediately (a token can authenticate, but standing is always current).
 * On any failure the chain continues unauthenticated — the entry point
 * produces the structured 401.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SynchrosUserDetailsService userDetailsService;

    public JwtAuthFilter(JwtService jwtService, SynchrosUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            JwtService.Claims claims = jwtService.verify(header.substring(7));
            if (claims != null) {
                try {
                    var details = (SynchrosUserDetails) userDetailsService.loadUserByUsername(claims.email());
                    var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (Exception e) {
                    // Unknown or suspended user inside a valid token: treat as
                    // unauthenticated (token was issued before suspension).
                    SecurityContextHolder.clearContext();
                }
            }
        }
        chain.doFilter(request, response);
    }
}
