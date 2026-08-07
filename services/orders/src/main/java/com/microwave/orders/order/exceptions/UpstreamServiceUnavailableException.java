package com.microwave.orders.order.exceptions;

public class UpstreamServiceUnavailableException extends RuntimeException {

  public UpstreamServiceUnavailableException(String serviceName, Throwable cause) {
    super(serviceName + " service is unavailable", cause);
  }
}
