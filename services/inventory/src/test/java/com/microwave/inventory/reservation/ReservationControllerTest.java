package com.microwave.inventory.reservation;

import com.microwave.inventory.reservation.enums.ReservationStatus;
import com.microwave.inventory.reservation.exceptions.ReservationNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ReservationService reservationService;

  @Test
  void getsReservationByOrderId() throws Exception {
    Reservation reservation = new Reservation(42L, 1L, 5, ReservationStatus.RESERVED);
    when(reservationService.findByOrderId(42L)).thenReturn(reservation);

    mockMvc.perform(get("/inventory/reservations/42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value(42))
        .andExpect(jsonPath("$.productId").value(1))
        .andExpect(jsonPath("$.quantity").value(5))
        .andExpect(jsonPath("$.status").value("RESERVED"));
  }

  @Test
  void returnsNotFoundForMissingReservation() throws Exception {
    when(reservationService.findByOrderId(99L)).thenThrow(new ReservationNotFoundException(99L));

    mockMvc.perform(get("/inventory/reservations/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.detail").value("Reservation not found for order: 99"))
        .andExpect(jsonPath("$.instance").value("/inventory/reservations/99"));
  }
}
