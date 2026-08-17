package com.microwave.notifications.notification.exceptions;

public class NotificationNotFoundException extends RuntimeException {

  public NotificationNotFoundException(Long orderId) {
    super("Notification not found for order: " + orderId);
  }
}
