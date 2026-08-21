package com.microwave.notifications.notification;

// Only ORDER_CREATED exists today. Kept as an enum, not a hardcoded string,
// so future notification types don't require a schema change.
public enum NotificationType {
  ORDER_CREATED
}
