package com.microwave.notifications.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

  Optional<NotificationLog> findByOrderId(Long orderId);

  Optional<NotificationLog> findByOrderIdAndType(Long orderId, NotificationType type);
}
