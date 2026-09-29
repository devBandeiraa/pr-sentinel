package dev.bandeira.prsentinel.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeliveryIdTest {

  @Test
  void aceitaValorValidoEExpoeOValor() {
    DeliveryId id = new DeliveryId("72d3162e-cc78-11e3-81ab-4c9367dc0958");

    assertThat(id.value()).isEqualTo("72d3162e-cc78-11e3-81ab-4c9367dc0958");
  }

  @Test
  void aceitaValorNoLimiteDe64Caracteres() {
    String value = "a".repeat(64);

    DeliveryId id = new DeliveryId(value);

    assertThat(id.value()).hasSize(64);
  }

  @Test
  void rejeitaValorNulo() {
    assertThatThrownBy(() -> new DeliveryId(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejeitaValorVazio() {
    assertThatThrownBy(() -> new DeliveryId("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejeitaValorEmBranco() {
    assertThatThrownBy(() -> new DeliveryId("   ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejeitaValorComMaisDe64Caracteres() {
    String value = "a".repeat(65);

    assertThatThrownBy(() -> new DeliveryId(value)).isInstanceOf(IllegalArgumentException.class);
  }
}
