package com.banamex.transferlimits;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the MS-TRANSFER-LIMITS microservice. */
@SpringBootApplication
public class MsTransferLimitsApplication {

  /**
   * Boots the Spring application context.
   *
   * @param args command line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(MsTransferLimitsApplication.class, args);
  }
}
