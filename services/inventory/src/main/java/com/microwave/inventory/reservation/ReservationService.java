package com.microwave.inventory.reservation;

import com.microwave.inventory.reservation.exceptions.InsufficientStockException;
import com.microwave.inventory.reservation.exceptions.ReservationNotFoundException;
import com.microwave.inventory.stock.Stock;
import com.microwave.inventory.stock.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ReservationService {

  private final ReservationRepository reservationRepository;
  private final StockRepository stockRepository;

  public ReservationService(ReservationRepository reservationRepository, StockRepository stockRepository) {
    this.reservationRepository = reservationRepository;
    this.stockRepository = stockRepository;
  }

  // Idempotent: a redelivered command for an orderId that's already reserved
  // returns the existing Reservation instead of decrementing Stock again.
  // @Transactional so a failure saving the Reservation rolls back the Stock
  // decrement too — otherwise a retried delivery would decrement Stock again
  // on every attempt before finally failing.
  @Transactional
  public Reservation reserve(Long orderId, Long productId, int quantity) {
    Optional<Reservation> existing = reservationRepository.findByOrderId(orderId);
    if (existing.isPresent()) {
      return existing.get();
    }

    Stock stock = stockRepository.findByProductId(productId).orElse(null);
    if (stock == null || stock.getAvailableQuantity() < quantity) {
      throw new InsufficientStockException(productId);
    }

    stock.decrease(quantity);
    stockRepository.save(stock);

    return reservationRepository.save(new Reservation(orderId, productId, quantity, ReservationStatus.RESERVED));
  }

  public Reservation findByOrderId(Long orderId) {
    return reservationRepository.findByOrderId(orderId)
        .orElseThrow(() -> new ReservationNotFoundException(orderId));
  }
}
