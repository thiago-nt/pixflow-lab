package dev.pixflow.lab.payment.domain;

import org.junit.jupiter.api.Test;import java.math.BigDecimal;import static org.assertj.core.api.Assertions.*;
class MoneyTest {
 @Test void convertsBrlToMinorUnits(){assertThat(new Money(new BigDecimal("123.45"),"BRL").minorUnits()).isEqualTo(12345);}
 @Test void rejectsInvalidMoney(){assertThatThrownBy(()->new Money(BigDecimal.ZERO,"BRL")).isInstanceOf(InvalidPaymentException.class);assertThatThrownBy(()->new Money(new BigDecimal("1.001"),"BRL")).isInstanceOf(InvalidPaymentException.class);assertThatThrownBy(()->new Money(BigDecimal.ONE,"USD")).isInstanceOf(InvalidPaymentException.class);}
}
