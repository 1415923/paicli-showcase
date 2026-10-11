package fixture;
public final class CsvNumbers { public int sum(String line){int total=0;for(String value:line.split(",")){total+=Integer.parseInt(value);}return total;} }
