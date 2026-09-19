package com.synchros.user;

import com.synchros.security.CurrentUser;
import com.synchros.security.SynchrosUserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final com.synchros.notification.NotificationService notificationService;

    public UserController(com.synchros.notification.NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Current user's profile + notifications (ownership implicit via auth). */
    public record MeResponse(String id, String email, String role) {
    }

    @GetMapping("/me")
    public MeResponse me(@CurrentUser SynchrosUserDetails user) {
        return new MeResponse(String.valueOf(user.getUserId()), user.getUsername(),
                user.getAuthorities().iterator().next().getAuthority());
    }

    @GetMapping("/me/notifications")
    public List<?> notifications(@CurrentUser SynchrosUserDetails user) {
        return notificationService.latestForUser(user.getUserId());
    }
}
