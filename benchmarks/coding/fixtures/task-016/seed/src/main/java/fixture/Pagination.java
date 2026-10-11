package fixture;
public final class Pagination { public int pageCount(int items,int pageSize){ if(items==0)return 0; return items/pageSize+1; } }
