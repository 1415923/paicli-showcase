package com.paicli.context;

import com.paicli.context.ContextModels.CapabilityProfile;
import com.paicli.context.ContextModels.LimitProvenance;

/** Versioned capability-limit precedence; generic compatible providers receive no assumed limit. */
public final class ProviderContextCapabilityResolver {
    public static final String VERSION="R3_CAPABILITY_RESOLVER_V1";
    private static final int OUTPUT_RESERVE=2048;
    private static final int SAFETY_MARGIN=512;

    public CapabilityProfile resolve(String provider,String model,Integer providerReported,
            Integer configured,Integer modelCatalog){
        if(providerReported!=null)return profile(provider,model,providerReported,
                LimitProvenance.PROVIDER_REPORTED_LIMIT);
        if(configured!=null)return profile(provider,model,configured,
                LimitProvenance.CONFIGURED_LIMIT);
        if(modelCatalog!=null)return profile(provider,model,modelCatalog,
                LimitProvenance.MODEL_CATALOG_LIMIT);
        return new CapabilityProfile(VERSION+":UNKNOWN:"+safe(provider)+":"+safe(model),null,
                LimitProvenance.UNKNOWN_LIMIT,OUTPUT_RESERVE,SAFETY_MARGIN);
    }
    private CapabilityProfile profile(String provider,String model,int limit,LimitProvenance provenance){
        if(limit<1024)throw new IllegalArgumentException("context limit must be at least 1024");
        return new CapabilityProfile(VERSION+":"+provenance+":"+safe(provider)+":"+safe(model),
                limit,provenance,OUTPUT_RESERVE,SAFETY_MARGIN);
    }
    private static String safe(String value){
        if(value==null||value.isBlank())return "unknown";
        String clean=value.replaceAll("[^A-Za-z0-9._-]","_");
        return clean.substring(0,Math.min(32,clean.length()));
    }
}
