package com.microwave.inventory.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ReservationRepositoryIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired
  private ReservationRepository reservationRepository;

  @Test
  void savesAndFindsReservationByOrderId() {
    reservationRepository.save(new Reservation(42L, 1L, 2, ReservationStatus.RESERVED));

    Optional<Reservation> found = reservationRepository.findByOrderId(42L);

    assertThat(found).isPresent();
    assertThat(found.get().getProductId()).isEqualTo(1L);
    assertThat(found.get().getQuantity()).isEqualTo(2);
    assertThat(found.get().getStatus()).isEqualTo(ReservationStatus.RESERVED);
  }

  @Test
  void returnsEmptyWhenOrderHasNoReservation() {
    Optional<Reservation> found = reservationRepository.findByOrderId(999L);

    assertThat(found).isEmpty();
  }
}
