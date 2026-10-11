import java.math.*;
import java.util.Random;
class Acceptance {
  static int count;
  static void check(int cents,int percent) {
    BigDecimal amount=BigDecimal.valueOf(cents);
    BigDecimal retained=BigDecimal.ONE.subtract(BigDecimal.valueOf(percent).movePointLeft(2));
    int expected=amount.multiply(retained).setScale(0,RoundingMode.FLOOR).intValueExact();
    int actual=Discount.afterDiscount(cents,percent);
    if(actual!=expected)throw new AssertionError(cents+"/"+percent+" expected "+expected+" got "+actual);
    count++;
  }
  public static void main(String[] args) {
    for(int cents:new int[]{0,1,2,99,100,101,199,999999,1000000})for(int p=0;p<=100;p++)check(cents,p);
    Random random=new Random(2047);for(int i=0;i<2000;i++)check(random.nextInt(1000001),random.nextInt(101));
    for(int[] pair:new int[][]{{-1,10},{1000001,10},{100,-1},{100,101}}){
      try {Discount.afterDiscount(pair[0],pair[1]);throw new AssertionError("invalid input accepted");}
      catch(IllegalArgumentException expected){count++;}
    }
    System.out.println("INDEPENDENT_DISCOUNT_PASS cases="+count);
  }
}
