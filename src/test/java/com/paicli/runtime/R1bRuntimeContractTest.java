package com.paicli.runtime;

import com.paicli.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R1bRuntimeContractTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    @Test
    void exactRuntimeIdentityIsRequired() {
        R1bRuntimeContract original = contract("ollama", "model-a",
                Config.MODE_WORKSPACE_WRITE, true, A);
        assertTrue(original.compareForRecovery(original).compatible());
        assertDrift(original, contract("openai", "model-a",
                Config.MODE_WORKSPACE_WRITE, true, A), "PROVIDER_FAMILY");
        assertDrift(original, contract("ollama", "model-b",
                Config.MODE_WORKSPACE_WRITE, true, A), "MODEL_IDENTITY");
        assertDrift(original, changed(original, "tools-v2",
                original.conversationAdapterVersion(), original.runtimeManifestHash()),
                "TOOL_SCHEMA");
        assertDrift(original, changed(original, original.toolSchemaVersion(),
                "adapter-v2", original.runtimeManifestHash()), "CONVERSATION_ADAPTER");
        assertDrift(original, changed(original, original.toolSchemaVersion(),
                original.conversationAdapterVersion(), B), "RUNTIME_MANIFEST");
        assertDrift(original, new R1bRuntimeContract(original.providerFamily(),
                original.modelIdentity(), original.toolSchemaVersion(),
                original.conversationAdapterVersion(), "manifest-00000002",
                original.instructionManifestHash(), original.runtimeManifestVersion(),
                original.runtimeManifestHash(), original.permissionMode(),
                original.verificationEnabled(), original.verificationPolicyHash(),
                original.serializationProtocolVersion()), "INSTRUCTION_MANIFEST_ID");
        assertDrift(original, new R1bRuntimeContract(original.providerFamily(),
                original.modelIdentity(), original.toolSchemaVersion(),
                original.conversationAdapterVersion(), original.instructionManifestId(),
                B, original.runtimeManifestVersion(), original.runtimeManifestHash(),
                original.permissionMode(), original.verificationEnabled(),
                original.verificationPolicyHash(), "checkpoint-json-v1"),
                "INSTRUCTION_MANIFEST_HASH");
    }

    @Test
    void permissionMayTightenAndVerificationMayStrengthenOnly() {
        R1bRuntimeContract original = contract("ollama", "model-a",
                Config.MODE_WORKSPACE_WRITE, true, A);
        assertTrue(original.compareForRecovery(contract("ollama", "model-a",
                Config.MODE_READ_ONLY, true, A)).compatible());
        assertDrift(contract("ollama", "model-a", Config.MODE_READ_ONLY, true, A),
                original, "PERMISSION_ESCALATION");
        assertDrift(original, contract("ollama", "model-a",
                Config.MODE_WORKSPACE_WRITE, false, A), "VERIFICATION_DOWNGRADE");
        assertTrue(contract("ollama", "model-a", Config.MODE_WORKSPACE_WRITE, false, A)
                .compareForRecovery(original).compatible());
    }

    private static R1bRuntimeContract contract(String provider, String model,
            String permission, boolean verify, String verificationHash) {
        return new R1bRuntimeContract(provider, model, "paicli-tools-v1",
                "agent-message-v1", "manifest-00000001", A, "skills-hooks-v1",
                A, permission, verify, verificationHash, "checkpoint-json-v1");
    }

    private static R1bRuntimeContract changed(R1bRuntimeContract value, String tools,
            String adapter, String runtimeHash) {
        return new R1bRuntimeContract(value.providerFamily(), value.modelIdentity(), tools,
                adapter, value.instructionManifestId(), value.instructionManifestHash(),
                value.runtimeManifestVersion(), runtimeHash, value.permissionMode(),
                value.verificationEnabled(), value.verificationPolicyHash(),
                value.serializationProtocolVersion());
    }

    private static void assertDrift(R1bRuntimeContract original,
            R1bRuntimeContract current, String code) {
        R1bRuntimeContract.Compatibility result = original.compareForRecovery(current);
        assertFalse(result.compatible());
        assertTrue(result.driftCodes().contains(code));
    }
}
