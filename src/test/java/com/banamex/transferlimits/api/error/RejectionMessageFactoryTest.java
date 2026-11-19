package com.banamex.transferlimits.api.error;

import static com.banamex.transferlimits.api.ApiTestData.TRANSFER_ID;
import static com.banamex.transferlimits.api.ApiTestData.amount;
import static com.banamex.transferlimits.api.ApiTestData.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.RejectionReason;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RejectionMessageFactoryTest {

  private static final String GENERIC_INVALID_LIMIT =
      "La configuración de límites del canal WEB es inválida: los límites deben ser no negativos,"
          + " con máximo 2 decimales, y el límite por operación no puede ser mayor que el diario.";

  private final RejectionMessageFactory factory = new RejectionMessageFactory();

  @Test
  void perOperationRejectionUsesTheTemplateWithEsMxAmounts() {
    assertThat(
            factory.rejection(
                rejected(RejectionReason.PER_OPERATION_LIMIT_EXCEEDED, "25000.00", "0.00", false)))
        .isEqualTo(
            "El monto 25,000.00 MXN excede el límite por operación de 20,000.00 MXN para el canal"
                + " MOBILE_APP.");
  }

  @Test
  void dailyRejectionShowsAvailableLimitAndConsumed() {
    assertThat(
            factory.rejection(
                rejected(RejectionReason.DAILY_LIMIT_EXCEEDED, "15000.00", "41500.00", false)))
        .isEqualTo(
            "El monto 15,000.00 MXN excede el disponible diario de 8,500.00 MXN (límite 50,000.00,"
                + " consumido 41,500.00) para el canal MOBILE_APP.");
  }

  @Test
  void formatsAmountsWithGroupingAndTwoDecimals() {
    assertThat(RejectionMessageFactory.format(new BigDecimal("1234567.5")))
        .isEqualTo("1,234,567.50");
    assertThat(RejectionMessageFactory.format(BigDecimal.ZERO)).isEqualTo("0.00");
  }

  @Test
  void otherProblemDetails() {
    assertThat(factory.validationError()).isEqualTo("La solicitud contiene campos inválidos.");
    assertThat(factory.accountLimitsNotFound("0012345678"))
        .isEqualTo("La cuenta 0012345678 no tiene límites configurados.");
    assertThat(factory.limitNotFound("0012345678", Channel.BRANCH))
        .isEqualTo("La cuenta 0012345678 no tiene límite configurado para el canal BRANCH.");
    assertThat(factory.transferIdConflict(TRANSFER_ID))
        .isEqualTo(
            "El transferId 3f1c2b9e-7a4d-4c1e-9b8a-2d6f5e4c3b2a ya fue utilizado con datos"
                + " distintos.");
    assertThat(factory.concurrentUpdate())
        .isEqualTo(
            "El recurso fue modificado por otra solicitud concurrente; vuelva a intentarlo.");
    assertThat(factory.internalError())
        .isEqualTo("Ocurrió un error interno inesperado. Use el traceId para reportarlo.");
  }

  @Test
  void invalidLimitConfigurationShowsBothLimitsWhenPerOperationExceedsDaily() {
    assertThat(
            factory.invalidLimitConfiguration(
                Channel.MOBILE_APP, amount("50000.00"), amount("60000.00"), "MXN"))
        .isEqualTo(
            "El límite por operación (60,000.00 MXN) no puede ser mayor que el límite diario"
                + " (50,000.00 MXN).");
  }

  @Test
  void invalidLimitConfigurationIsGenericForOtherViolations() {
    assertThat(factory.invalidLimitConfiguration(Channel.WEB, null, amount("1"), "MXN"))
        .isEqualTo(GENERIC_INVALID_LIMIT);
    assertThat(factory.invalidLimitConfiguration(Channel.WEB, amount("1"), null, "MXN"))
        .isEqualTo(GENERIC_INVALID_LIMIT);
    assertThat(factory.invalidLimitConfiguration(Channel.WEB, amount("-1"), amount("-2"), "MXN"))
        .isEqualTo(GENERIC_INVALID_LIMIT);
  }

  @Test
  void clientValuesAreNeverExpandedAsTemplates() {
    assertThat(factory.accountLimitsNotFound("${accountId}"))
        .isEqualTo("La cuenta ${accountId} no tiene límites configurados.");
  }

  @Test
  void failsFastWhenTheTemplatesFileIsMissing() {
    assertThatThrownBy(() -> new RejectionMessageFactory("messages/does-not-exist.properties"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot load message templates");
  }

  @Test
  void failsFastWhenSomeTemplateIsMissingOrBlank() {
    assertThatThrownBy(
            () -> new RejectionMessageFactory("messages/incomplete-rejections.properties"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Missing message templates")
        .hasMessageContaining("VALIDATION_ERROR")
        .hasMessageContaining("DAILY_LIMIT_EXCEEDED")
        .hasMessageNotContaining("PER_OPERATION_LIMIT_EXCEEDED");
  }
}
