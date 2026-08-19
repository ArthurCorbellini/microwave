package com.microwave.notifications.notification;

import com.microwave.notifications.notification.enums.NotificationType;
import com.microwave.notifications.notification.exceptions.NotificationNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class NotificationService {

  private final NotificationLogRepository notificationLogRepository;

  public NotificationService(NotificationLogRepository notificationLogRepository) {
    this.notificationLogRepository = notificationLogRepository;
  }

  // Idempotent: a redelivered event for an (orderId, type) pair that's already
  // logged is a no-op, returning the existing entry instead of writing a duplicate.
  public NotificationLog recordOrderCreated(Long orderId, String message) {
    Optional<NotificationLog> existing =
        notificationLogRepository.findByOrderIdAndType(orderId, NotificationType.ORDER_CREATED);
    if (existing.isPresent()) {
      return existing.get();
    }
    return notificationLogRepository.save(new NotificationLog(orderId, NotificationType.ORDER_CREATED, message));
  }

  public NotificationLog findByOrderId(Long orderId) {
    return notificationLogRepository.findByOrderId(orderId)
        .orElseThrow(() -> new NotificationNotFoundException(orderId));
  }
}
