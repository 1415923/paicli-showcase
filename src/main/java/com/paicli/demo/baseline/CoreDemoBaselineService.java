package com.paicli.demo.baseline;

import com.paicli.demo.fixture.CoreDemoFixtureException;
import com.paicli.demo.fixture.CoreDemoFixtureManifest;
import com.paicli.demo.fixture.CoreDemoTreeHasher;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class CoreDemoBaselineService {
    public static final String COMMAND_ID = "MAVEN_OFFLINE_TEST_V1";
    private static final int MAX_CAPTURE = 65_536;
    private final Duration timeout;
    private final ProcessLauncher launcher;

    public CoreDemoBaselineService(Duration timeout) {
        this(timeout, CoreDemoBaselineService::startMaven);
    }

    CoreDemoBaselineService(Duration timeout, ProcessLauncher launcher) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Invalid baseline timeout");
        this.timeout = timeout; this.launcher = Objects.requireNonNull(launcher);
    }

    public CoreDemoBaselineResult verify(Path workspace, CoreDemoFixtureManifest manifest) {
        long started = System.nanoTime();
        String before;
        try {
            if (!COMMAND_ID.equals(manifest.baselineCommandId())) throw new CoreDemoFixtureException("Unsupported baseline command");
            before = CoreDemoTreeHasher.hashWorkspace(workspace, manifest).aggregateHash();
            if (!before.equals(manifest.expectedBaselineTreeHash()))
                return result(CoreDemoBaselineResult.Status.DEMO_BASELINE_INVALID, null, started, Counts.ZERO,
                        Capture.EMPTY, Capture.EMPTY, false, true, true, before);
        } catch (Exception e) {
            return error(started, e);
        }

        Process process;
        List<String> diagnosticCommand = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("windows")
                ? List.of(System.getenv().getOrDefault("ComSpec", "cmd.exe"),
                        "/d", "/s", "/c", "mvn.cmd", "-o", "-B", "-ntp",
                        "-Dstyle.color=never", "test")
                : List.of("mvn", "-o", "-B", "-ntp", "-Dstyle.color=never", "test");
        com.paicli.diagnostic.StageTrace.event(
                "CHILD_PROCESS_CREATE_START", false,
                "purpose", "CORE_DEMO_BASELINE",
                "executable_basename",
                com.paicli.diagnostic.StageTrace.executableBasename(diagnosticCommand),
                "argument_count", diagnosticCommand.size() - 1,
                "argument_hash",
                com.paicli.diagnostic.StageTrace.hashArguments(diagnosticCommand));
        try {
            process = launcher.start(workspace.toRealPath());
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_END", false,
                    "purpose", "CORE_DEMO_BASELINE",
                    "outcome", "STARTED",
                    "pid", com.paicli.diagnostic.StageTrace.processId(process));
        } catch (Exception e) {
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_END", false,
                    "purpose", "CORE_DEMO_BASELINE",
                    "outcome", "ERROR",
                    "error_type", e.getClass().getName());
            return error(started, e);
        }
        AtomicReference<Capture> out = new AtomicReference<>(Capture.EMPTY);
        AtomicReference<Capture> err = new AtomicReference<>(Capture.EMPTY);
        Thread outReader = Thread.ofVirtual().name("core-demo-baseline-stdout").start(() -> out.set(capture(process.getInputStream())));
        Thread errReader = Thread.ofVirtual().name("core-demo-baseline-stderr").start(() -> err.set(capture(process.getErrorStream())));
        boolean timedOut = false; Integer exit = null; Counts counts = Counts.ZERO; boolean cleanup = false; boolean processCleaned = false;
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true; terminate(process); process.waitFor(5, TimeUnit.SECONDS);
            }
            outReader.join(2000); errReader.join(2000);
            processCleaned = !process.isAlive();
            if (!timedOut) exit = process.exitValue();
            if (!timedOut && exit == 0) counts = parseSurefire(workspace);
            cleanup = cleanupTargets(workspace);
            String after = CoreDemoTreeHasher.hashWorkspace(workspace, manifest).aggregateHash();
            boolean valid = !timedOut && exit != null && exit == 0 && counts.tests == manifest.expectedBaselineTestCount()
                    && counts.failures == 0 && counts.errors == 0 && counts.skipped == 0
                    && before.equals(after) && cleanup && out.get().error == null && err.get().error == null;
            CoreDemoBaselineResult.Status status = !cleanup
                    ? CoreDemoBaselineResult.Status.BASELINE_CLEANUP_FAILED
                    : valid ? CoreDemoBaselineResult.Status.BASELINE_PASSED
                    : CoreDemoBaselineResult.Status.DEMO_BASELINE_INVALID;
            return result(status, exit, started, counts, out.get(), err.get(), timedOut, processCleaned, cleanup, after);
        } catch (Exception e) {
            terminate(process);
            try { outReader.join(1000); errReader.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            try { cleanup = cleanupTargets(workspace); } catch (Exception ignored) { cleanup = false; }
            return result(CoreDemoBaselineResult.Status.BASELINE_ERROR, exit, started, counts,
                    out.get(), err.get(), timedOut, !process.isAlive(), cleanup, before);
        }
    }

    private static Process startMaven(Path workspace) throws IOException {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows") ? "mvn.cmd" : "mvn";
        List<String> command = List.of(executable, "-o", "-B", "-ntp", "-Dstyle.color=never", "test");
        ProcessBuilder builder;
        if (executable.endsWith(".cmd")) {
            String comspec = System.getenv().getOrDefault("ComSpec", "cmd.exe");
            builder = new ProcessBuilder(comspec, "/d", "/s", "/c", executable,
                    "-o", "-B", "-ntp", "-Dstyle.color=never", "test");
        } else builder = new ProcessBuilder(command);
        builder.directory(workspace.toFile()).redirectErrorStream(false);
        builder.environment().put("MAVEN_OPTS", "-Dstyle.color=never");
        return builder.start();
    }

    private static Counts parseSurefire(Path workspace) throws Exception {
        Path reports = workspace.resolve("target/surefire-reports");
        if (!Files.isDirectory(reports)) throw new IOException("Surefire reports missing");
        List<Path> xml;
        try (Stream<Path> paths = Files.list(reports)) {
            xml = paths.filter(p -> p.getFileName().toString().startsWith("TEST-"))
                    .filter(p -> p.getFileName().toString().endsWith(".xml")).sorted().toList();
        }
        if (xml.isEmpty()) throw new IOException("Surefire reports missing");
        int tests=0, failures=0, errors=0, skipped=0;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        for (Path path : xml) {
            var root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
            if (!"testsuite".equals(root.getTagName())) throw new IOException("Malformed Surefire report");
            tests += integer(root, "tests"); failures += integer(root, "failures");
            errors += integer(root, "errors"); skipped += integer(root, "skipped");
        }
        return new Counts(tests, failures, errors, skipped);
    }

    private static int integer(org.w3c.dom.Element element, String name) throws IOException {
        String value = element.getAttribute(name);
        if (!value.matches("[0-9]+")) throw new IOException("Malformed Surefire count");
        return Integer.parseInt(value);
    }

    static boolean cleanupTargets(Path workspace) throws IOException {
        Path target = workspace.toRealPath().resolve("target");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return true;
        if (Files.isSymbolicLink(target) || !target.getParent().equals(workspace.toRealPath()))
            throw new IOException("Unsafe baseline target");
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) throw new IOException("Linked target residue");
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
        return !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    }

    private static Capture capture(InputStream stream) {
        try (stream) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); ByteArrayOutputStream kept=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192]; long seen=0; int read;
            while((read=stream.read(buffer))!=-1) { digest.update(buffer,0,read); seen+=read;
                if(kept.size()<MAX_CAPTURE) kept.write(buffer,0,Math.min(read,MAX_CAPTURE-kept.size())); }
            return new Capture(HexFormat.of().formatHex(digest.digest()), sanitize(kept.toString(StandardCharsets.UTF_8), seen>kept.size()), null);
        } catch(Exception e) { return new Capture("", "", e); }
    }

    private static String sanitize(String value, boolean truncated) {
        String text=value.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]","").replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]","");
        text=text.replaceAll("(?i)(bearer|token|password|api[_-]?key)\\s*[:=]\\s*\\S+","$1=<redacted>");
        if(text.length()>8192) text=text.substring(0,8192);
        return text + (truncated ? "\n...[truncated]" : "");
    }
    private static void terminate(Process process) {
        process.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if(process.isAlive()) process.destroyForcibly();
    }
    private static CoreDemoBaselineResult error(long started, Exception e) {
        String excerpt=sanitize(e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),""),false);
        return new CoreDemoBaselineResult(CoreDemoBaselineResult.Status.BASELINE_ERROR,null,elapsed(started),
                0,0,0,0,"","",excerpt,false,true,true,"");
    }
    private static CoreDemoBaselineResult result(CoreDemoBaselineResult.Status status,Integer exit,long started,Counts c,Capture out,Capture err,boolean timeout,boolean processCleaned,boolean buildCleaned,String hash) {
        String excerpt=sanitize(out.excerpt+"\n"+err.excerpt,false);
        return new CoreDemoBaselineResult(status,exit,elapsed(started),c.tests,c.failures,c.errors,c.skipped,
                out.hash,err.hash,excerpt,timeout,processCleaned,buildCleaned,hash);
    }
    private static long elapsed(long started){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);}
    @FunctionalInterface interface ProcessLauncher { Process start(Path workspace) throws IOException; }
    private record Counts(int tests,int failures,int errors,int skipped){static final Counts ZERO=new Counts(0,0,0,0);}
    private record Capture(String hash,String excerpt,Exception error){static final Capture EMPTY=new Capture("","",null);}
}
