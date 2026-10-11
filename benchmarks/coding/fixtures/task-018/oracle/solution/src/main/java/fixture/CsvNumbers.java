package fixture;
public final class CsvNumbers { public int sum(String line){if(line==null||line.isBlank())return 0;int total=0;for(String value:line.split(",")){total+=Integer.parseInt(value.trim());}return total;} }
