package com.paicli.demo.artifact;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class CoreDemoArtifactWriter implements CoreDemoArtifactSink {
    private static final Set<String> PAYLOADS=Set.of(
            "summary.json","report.md","hashes.json","verification/summary.json");
    private static final int MAX_FILE_BYTES=256*1024,MAX_BUNDLE_BYTES=512*1024;
    private final CoreDemoArtifactRenderer renderer=new CoreDemoArtifactRenderer();
    private final CoreDemoSemanticHasher semanticHasher=new CoreDemoSemanticHasher();
    private final ObjectMapper json=new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final FailureHook failureHook;
    private final boolean preferAtomicMove;

    public CoreDemoArtifactWriter(){this(point->{},true);}
    CoreDemoArtifactWriter(FailureHook failureHook,boolean preferAtomicMove){
        this.failureHook=Objects.requireNonNull(failureHook);this.preferAtomicMove=preferAtomicMove;
    }

    @Override public CoreDemoArtifactCommit write(Path artifactsRoot,CoreDemoArtifactInput input)
            throws IOException{
        Objects.requireNonNull(artifactsRoot);Objects.requireNonNull(input);
        Path root=artifactsRoot.toAbsolutePath().normalize();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root))
            throw new IOException("Owned artifacts root is unsafe");
        Path finalDir=root.resolve("final");
        if(Files.exists(finalDir,LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException("Final artifact bundle already exists");
        Path staging=root.resolve("staging-"+Long.toUnsignedString(
                ThreadLocalRandom.current().nextLong(),36));
        Files.createDirectory(staging);
        String semanticHash=semanticHasher.hash(input);
        boolean moved=false;
        try{
            failureHook.at(FailurePoint.STAGING_CREATED);
            LinkedHashMap<String,byte[]> payloads=new LinkedHashMap<>();
            try{
                payloads.put("summary.json",renderer.summary(input,semanticHash));
                payloads.put("report.md",renderer.report(input,semanticHash));
                payloads.put("hashes.json",renderer.hashes(input,semanticHash));
                payloads.put("verification/summary.json",renderer.verification(input));
            }catch(Exception e){throw new IOException("Artifact rendering failed",e);}
            int total=payloads.values().stream().mapToInt(v->v.length).sum();
            if(total>MAX_BUNDLE_BYTES)throw new IOException("Artifact bundle exceeds budget");
            List<CoreDemoArtifactCommit.FileFact> facts=new ArrayList<>();
            for(var entry:payloads.entrySet()){
                if(entry.getValue().length>MAX_FILE_BYTES)
                    throw new IOException("Artifact file exceeds budget: "+entry.getKey());
                Path target=resolve(staging,entry.getKey());Files.createDirectories(target.getParent());
                Files.write(target,entry.getValue(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                verifyFile(target,entry.getValue());
                facts.add(new CoreDemoArtifactCommit.FileFact(entry.getKey(),entry.getValue().length,sha(entry.getValue())));
                failureHook.at(switch(entry.getKey()){
                    case "summary.json"->FailurePoint.SUMMARY_WRITTEN;
                    case "report.md"->FailurePoint.REPORT_WRITTEN;
                    case "hashes.json"->FailurePoint.HASHES_WRITTEN;
                    default->FailurePoint.VERIFICATION_WRITTEN;});
            }
            facts.sort(Comparator.comparing(CoreDemoArtifactCommit.FileFact::relativePath));
            byte[] marker=manifest(input,semanticHash,facts);
            Files.write(staging.resolve("artifact-manifest.json"),marker,
                    StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            verifyFile(staging.resolve("artifact-manifest.json"),marker);
            failureHook.at(FailurePoint.MANIFEST_WRITTEN);
            verifyStaging(staging,facts,marker);failureHook.at(FailurePoint.BEFORE_MOVE);
            boolean atomic=move(staging,finalDir);moved=true;
            failureHook.at(FailurePoint.AFTER_MOVE);verifyCommitted(finalDir);
            return new CoreDemoArtifactCommit(semanticHash,sha(marker),facts,atomic);
        }catch(IOException e){
            if(moved&&!isCommitted(finalDir))
                throw new IOException("Artifact move left an uncommitted final bundle",e);
            throw e;
        }
    }

    public boolean isCommitted(Path finalDir){
        try{verifyCommitted(finalDir.toAbsolutePath().normalize());return true;}
        catch(Exception e){return false;}
    }
    private boolean move(Path from,Path to)throws IOException{
        if(preferAtomicMove)try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);return true;}
        catch(AtomicMoveNotSupportedException ignored){}
        Files.move(from,to);return false;
    }
    private void verifyStaging(Path root,List<CoreDemoArtifactCommit.FileFact> facts,byte[] marker)throws IOException{
        for(var f:facts){byte[] a=Files.readAllBytes(resolve(root,f.relativePath()));
            if(a.length!=f.sizeBytes()||!sha(a).equals(f.sha256()))throw new IOException("Artifact staging verification failed");}
        verifyFile(root.resolve("artifact-manifest.json"),marker);
    }
    @SuppressWarnings("unchecked")
    private void verifyCommitted(Path root)throws IOException{
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root))
            throw new IOException("Final bundle missing");
        Path marker=root.resolve("artifact-manifest.json");
        if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(marker))
            throw new IOException("Commit marker missing");
        Map<String,Object> manifest;
        try{manifest=json.readValue(Files.readAllBytes(marker),Map.class);}
        catch(Exception e){throw new IOException("Commit marker malformed",e);}
        Object listed=manifest.get("files");
        if(!(listed instanceof List<?> files)||files.size()!=PAYLOADS.size())
            throw new IOException("Commit marker file set invalid");
        Set<String> seen=new HashSet<>();
        for(Object item:files){
            if(!(item instanceof Map<?,?> fact))throw new IOException("Commit fact malformed");
            String rel=String.valueOf(fact.get("relativePath"));
            if(!PAYLOADS.contains(rel)||!seen.add(rel))throw new IOException("Commit file set mismatch");
            byte[] bytes=Files.readAllBytes(resolve(root,rel));
            long size=((Number)fact.get("sizeBytes")).longValue();
            if(bytes.length!=size||!sha(bytes).equals(String.valueOf(fact.get("sha256"))))
                throw new IOException("Committed artifact hash mismatch");
        }
        if(!seen.equals(PAYLOADS))throw new IOException("Committed artifact incomplete");
    }
    private byte[] manifest(CoreDemoArtifactInput in,String semantic,
            List<CoreDemoArtifactCommit.FileFact> facts)throws IOException{
        TreeMap<String,Object> root=new TreeMap<>();root.put("artifactSchemaVersion",1);
        root.put("scenarioId",in.fixture().scenarioId());root.put("runId",in.runId());
        root.put("bundleStatus","COMMITTED");root.put("files",facts);
        root.put("committedAt",Instant.now().toString());root.put("semanticResultHash",semantic);
        try{return(json.writeValueAsString(root).replace("\r\n","\n").replace('\r','\n')+"\n").getBytes(StandardCharsets.UTF_8);}
        catch(Exception e){throw new IOException("Could not render artifact manifest",e);}
    }
    private static Path resolve(Path root,String rel)throws IOException{
        if(!PAYLOADS.contains(rel))throw new IOException("Unexpected artifact path");
        Path result=root.resolve(rel.replace('/',java.io.File.separatorChar)).normalize();
        if(!result.startsWith(root))throw new IOException("Artifact path escape");return result;
    }
    private static void verifyFile(Path file,byte[] expected)throws IOException{
        if(!Arrays.equals(Files.readAllBytes(file),expected))throw new IOException("Artifact write mismatch");
    }
    private static String sha(byte[] value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}
    }
    enum FailurePoint{STAGING_CREATED,SUMMARY_WRITTEN,REPORT_WRITTEN,HASHES_WRITTEN,
        VERIFICATION_WRITTEN,MANIFEST_WRITTEN,BEFORE_MOVE,AFTER_MOVE}
    @FunctionalInterface interface FailureHook{void at(FailurePoint point)throws IOException;}
}
