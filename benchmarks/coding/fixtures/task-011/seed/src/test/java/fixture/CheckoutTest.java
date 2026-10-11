package fixture;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class CheckoutTest { @Test void membershipDiscount() { Checkout c=new Checkout(new StandardDiscountPolicy()); assertEquals(900,c.total(1000,true)); assertEquals(1000,c.total(1000,false)); assertEquals(0,c.total(0,true)); } }
