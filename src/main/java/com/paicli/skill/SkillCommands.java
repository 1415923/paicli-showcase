package com.paicli.skill;

import com.paicli.config.Config;
import com.paicli.interactive.command.ShellCommandResult;
import com.paicli.safety.SafetyPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit, inert package management. No Tool API can import or activate a draft. */
public final class SkillCommands {
    private final Path workspace;
    private final List<String> directories;
    public SkillCommands(Path workspace,List<String> directories)throws IOException {
        this.workspace=SkillPackageFiles.plain(workspace);this.directories=List.copyOf(directories);
    }

    public synchronized ShellCommandResult command(List<String> args,boolean taskRunning) {
        try {
            String verb=args.isEmpty()?"list":args.getFirst();
            if(taskRunning&&!Set.of("list","show").contains(verb))throw new IOException("Skill import/init is unavailable during a task");
            return switch(verb){
                case "list" -> {if(args.size()>1)throw new IOException("usage: skills list");yield result(list());}
                case "show" -> {if(args.size()!=2)throw new IOException("usage: skills show <name>");yield result(show(args.get(1)));}
                case "init" -> {if(args.size()!=2)throw new IOException("usage: skills init <name>");yield result(init(args.get(1)));}
                case "import" -> {if(args.size()<2||args.size()>3)throw new IOException("usage: skills import <directory> [sha256]");
                    yield result(importPackage(args.get(1),args.size()==3?args.get(2):null));}
                default -> throw new IOException("usage: skills [list|show <name>|init <name>|import <directory> [sha256]]");
            };
        }catch(Exception failure){
            return ShellCommandResult.continueWith("SKILL_COMMAND_REJECTED",List.of(
                    new com.paicli.runtime.PersistenceSanitizer().sanitizeAndTruncate(
                            Objects.toString(failure.getMessage(),"Skill operation failed"),2048)));
        }
    }

