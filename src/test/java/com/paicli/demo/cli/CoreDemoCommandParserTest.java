package com.paicli.demo.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoCommandParserTest {
    private final CoreDemoCommandParser parser=new CoreDemoCommandParser();
    @Test void acceptsOnlyExactFixedCommand(){
        assertTrue(parser.parse(new String[]{"demo","core","--headless"}).headless());
        for(String[] args:new String[][]{
                {},{"demo","core"},{"demo","core","--headless","extra"},
                {"demo","core","--provider","mock"},{"demo","core","--verify","mvn test"},
                {"demo","core","--workspace","x"},{"demo","core","--fixture","x"},
                {"demo","core","--approve-all"},{"demo","core","--no-snapshot"}}){
            var error=assertThrows(CoreDemoCommandParser.CoreDemoUsageException.class,
                    ()->parser.parse(args));assertEquals(2,error.exitCode());
        }
    }

    @Test void parserDoesNotMutateInput(){
        String[] args={"demo","core","--headless"};
        parser.parse(args);assertArrayEquals(new String[]{"demo","core","--headless"},args);
    }
}
