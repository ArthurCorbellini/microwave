package com.microwave.notifications.notification;

import com.microwave.notifications.notification.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "notification_logs", uniqueConstraints = @UniqueConstraint(columnNames = {"orderId", "type"}))
public class NotificationLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long orderId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private NotificationType type;

  @Column(nullable = false)
  private String message;

  @Column(nullable = false)
  private Instant sentAt;

  protected NotificationLog() {
  }

  public NotificationLog(Long orderId, NotificationType type, String message) {
    this.orderId = orderId;
    this.type = type;
    this.message = message;
    this.sentAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public Long getOrderId() {
    return orderId;
  }

  public NotificationType getType() {
    return type;
  }

  public String getMessage() {
    return message;
  }

  public Instant getSentAt() {
    return sentAt;
  }
}