    private ShellCommandResult result(List<String> lines){return ShellCommandResult.continueWith("SKILLS",lines);}
    private List<Path> packages()throws IOException {
        List<Path> result=new ArrayList<>();
        for(String dir:directories){
            Path root=workspace.resolve(dir).normalize();safeTarget(root);
            if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))continue;
            SkillPackageFiles.plain(root);
            try(var entries=Files.list(root)){
                var iterator=entries.iterator();int count=0;
                while(iterator.hasNext()){
                    Path entry=iterator.next();if(++count>256)throw new IOException("Too many Skill directory entries");
                    if(Files.exists(entry.resolve("SKILL.md"),LinkOption.NOFOLLOW_LINKS))result.add(entry);
                    if(result.size()>64)throw new IOException("Too many Skills");
                }
            }
        }
        return result.stream().distinct().sorted().toList();
    }

    private List<String> list()throws IOException {
        var lines=new ArrayList<String>();var names=new HashSet<String>();
        for(Path path:packages()){
            try {var skill=SkillLoader.inspectPackage(path);
                String status=names.add(skill.name().toLowerCase(Locale.ROOT))?"AVAILABLE":"REJECTED duplicate name";
                lines.add(skill.name()+" ["+status+"] "+safe(skill.description()));
            }catch(IOException failure){lines.add(path.getFileName()+" [REJECTED] "+safe(failure.getMessage()));}
        }
        if(lines.isEmpty())lines.add("No Skills installed. Use skills import <directory> or skills init <name>.");
        return lines;
    }

    private List<String> show(String name)throws IOException {
        for(Path path:packages()){
            var skill=SkillLoader.inspectPackage(path);
            if(skill.name().equals(name)){
                var snapshot=SkillPackageFiles.snapshot(path);
                return List.of(skill.name()+": "+safe(skill.description()),
                        "Package: "+workspace.relativize(path),"SHA-256: "+snapshot.hash(),
                        "Files: "+snapshot.hashes().size()+"; bytes: "+snapshot.bytes(),
                        "Activate with $"+name+" or load_skill. Resources use read_skill_resource; scripts use approved run_command.");
            }
        }
        throw new IOException("Unknown Skill: "+name);
    }

    private List<String> init(String name)throws IOException {
        if(!name.matches("[a-z0-9][a-z0-9-]{0,63}"))throw new IOException("Use a lowercase name containing letters, numbers and hyphens");
        Path drafts=workspace.resolve("skill-drafts");
        safeTarget(drafts.resolve(name));
        Files.createDirectories(drafts);Path target=Files.createDirectory(drafts.resolve(name));
        Files.writeString(target.resolve("SKILL.md"),"---\nname: "+name+"\ndescription: Describe the task this Skill helps with and when to use it.\n---\n\n"
                +"Describe the useful workflow here. Put optional detailed references in separate files and link them when needed.\n"
                +"Tools and scripts use the host's existing permissions and approval process.\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        return List.of("Draft created: skill-drafts/"+name+"/SKILL.md",
                "Edit the draft, then preview and confirm skills import \"skill-drafts/"+name+"\". It is not active yet.");
    }

    private List<String> importPackage(String source,String confirmation)throws IOException {
        Path directory=workspace.resolve(source).toAbsolutePath().normalize();
        var snapshot=SkillPackageFiles.snapshot(directory);
        var skill=SkillLoader.inspectPackage(directory);
        if(!skill.contentSha256().equals(snapshot.hashes().get("SKILL.md")))throw new IOException("Skill metadata changed during inspection; preview again");
        for(Path existing:packages()){
            if(SkillLoader.inspectPackage(existing).name().equalsIgnoreCase(skill.name()))throw new IOException("Skill name already exists: "+skill.name());
        }
        Path target=workspace.resolve(".paicli/skills").resolve(skill.name());
        safeTarget(target);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Skill destination already exists");
        if(confirmation==null)return List.of("Import preview: "+skill.name()+" — "+safe(skill.description()),
                "Source: "+directory,"Destination: "+target,
                "Complete package: "+snapshot.hashes().size()+" files, "+snapshot.bytes()+" bytes; no scripts or dependencies will run.",
                "SHA-256: "+snapshot.hash(),
                "Confirm: skills import \""+source+"\" "+snapshot.hash());
        if(!snapshot.hash().equals(confirmation))throw new IOException("Skill content changed or confirmation hash does not match; preview again");
        Path imports=workspace.resolve(".paicli/skill-imports");
        safeTarget(imports);
        Files.createDirectories(imports);
        Path record=Files.createDirectory(imports.resolve(UUID.randomUUID().toString()));
        Path staging=Files.createDirectory(record.resolve("package"));
        for(String dir:snapshot.directories())Files.createDirectories(staging.resolve(dir));
        for(var entry:snapshot.hashes().entrySet()){
            byte[] bytes=SkillPackageFiles.read(directory,entry.getKey(),SkillPackageFiles.MAX_BYTES);
            if(!entry.getValue().equals(SkillPackageFiles.hash(bytes)))throw new IOException("Skill source changed while copying");
            Files.write(staging.resolve(entry.getKey()),bytes,StandardOpenOption.CREATE_NEW);
        }
        if(!snapshot.hash().equals(SkillPackageFiles.snapshot(staging).hash()))throw new IOException("Copied Skill package does not match confirmation");
        var manifest=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of(
                "source",directory.toString(),"name",skill.name(),"package_sha256",snapshot.hash(),"files",snapshot.hashes()));
        Files.write(record.resolve("source.json"),manifest,StandardOpenOption.CREATE_NEW);
        safeTarget(target);Files.createDirectories(target.getParent());
        Files.move(staging,target); // no REPLACE_EXISTING: collisions must fail
        return List.of("Imported "+skill.name()+"; complete package SHA-256 "+snapshot.hash(),
                "No scripts executed. Start a new task with $"+skill.name()+" or let the model discover it.");
    }
    private void safeTarget(Path path)throws IOException {
        try{new SafetyPolicy(workspace).validatePath(path);}
        catch(com.paicli.safety.SafetyViolationException error){throw new IOException("Skill target escapes workspace or traverses a link",error);}
    }
    private String safe(String text){return new com.paicli.runtime.PersistenceSanitizer().sanitizeAndTruncate(text,2048);}
}
