package com.paicli.demo.order;
import org.junit.jupiter.api.Test; import java.math.BigDecimal; import java.util.List; import static org.junit.jupiter.api.Assertions.assertEquals;
class OrderSummaryTest {
    @Test void summarizesLineCountAndTotal() { Order order = new Order(List.of(new OrderLine("book", new BigDecimal("8.50"), 2))); assertEquals(new OrderSummary(1, new BigDecimal("17.00")), OrderSummary.from(order, new PricingService())); }
}
