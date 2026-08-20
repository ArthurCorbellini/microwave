package com.microwave.inventory.reservation;

import com.microwave.inventory.reservation.exceptions.InsufficientStockException;
import com.microwave.inventory.reservation.exceptions.ReservationNotFoundException;
import com.microwave.inventory.stock.Stock;
import com.microwave.inventory.stock.StockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class ReservationServiceTest {

  @Mock
  private ReservationRepository reservationRepository;

  @Mock
  private StockRepository stockRepository;

  private ReservationService reservationService;

  private void initService() {
    reservationService = new ReservationService(reservationRepository, stockRepository);
  }

  @Test
  void reservesStockWhenAvailable() {
    initService();
    when(reservationRepository.findByOrderId(42L)).thenReturn(Optional.empty());
    when(stockRepository.findByProductId(1L)).thenReturn(Optional.of(new Stock(1L, 50)));
    when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

    Reservation reservation = reservationService.reserve(42L, 1L, 5);

    assertThat(reservation.getOrderId()).isEqualTo(42L);
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);

    ArgumentCaptor<Stock> stockCaptor = ArgumentCaptor.forClass(Stock.class);
    verify(stockRepository).save(stockCaptor.capture());
    assertThat(stockCaptor.getValue().getAvailableQuantity()).isEqualTo(45);
  }

  @Test
  void throwsInsufficientStockWhenQuantityExceedsAvailable() {
    initService();
    when(reservationRepository.findByOrderId(42L)).thenReturn(Optional.empty());
    when(stockRepository.findByProductId(1L)).thenReturn(Optional.of(new Stock(1L, 2)));

    assertThatThrownBy(() -> reservationService.reserve(42L, 1L, 5))
        .isInstanceOf(InsufficientStockException.class);
    verify(reservationRepository, never()).save(any(Reservation.class));
  }

  @Test
  void throwsInsufficientStockWhenProductHasNoStockRow() {
    initService();
    when(reservationRepository.findByOrderId(42L)).thenReturn(Optional.empty());
    when(stockRepository.findByProductId(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> reservationService.reserve(42L, 1L, 5))
        .isInstanceOf(InsufficientStockException.class);
  }

  @Test
  void isIdempotentForARedeliveredCommand() {
    initService();
    Reservation existing = new Reservation(42L, 1L, 5, ReservationStatus.RESERVED);
    when(reservationRepository.findByOrderId(42L)).thenReturn(Optional.of(existing));

    Reservation result = reservationService.reserve(42L, 1L, 5);

    assertThat(result).isSameAs(existing);
    verify(stockRepository, never()).findByProductId(any());
    verify(reservationRepository, never()).save(any(Reservation.class));
  }

  @Test
  void findsReservationByOrderId() {
    initService();
    Reservation reservation = new Reservation(42L, 1L, 5, ReservationStatus.RESERVED);
    when(reservationRepository.findByOrderId(42L)).thenReturn(Optional.of(reservation));

    assertThat(reservationService.findByOrderId(42L)).isSameAs(reservation);
  }

  @Test
  void throwsReservationNotFoundWhenNoneExists() {
    initService();
    when(reservationRepository.findByOrderId(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> reservationService.findByOrderId(99L))
        .isInstanceOf(ReservationNotFoundException.class);
  }
}
