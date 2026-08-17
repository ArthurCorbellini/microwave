package com.microwave.notifications.notification;

import com.microwave.notifications.notification.enums.NotificationType;
import com.microwave.notifications.notification.exceptions.NotificationNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private NotificationService notificationService;

  @Test
  void getsNotificationByOrderId() throws Exception {
    NotificationLog log = new NotificationLog(42L, NotificationType.ORDER_CREATED, "Order #42 created");
    when(notificationService.findByOrderId(42L)).thenReturn(log);

    mockMvc.perform(get("/notifications/42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value(42))
        .andExpect(jsonPath("$.type").value("ORDER_CREATED"))
        .andExpect(jsonPath("$.message").value("Order #42 created"));
  }

  @Test
  void returnsNotFoundForMissingNotification() throws Exception {
    when(notificationService.findByOrderId(99L)).thenThrow(new NotificationNotFoundException(99L));

    mockMvc.perform(get("/notifications/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.detail").value("Notification not found for order: 99"))
        .andExpect(jsonPath("$.instance").value("/notifications/99"));
  }
}
