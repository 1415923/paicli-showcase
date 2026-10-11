package com.paicli.demo.order;
import java.math.BigDecimal;
public record OrderLine(String sku, BigDecimal unitPrice, int quantity) {
    public OrderLine { if (sku == null || sku.isBlank() || unitPrice == null || unitPrice.signum() < 0 || quantity <= 0) throw new IllegalArgumentException("invalid order line"); }
    public BigDecimal rawTotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
}
