package com.paicli.demo.order;
import java.util.List;
public record Order(List<OrderLine> lines) { public Order { lines = lines == null ? List.of() : List.copyOf(lines); } }
