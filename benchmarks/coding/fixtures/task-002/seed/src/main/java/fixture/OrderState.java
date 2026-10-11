package fixture;

import java.util.Set;

public enum OrderState {
    CREATED, PAID, SHIPPED, CANCELLED;

    public Set<OrderState> next() {
        return switch (this) {
            case CREATED -> Set.of(PAID, CANCELLED);
            case PAID -> Set.of(SHIPPED, CANCELLED);
            case SHIPPED, CANCELLED -> Set.of();
        };
    }
}
