package com.flashreserve.security;

import com.flashreserve.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class FlashUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public FlashUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user: " + email));
        return new FlashUserDetails(user.getId(), user.getEmail(),
                user.getPasswordHash(), user.getRole());
    }
}
