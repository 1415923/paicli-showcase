package com.paicli.demo.order;
import java.math.BigDecimal; import java.math.RoundingMode;
public final class MoneyRules { private MoneyRules() {} public static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); } }
