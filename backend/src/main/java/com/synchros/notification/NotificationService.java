package com.Synchros.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /** In a real system this fans out to email/push; here it persists an
     *  in-app notification (the eventual-consistency demo artifact). */
    @Transactional
    public void notify(Long userId, String kind, String body) {
        notificationRepository.save(new Notification(userId, kind, body));
        log.info("notification stored user={} kind={}", userId, kind);
    }

    public List<Notification> latestForUser(Long userId) {
        return notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }
}
