package com.synchros.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** Principal carrying the numeric DB user id (used for ownership checks). */
public class SynchrosUserDetails implements UserDetails {

    private final Long userId;
    private final String email;
    private final String passwordHash;
    private final String role;

    public SynchrosUserDetails(Long userId, String email, String passwordHash, String role) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
