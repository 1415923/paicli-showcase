package fixture;
import java.util.*;
public final class TinyStack<T> { private final List<T> values=new ArrayList<>(); public void push(T value){values.add(value);} public T pop(){if(values.isEmpty())throw new NoSuchElementException();return values.removeFirst();} }
