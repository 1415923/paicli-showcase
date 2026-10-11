package com.paicli.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Frozen Task-Recovery runtime contract stored in committed Generation metadata. */
public record R1bRuntimeContract(
        String providerFamily,
        String modelIdentity,
        String toolSchemaVersion,
        String conversationAdapterVersion,
        String instructionManifestId,
        String instructionManifestHash,
        String runtimeManifestVersion,
        String runtimeManifestHash,
        String permissionMode,
        boolean verificationEnabled,
        String verificationPolicyHash,
        String serializationProtocolVersion) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String TOOL_SCHEMA_VERSION = "paicli-tools-v1";
    public static final String CONVERSATION_ADAPTER_VERSION = "agent-message-v1";
    public static final String RUNTIME_MANIFEST_VERSION = "skills-hooks-v1";
    public static final String SERIALIZATION_PROTOCOL_VERSION = "checkpoint-json-v1";

    public R1bRuntimeContract {
        require(providerFamily,"providerFamily");require(modelIdentity,"modelIdentity");
        require(toolSchemaVersion,"toolSchemaVersion");
        require(conversationAdapterVersion,"conversationAdapterVersion");
        require(instructionManifestId,"instructionManifestId");
        requireHash(instructionManifestHash,"instructionManifestHash");
        require(runtimeManifestVersion,"runtimeManifestVersion");
        requireHash(runtimeManifestHash,"runtimeManifestHash");
        require(permissionMode,"permissionMode");
        requireHash(verificationPolicyHash,"verificationPolicyHash");
        require(serializationProtocolVersion,"serializationProtocolVersion");
    }

    public static R1bRuntimeContract capture(Config config,RuntimeManifest runtimeManifest,
            String manifestId,String manifestHash){
        Objects.requireNonNull(config);
        String runtime=runtimeManifest==null?"disabled|disabled"
                : safe(runtimeManifest.skillsSha256())+"|"+safe(runtimeManifest.hooksSha256());
        String verification=(config.isVerifyEnabled()?"enabled":"disabled")+"|"
                +sha256(safe(config.effectiveVerificationCommand()))+"|"
                +config.effectiveMaxRepairAttempts();
        String model=config.model()==null||config.model().isBlank()
                ?config.effectiveProvider()+":default":config.model();
        return new R1bRuntimeContract(config.effectiveProvider(),model,
                TOOL_SCHEMA_VERSION,CONVERSATION_ADAPTER_VERSION,manifestId,manifestHash,
                RUNTIME_MANIFEST_VERSION,sha256(runtime),safeMode(config.approvalMode()),
                config.isVerifyEnabled(),sha256(verification),SERIALIZATION_PROTOCOL_VERSION);
    }

    public Compatibility compareForRecovery(R1bRuntimeContract current){
        Objects.requireNonNull(current);
        List<String> drift=new ArrayList<>();
        same(providerFamily,current.providerFamily,"PROVIDER_FAMILY",drift);
        same(modelIdentity,current.modelIdentity,"MODEL_IDENTITY",drift);
        same(toolSchemaVersion,current.toolSchemaVersion,"TOOL_SCHEMA",drift);
        same(conversationAdapterVersion,current.conversationAdapterVersion,
                "CONVERSATION_ADAPTER",drift);
        same(instructionManifestId,current.instructionManifestId,"INSTRUCTION_MANIFEST_ID",drift);
        same(instructionManifestHash,current.instructionManifestHash,
                "INSTRUCTION_MANIFEST_HASH",drift);
        same(runtimeManifestVersion,current.runtimeManifestVersion,
                "RUNTIME_MANIFEST_VERSION",drift);
        same(runtimeManifestHash,current.runtimeManifestHash,"RUNTIME_MANIFEST",drift);
        same(serializationProtocolVersion,current.serializationProtocolVersion,
                "SERIALIZATION_PROTOCOL",drift);
        if(!capabilities(permissionMode).containsAll(capabilities(current.permissionMode))){
            drift.add("PERMISSION_ESCALATION");
        }
        if(verificationEnabled&&!current.verificationEnabled){
            drift.add("VERIFICATION_DOWNGRADE");
        }else if(verificationEnabled
                &&!verificationPolicyHash.equals(current.verificationPolicyHash)){
            drift.add("VERIFICATION_POLICY_DRIFT");
        }
        return drift.isEmpty()?new Compatibility(true,List.of())
                :new Compatibility(false,List.copyOf(drift));
    }

    public String toJson(){
        try{return MAPPER.writeValueAsString(this);}
        catch(JsonProcessingException e){throw new IllegalStateException("Runtime contract serialization failed",e);}
    }

    public static R1bRuntimeContract fromJson(String json){
        try{return MAPPER.readValue(json,R1bRuntimeContract.class);}
        catch(Exception e){throw new IllegalArgumentException("Invalid frozen runtime contract",e);}
    }

    public String canonicalHash(){return sha256(toJson());}

    public record Compatibility(boolean compatible,List<String> driftCodes){
        public Compatibility{driftCodes=List.copyOf(driftCodes);}
    }

    private static Set<String> capabilities(String mode){
        return switch(mode){
            case Config.MODE_DENY -> Set.of();
            case Config.MODE_PLAN,Config.MODE_READ_ONLY -> Set.of("LOW");
            case Config.MODE_WORKSPACE_WRITE -> Set.of("LOW","MEDIUM");
            case Config.MODE_ON_REQUEST -> Set.of("LOW","MEDIUM","HIGH_WITH_APPROVAL");
            default -> Set.of("UNKNOWN");
        };
    }
    private static void same(String expected,String actual,String code,List<String> drift){
        if(!Objects.equals(expected,actual))drift.add(code);
    }
    private static String safeMode(String value){return value==null?Config.MODE_DENY:value;}
    private static String safe(String value){return value==null?"":value;}
    private static void require(String value,String field){
        if(value==null||value.isBlank())throw new IllegalArgumentException(field+" is required");
    }
    private static void requireHash(String value,String field){
        if(value==null||!value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException(field+" must be lowercase SHA-256");
    }
    private static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}
    }
}
