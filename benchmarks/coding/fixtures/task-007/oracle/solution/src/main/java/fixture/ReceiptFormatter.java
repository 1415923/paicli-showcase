package fixture;
import java.math.BigDecimal;
public final class ReceiptFormatter { public String dollars(long cents) { String amount=BigDecimal.valueOf(cents,2).toPlainString(); return amount.startsWith("-") ? "-$"+amount.substring(1) : "$"+amount; } }
