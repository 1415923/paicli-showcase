package com.paicli.demo.order;
import java.math.BigDecimal;
public class PricingService {
    public BigDecimal subtotal(Order order) {
        BigDecimal raw = order.lines().stream().map(OrderLine::rawTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return MoneyRules.money(raw);
    }
}
