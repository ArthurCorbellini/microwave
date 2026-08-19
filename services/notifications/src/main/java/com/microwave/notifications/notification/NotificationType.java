package com.microwave.notifications.notification;

// Only ORDER_CREATED exists in Phase 3. Kept as an enum (not hardcoded to one
// value) so Phase 4's additional events don't require a schema change —
// see the design spec's "notifications's behavior" section.
public enum NotificationType {
  ORDER_CREATED
}
