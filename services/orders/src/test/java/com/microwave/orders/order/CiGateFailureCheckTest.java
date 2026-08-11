package com.microwave.orders.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class CiGateFailureCheckTest {

  @Test
  void alwaysFails() {
    fail("intentional failure to verify the CI gate blocks a red PR");
  }
}
