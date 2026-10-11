package com.paicli.demo.order;
import java.math.BigDecimal;
public record OrderSummary(int lineCount, BigDecimal total) {
    public static OrderSummary from(Order order, PricingService pricing) { return new OrderSummary(order.lines().size(), pricing.subtotal(order)); }
}
