package com.microwave.notifications.notification;

import java.time.Instant;

public record NotificationLogResponse(Long orderId, NotificationType type, String message, Instant sentAt) {

  public static NotificationLogResponse from(NotificationLog notificationLog) {
    return new NotificationLogResponse(
        notificationLog.getOrderId(), notificationLog.getType(),
        notificationLog.getMessage(), notificationLog.getSentAt());
  }
}
