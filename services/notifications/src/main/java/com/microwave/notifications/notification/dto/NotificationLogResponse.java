package com.microwave.notifications.notification.dto;

import com.microwave.notifications.notification.NotificationLog;
import com.microwave.notifications.notification.enums.NotificationType;

import java.time.Instant;

public record NotificationLogResponse(Long orderId, NotificationType type, String message, Instant sentAt) {

  public static NotificationLogResponse from(NotificationLog notificationLog) {
    return new NotificationLogResponse(
        notificationLog.getOrderId(), notificationLog.getType(),
        notificationLog.getMessage(), notificationLog.getSentAt());
  }
}
