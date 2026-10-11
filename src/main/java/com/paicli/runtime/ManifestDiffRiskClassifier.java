package com.paicli.runtime;

import com.paicli.state.FollowUpModels.*;
import com.paicli.state.InstructionManifest.InstructionEntry;
import com.paicli.state.InstructionManifestCandidate.Entry;
import java.nio.file.Path;
import java.util.*;

/** Provider-free Manifest inheritance risk classifier. BLOCKING cannot be user-overridden. */
public final class ManifestDiffRiskClassifier {
    public List<ManifestDiff> diff(List<InstructionEntry> parent,List<Entry> child){
        Map<String,InstructionEntry> old=new TreeMap<>();parent.forEach(e->old.put(e.sourcePath(),e));
        Map<String,Entry> now=new TreeMap<>();child.forEach(e->now.put(e.sourcePath(),e));
        Set<String> paths=new TreeSet<>();paths.addAll(old.keySet());paths.addAll(now.keySet());
        List<ManifestDiff> out=new ArrayList<>();
        for(String path:paths){
            InstructionEntry a=old.get(path);Entry b=now.get(path);
            DiffCategory category;
            if(a==null)category=DiffCategory.ADDED;
            else if(b==null)category=DiffCategory.REMOVED;
            else if(!a.status().name().equals(b.status().name()))category=DiffCategory.STATUS_CHANGED;
            else if(!Objects.equals(a.contentHash(),b.contentHash()))category=DiffCategory.CHANGED;
            else if(!Objects.equals(a.appliedOrder(),b.appliedOrder()))category=DiffCategory.REORDERED;
            else category=DiffCategory.UNCHANGED;
            out.add(new ManifestDiff(category,path,a==null?null:a.contentHash(),b==null?null:b.contentHash(),
                    category.name()+":"+path,"PARENT_COMMITTED_VS_CHILD_FRESH"));
        }
        return List.copyOf(out);
    }

    public Assessment classify(List<ManifestDiff> diffs){
        if(diffs==null||diffs.isEmpty())return new Assessment(Risk.UNKNOWN,"MANIFEST_DIFF_MISSING");
        for(ManifestDiff diff:diffs){
            if(invalidPath(diff.sourcePath()))
                return new Assessment(Risk.BLOCKING,"MANIFEST_SOURCE_SCOPE_INVALID");
            if(diff.category()==DiffCategory.CHANGED&&(!hash(diff.parentHash())||!hash(diff.childHash())))
                return new Assessment(Risk.UNKNOWN,"MANIFEST_DIFF_HASH_UNKNOWN");
        }
        return diffs.stream().allMatch(d->d.category()==DiffCategory.UNCHANGED)
                ?new Assessment(Risk.LOW,"MANIFEST_UNCHANGED")
                :new Assessment(Risk.HIGH,"MANIFEST_AUTHORITY_CHANGED");
    }
    private static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static boolean invalidPath(String value){
        if(value==null||value.isBlank()||value.replace('\\','/').contains("../"))return true;
        try{return Path.of(value).isAbsolute();}catch(java.nio.file.InvalidPathException invalid){return true;}
    }
    public record Assessment(Risk risk,String reason){ }
}
