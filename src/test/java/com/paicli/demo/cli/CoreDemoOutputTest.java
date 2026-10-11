package com.paicli.demo.cli;

import com.paicli.demo.runtime.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoOutputTest {
    @Test void outputIsBoundedMachineReadableAndPathSafe(){
        var result=new CoreDemoRunResult("run-00000021","core-java-member-discount-v1",
                "COMPLETED",CoreDemoExitCode.COMPLETED,
                "~/.paicli/demo-runs/run-00000021/artifacts/final",false,null,null);
        var out=new StringWriter();var err=new StringWriter();
        new CoreDemoOutput().write(result,new PrintWriter(out),new PrintWriter(err));
        assertTrue(out.toString().contains("PAICLI_DEMO_RESULT="));
        assertTrue(out.toString().contains("\"exitCode\":0"));
        assertFalse(out.toString().contains(rootLike()));
        assertEquals("",err.toString());
    }
    @Test void absoluteArtifactLocationIsRejected(){
        assertThrows(IllegalArgumentException.class,()->new CoreDemoRunResult(
                "run-00000022","scenario","FAILED",CoreDemoExitCode.INTERNAL_ERROR,
                "C:\\Users\\secret",true,"ERROR","x"));
    }
    private static String rootLike(){return System.getProperty("user.home","unlikely");}
}
