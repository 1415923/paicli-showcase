package com.paicli.interactive.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PicocliCompatibilitySpikeTest {
    @Test void parsesExistingCommandShapesWithoutWiringMain(){
        CommandLine cli=new CommandLine(new Root());
        assertRoot(cli.parseArgs("--provider","mock","fix","rounding"),"mock",List.of("fix","rounding"));
        assertEquals("doctor",cli.parseArgs("doctor","--plain").subcommand().commandSpec().name());
        assertEquals("core",cli.parseArgs("demo","core","--headless").subcommand().subcommand().commandSpec().name());
        assertEquals("resume",cli.parseArgs("resume","task-1").subcommand().commandSpec().name());
        assertEquals("rewind",cli.parseArgs("rewind","snapshot-1").subcommand().commandSpec().name());
    }

    private static void assertRoot(CommandLine.ParseResult result,String provider,List<String> task){
        Root root=result.commandSpec().commandLine().getCommand();assertEquals(provider,root.provider);assertEquals(task,root.task);
    }

    @Command(name="paicli",subcommands={Doctor.class,Demo.class,Resume.class,Rewind.class})
    static class Root {
        @Option(names="--provider") String provider;
        @Option(names={"--verify","--verify-command"}) String verify;
        @Option(names="--max-repair-attempts") Integer repairs;
        @Option(names={"--no-plan","--no-repair","--skills","--no-skills","--hooks","--no-hooks"}) boolean flag;
        @Option(names="--hooks-config") String hooksConfig;
        @Parameters(arity="0..*") List<String> task=new ArrayList<>();
    }
    @Command(name="doctor") static class Doctor{@Option(names="--plain")boolean plain;}
    @Command(name="demo",subcommands=Core.class) static class Demo{}
    @Command(name="core") static class Core{@Option(names="--headless",required=true)boolean headless;}
    @Command(name="resume") static class Resume{@Parameters(index="0")String taskId;}
    @Command(name="rewind") static class Rewind{@Parameters(index="0")String snapshotId;}
}
