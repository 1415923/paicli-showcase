package com.paicli.demo.order;
import org.junit.jupiter.api.Test; import java.lang.reflect.Modifier; import java.math.BigDecimal; import java.util.Arrays; import java.util.List; import static org.junit.jupiter.api.Assertions.*;
class PricingServiceTest {
    private final PricingService pricing = new PricingService();
    @Test void memberDiscountUsesRoundedSubtotal() throws Exception {
        Order order = new Order(List.of(new OrderLine("a", new BigDecimal("0.025"), 1), new OrderLine("b", new BigDecimal("0.025"), 1)));
        assertEquals(new BigDecimal("0.05"), pricing.subtotal(order));
        var named = Arrays.stream(PricingService.class.getDeclaredMethods()).filter(method -> method.getName().equals("memberTotal")).toList();
        if (named.isEmpty()) return;
        assertEquals(1, named.size(), "memberTotal must have one exact public signature");
        var method = named.getFirst();
        assertArrayEquals(new Class<?>[]{Order.class}, method.getParameterTypes());
        assertEquals(BigDecimal.class, method.getReturnType());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(new BigDecimal("0.05"), method.invoke(pricing, order));
    }
    @Test void emptyOrderIsZeroMoney() { assertEquals(new BigDecimal("0.00"), pricing.subtotal(new Order(List.of()))); }
}
