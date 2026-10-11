package com.paicli.tool;

import com.paicli.model.ToolCall;
import com.paicli.safety.SafetyPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceLinkSafetyIT {
    @TempDir Path temp;
    Path workspace;
    Path outside;
    ReadFileTool read;
    ListFilesTool list;
    GrepTool grep;
    final List<Path> createdLinks=new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        workspace=Files.createDirectory(temp.resolve("workspace"));
        outside=Files.createDirectory(temp.resolve("outside"));
        SafetyPolicy policy=new SafetyPolicy(workspace);
        read=new ReadFileTool(policy);list=new ListFilesTool(policy);grep=new GrepTool(policy);
    }

    @AfterEach
    void removeCreatedLinks() throws Exception {
        for (int i=createdLinks.size()-1;i>=0;i--) Files.deleteIfExists(createdLinks.get(i));
    }
    @Test
    void readAndGrepRejectFileSymlinkEscapeWhenSupported() throws Exception {
        Path secret=outside.resolve("secret.txt");Files.writeString(secret,"outside-secret");
        Path link=workspace.resolve("linked-secret.txt");createSymbolicLinkOrSkip(link,secret);

        var readResult=read.execute(new ToolCall("read_file",Map.of("path","linked-secret.txt")));
        var grepResult=grep.execute(new ToolCall("grep",Map.of("pattern","outside-secret","path","linked-secret.txt")));
        assertFalse(readResult.success());assertFalse(grepResult.success());
        assertFalse(String.valueOf(readResult.output()).contains("outside-secret"));
        assertFalse(String.valueOf(grepResult.output()).contains("outside-secret"));
    }

    @Test
    void listAndGrepRejectDirectoryLinkEscapeWhenSupported() throws Exception {
        Files.writeString(outside.resolve("secret.txt"),"outside-secret");
        Path link=workspace.resolve("linked-dir");createDirectoryLinkOrSkip(link,outside);

        assertFalse(list.execute(new ToolCall("list_files",Map.of("path","linked-dir"))).success());
        assertFalse(grep.execute(new ToolCall("grep",Map.of("pattern","outside-secret","path","linked-dir"))).success());
    }

    @Test
    void linkNestedInsideOtherwiseValidPathIsRejectedWhenSupported() throws Exception {
        Path parent=Files.createDirectory(workspace.resolve("safe"));
        Path link=parent.resolve("escape");createDirectoryLinkOrSkip(link,outside);
        Files.writeString(outside.resolve("nested.txt"),"outside-secret");

        assertFalse(read.execute(new ToolCall("read_file",Map.of("path","safe/escape/nested.txt"))).success());
    }

    private void createSymbolicLinkOrSkip(Path link,Path target){
        try{Files.createSymbolicLink(link,target);createdLinks.add(link);}catch(Exception e){Assumptions.assumeTrue(false,"file symlink unavailable: "+e.getClass().getSimpleName());}
    }

    private void createDirectoryLinkOrSkip(Path link,Path target) throws Exception {
        if(!isWindows()){createSymbolicLinkOrSkip(link,target);return;}
        Process process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),target.toString())
                .redirectErrorStream(true).start();
        byte[] output=process.getInputStream().readAllBytes();
        boolean finished=process.waitFor(10,TimeUnit.SECONDS);
        Assumptions.assumeTrue(finished&&process.exitValue()==0,"junction unavailable: "+new String(output));
        createdLinks.add(link);
    }

    private boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows");}
}
