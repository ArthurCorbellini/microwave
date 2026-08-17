package com.microwave.notifications.notification;

import com.microwave.notifications.notification.enums.NotificationType;
import com.microwave.notifications.notification.exceptions.NotificationNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  @Mock
  private NotificationLogRepository notificationLogRepository;

  private NotificationService notificationService;

  private void initService() {
    notificationService = new NotificationService(notificationLogRepository);
  }

  @Test
  void recordsANewNotification() {
    initService();
    when(notificationLogRepository.findByOrderIdAndType(42L, NotificationType.ORDER_CREATED))
        .thenReturn(Optional.empty());
    when(notificationLogRepository.save(any(NotificationLog.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    NotificationLog result = notificationService.recordOrderCreated(42L, "Order #42 created");

    assertThat(result.getOrderId()).isEqualTo(42L);
    assertThat(result.getMessage()).isEqualTo("Order #42 created");
  }

  @Test
  void isIdempotentForARedeliveredEvent() {
    initService();
    NotificationLog existing = new NotificationLog(42L, NotificationType.ORDER_CREATED, "Order #42 created");
    when(notificationLogRepository.findByOrderIdAndType(42L, NotificationType.ORDER_CREATED))
        .thenReturn(Optional.of(existing));

    NotificationLog result = notificationService.recordOrderCreated(42L, "Order #42 created");

    assertThat(result).isSameAs(existing);
    verify(notificationLogRepository, never()).save(any(NotificationLog.class));
  }

  @Test
  void findsNotificationByOrderId() {
    initService();
    NotificationLog log = new NotificationLog(42L, NotificationType.ORDER_CREATED, "Order #42 created");
    when(notificationLogRepository.findByOrderId(42L)).thenReturn(Optional.of(log));

    assertThat(notificationService.findByOrderId(42L)).isSameAs(log);
  }

  @Test
  void throwsNotificationNotFoundWhenNoneExists() {
    initService();
    when(notificationLogRepository.findByOrderId(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> notificationService.findByOrderId(99L))
        .isInstanceOf(NotificationNotFoundException.class);
  }
}
