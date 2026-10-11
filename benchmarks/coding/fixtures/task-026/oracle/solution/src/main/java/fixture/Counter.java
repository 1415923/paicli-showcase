package fixture;
public record Counter(int value) { public Counter increment(){return new Counter(value+1);} public Counter decrement(){return new Counter(value-1);} }
