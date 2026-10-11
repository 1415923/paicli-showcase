package com.paicli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Immutable configuration for PaiCLI.
 * Loaded from project-level (.paicli/config.json) and user-level (~/.paicli/config.json).
 * Project-level overrides user-level, which overrides defaults.
 * <p>
 * Unknown fields in JSON are silently ignored (forward compatibility).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Config(
        @JsonProperty("workspace") String workspace,
        @JsonProperty("command_timeout_seconds") int commandTimeoutSeconds,
        @JsonProperty("max_output_bytes") int maxOutputBytes,
        @JsonProperty("max_write_file_bytes") int maxWriteFileBytes,
        @JsonProperty("approval_mode") String approvalMode,
        @JsonProperty("max_project_instruction_chars") int maxProjectInstructionChars,
        // v0.3.0: Provider configuration
        @JsonProperty("provider") String provider,
        @JsonProperty("model") String model,
        @JsonProperty("base_url") String baseUrl,
        @JsonProperty("api_key_env") String apiKeyEnv,
        @JsonProperty("system_prompt") String systemPrompt,
        @JsonProperty("max_iterations") int maxIterations,
        // v0.5.0: Plan / Verify / Repair runtime configuration
        @JsonProperty("verification_command") String verificationCommand,
        @JsonProperty("verification_timeout_seconds") int verificationTimeoutSeconds,
        @JsonProperty("max_verification_output_bytes") int maxVerificationOutputBytes,
        @JsonProperty("max_repair_attempts") Integer maxRepairAttempts,
        @JsonProperty("enable_plan") Boolean enablePlan,
        @JsonProperty("enable_verify") Boolean enableVerify,
        @JsonProperty("enable_repair") Boolean enableRepair,
        // v0.6.0: Skills runtime configuration
        @JsonProperty("enable_skills") Boolean enableSkills,
        @JsonProperty("skills_dirs") List<String> skillsDirs,
        @JsonProperty("max_skill_context_chars") Integer maxSkillContextChars,
        @JsonProperty("max_selected_skills") Integer maxSelectedSkills,
        // v0.6.0: Hooks runtime configuration
        @JsonProperty("enable_hooks") Boolean enableHooks,
        @JsonProperty("hooks_config_path") String hooksConfigPath,
        @JsonProperty("hook_timeout_seconds") Integer hookTimeoutSeconds,
        @JsonProperty("max_hook_output_bytes") Integer maxHookOutputBytes,
        @JsonProperty("hook_failure_policy") String hookFailurePolicy,
        @JsonProperty("context_window_tokens") Integer contextWindowTokens,
        @JsonProperty("provider_preset") String providerPreset,
        @JsonProperty("thinking") String thinking,
        @JsonProperty("reasoning_effort") String reasoningEffort,
        @JsonProperty("credential_ref") String credentialRef,
        @JsonProperty("task_continuity") TaskContinuityConfig taskContinuity
) {

    public static final int DEFAULT_VERIFICATION_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_MAX_VERIFICATION_OUTPUT_BYTES = 50 * 1024;
    public static final int DEFAULT_MAX_REPAIR_ATTEMPTS = 1;
    public static final int MAX_REPAIR_ATTEMPTS = 5;
    public static final List<String> DEFAULT_SKILLS_DIRS =
            List.of("skills", ".paicli/skills");
    public static final int DEFAULT_MAX_SKILL_CONTEXT_CHARS = 16 * 1024;
    public static final int MIN_SKILL_CONTEXT_CHARS = 512;
    public static final int MAX_SKILL_CONTEXT_CHARS = 64 * 1024;
    public static final int DEFAULT_MAX_SELECTED_SKILLS = 3;
    public static final int MAX_SELECTED_SKILLS = 10;
    public static final String DEFAULT_HOOKS_CONFIG_PATH = ".paicli/hooks.json";
    public static final int DEFAULT_HOOK_TIMEOUT_SECONDS = 30;
    public static final int MAX_HOOK_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_MAX_HOOK_OUTPUT_BYTES = 32 * 1024;
    public static final int MAX_HOOK_OUTPUT_BYTES = 256 * 1024;
    public static final String DEFAULT_HOOK_FAILURE_POLICY = "warn";

    public Config {
        if (maxRepairAttempts != null
                && (maxRepairAttempts < 1 || maxRepairAttempts > MAX_REPAIR_ATTEMPTS)) {
            throw new IllegalArgumentException(
                    "max_repair_attempts must be between 1 and " + MAX_REPAIR_ATTEMPTS);
        }
        if (maxSkillContextChars != null
                && (maxSkillContextChars < MIN_SKILL_CONTEXT_CHARS
                || maxSkillContextChars > MAX_SKILL_CONTEXT_CHARS)) {
            throw new IllegalArgumentException(
                    "max_skill_context_chars must be between "
                            + MIN_SKILL_CONTEXT_CHARS + " and "
                            + MAX_SKILL_CONTEXT_CHARS);
        }
        if (maxSelectedSkills != null
                && (maxSelectedSkills < 1 || maxSelectedSkills > MAX_SELECTED_SKILLS)) {
            throw new IllegalArgumentException(
                    "max_selected_skills must be between 1 and "
                            + MAX_SELECTED_SKILLS);
        }
        if (skillsDirs != null) {
            if (skillsDirs.isEmpty() || skillsDirs.stream()
                    .anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException(
                        "skills_dirs must contain non-blank paths");
            }
            skillsDirs = List.copyOf(skillsDirs);
        }
        if (hooksConfigPath != null && hooksConfigPath.isBlank()) {
            throw new IllegalArgumentException("hooks_config_path must not be blank");
        }
        if (hookTimeoutSeconds != null
                && (hookTimeoutSeconds < 1 || hookTimeoutSeconds > MAX_HOOK_TIMEOUT_SECONDS)) {
            throw new IllegalArgumentException(
                    "hook_timeout_seconds must be between 1 and " + MAX_HOOK_TIMEOUT_SECONDS);
        }
        if (maxHookOutputBytes != null
                && (maxHookOutputBytes < 1 || maxHookOutputBytes > MAX_HOOK_OUTPUT_BYTES)) {
            throw new IllegalArgumentException(
                    "max_hook_output_bytes must be between 1 and " + MAX_HOOK_OUTPUT_BYTES);
        }
        if (hookFailurePolicy != null
                && !"warn".equals(hookFailurePolicy)
                && !"fail".equals(hookFailurePolicy)) {
            throw new IllegalArgumentException("hook_failure_policy must be warn or fail");
        }
        if(contextWindowTokens!=null&&contextWindowTokens<1024)
            throw new IllegalArgumentException("context_window_tokens must be at least 1024");
        if(providerPreset!=null&&!providerPreset.equals("deepseek"))
            throw new IllegalArgumentException("provider_preset must be deepseek when present");
        if(providerPreset!=null&&!PROVIDER_OPENAI.equals(provider))
            throw new IllegalArgumentException("provider_preset requires provider=openai");
        if(thinking!=null&&!thinking.equals("enabled")&&!thinking.equals("disabled"))
            throw new IllegalArgumentException("thinking must be enabled or disabled");
        if(reasoningEffort!=null&&!reasoningEffort.equals("high")&&!reasoningEffort.equals("max"))
            throw new IllegalArgumentException("reasoning_effort must be high or max");
        if((thinking!=null||reasoningEffort!=null)&&!"deepseek".equals(providerPreset))
            throw new IllegalArgumentException("thinking configuration requires provider_preset=deepseek");
        if (credentialRef != null) new com.paicli.credential.CredentialRef(credentialRef);
        if (taskContinuity != null) taskContinuity.validate();
    }

    /** Explicit production opt-in. No numeric production defaults exist. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskContinuityConfig(
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("max_additional_segments") Integer maxAdditionalSegments,
            @JsonProperty("max_plan_attempts") Integer maxPlanAttempts,
            @JsonProperty("max_total_provider_turns") Integer maxTotalProviderTurns,
            @JsonProperty("max_total_tool_calls") Integer maxTotalToolCalls,
            @JsonProperty("max_input_tokens") Long maxInputTokens,
            @JsonProperty("max_output_tokens") Long maxOutputTokens,
            @JsonProperty("max_active_elapsed_ms") Long maxActiveElapsedMillis) {
        public boolean explicitlyEnabled() { return Boolean.TRUE.equals(enabled); }

        private void validate() {
            if (!explicitlyEnabled()) return;
            requirePositive(maxAdditionalSegments, "max_additional_segments");
            requirePositive(maxPlanAttempts, "max_plan_attempts");
            requirePositive(maxTotalProviderTurns, "max_total_provider_turns");
            requirePositive(maxTotalToolCalls, "max_total_tool_calls");
            requirePositive(maxInputTokens, "max_input_tokens");
            requirePositive(maxOutputTokens, "max_output_tokens");
            requirePositive(maxActiveElapsedMillis, "max_active_elapsed_ms");
        }

        private static void requirePositive(Number value, String field) {
            if (value == null || value.longValue() <= 0) {
                throw new IllegalArgumentException(
                        "task_continuity.enabled=true requires positive " + field);
            }
        }
    }

    /** Full pre-continuity shape retained for source compatibility. */
    public Config(String workspace,int commandTimeoutSeconds,int maxOutputBytes,
            int maxWriteFileBytes,String approvalMode,int maxProjectInstructionChars,
            String provider,String model,String baseUrl,String apiKeyEnv,String systemPrompt,
            int maxIterations,String verificationCommand,int verificationTimeoutSeconds,
            int maxVerificationOutputBytes,Integer maxRepairAttempts,Boolean enablePlan,
            Boolean enableVerify,Boolean enableRepair,Boolean enableSkills,List<String> skillsDirs,
            Integer maxSkillContextChars,Integer maxSelectedSkills,Boolean enableHooks,
            String hooksConfigPath,Integer hookTimeoutSeconds,Integer maxHookOutputBytes,
            String hookFailurePolicy,Integer contextWindowTokens,String providerPreset,
            String thinking,String reasoningEffort,String credentialRef) {
        this(workspace,commandTimeoutSeconds,maxOutputBytes,maxWriteFileBytes,approvalMode,
                maxProjectInstructionChars,provider,model,baseUrl,apiKeyEnv,systemPrompt,
                maxIterations,verificationCommand,verificationTimeoutSeconds,
                maxVerificationOutputBytes,maxRepairAttempts,enablePlan,enableVerify,
                enableRepair,enableSkills,skillsDirs,maxSkillContextChars,maxSelectedSkills,
                enableHooks,hooksConfigPath,hookTimeoutSeconds,maxHookOutputBytes,
                hookFailurePolicy,contextWindowTokens,providerPreset,thinking,reasoningEffort,
                credentialRef,null);
    }

    /** Full v1.2 shape retained for source compatibility before credential references. */
    public Config(String workspace,int commandTimeoutSeconds,int maxOutputBytes,
            int maxWriteFileBytes,String approvalMode,int maxProjectInstructionChars,
            String provider,String model,String baseUrl,String apiKeyEnv,String systemPrompt,
            int maxIterations,String verificationCommand,int verificationTimeoutSeconds,
            int maxVerificationOutputBytes,Integer maxRepairAttempts,Boolean enablePlan,
            Boolean enableVerify,Boolean enableRepair,Boolean enableSkills,List<String> skillsDirs,
            Integer maxSkillContextChars,Integer maxSelectedSkills,Boolean enableHooks,
            String hooksConfigPath,Integer hookTimeoutSeconds,Integer maxHookOutputBytes,
            String hookFailurePolicy,Integer contextWindowTokens,String providerPreset,
            String thinking,String reasoningEffort) {
        this(workspace,commandTimeoutSeconds,maxOutputBytes,maxWriteFileBytes,approvalMode,
                maxProjectInstructionChars,provider,model,baseUrl,apiKeyEnv,systemPrompt,maxIterations,
                verificationCommand,verificationTimeoutSeconds,maxVerificationOutputBytes,
                maxRepairAttempts,enablePlan,enableVerify,enableRepair,enableSkills,skillsDirs,
                maxSkillContextChars,maxSelectedSkills,enableHooks,hooksConfigPath,hookTimeoutSeconds,
                maxHookOutputBytes,hookFailurePolicy,contextWindowTokens,providerPreset,thinking,
                reasoningEffort,null);
    }

    /** Previous full canonical shape retained for source compatibility. */
    public Config(String workspace,int commandTimeoutSeconds,int maxOutputBytes,
            int maxWriteFileBytes,String approvalMode,int maxProjectInstructionChars,
            String provider,String model,String baseUrl,String apiKeyEnv,String systemPrompt,
            int maxIterations,String verificationCommand,int verificationTimeoutSeconds,
            int maxVerificationOutputBytes,Integer maxRepairAttempts,Boolean enablePlan,
            Boolean enableVerify,Boolean enableRepair,Boolean enableSkills,List<String> skillsDirs,
            Integer maxSkillContextChars,Integer maxSelectedSkills,Boolean enableHooks,
            String hooksConfigPath,Integer hookTimeoutSeconds,Integer maxHookOutputBytes,
            String hookFailurePolicy,Integer contextWindowTokens,String providerPreset){
        this(workspace,commandTimeoutSeconds,maxOutputBytes,maxWriteFileBytes,approvalMode,
                maxProjectInstructionChars,provider,model,baseUrl,apiKeyEnv,systemPrompt,maxIterations,
                verificationCommand,verificationTimeoutSeconds,maxVerificationOutputBytes,
                maxRepairAttempts,enablePlan,enableVerify,enableRepair,enableSkills,skillsDirs,
                maxSkillContextChars,maxSelectedSkills,enableHooks,hooksConfigPath,hookTimeoutSeconds,
                maxHookOutputBytes,hookFailurePolicy,contextWindowTokens,providerPreset,null,null,null);
    }

    /** Full-shape compatibility constructor retained for existing v1.2 callers. */
    public Config(String workspace,int commandTimeoutSeconds,int maxOutputBytes,
            int maxWriteFileBytes,String approvalMode,int maxProjectInstructionChars,
            String provider,String model,String baseUrl,String apiKeyEnv,String systemPrompt,
            int maxIterations,String verificationCommand,int verificationTimeoutSeconds,
            int maxVerificationOutputBytes,Integer maxRepairAttempts,Boolean enablePlan,
            Boolean enableVerify,Boolean enableRepair,Boolean enableSkills,List<String> skillsDirs,
            Integer maxSkillContextChars,Integer maxSelectedSkills,Boolean enableHooks,
            String hooksConfigPath,Integer hookTimeoutSeconds,Integer maxHookOutputBytes,
            String hookFailurePolicy,Integer contextWindowTokens){
        this(workspace,commandTimeoutSeconds,maxOutputBytes,maxWriteFileBytes,approvalMode,
                maxProjectInstructionChars,provider,model,baseUrl,apiKeyEnv,systemPrompt,maxIterations,
                verificationCommand,verificationTimeoutSeconds,maxVerificationOutputBytes,
                maxRepairAttempts,enablePlan,enableVerify,enableRepair,enableSkills,skillsDirs,
                maxSkillContextChars,maxSelectedSkills,enableHooks,hooksConfigPath,hookTimeoutSeconds,
                maxHookOutputBytes,hookFailurePolicy,contextWindowTokens,null,null,null,null);
    }

    /** R2 canonical-shape compatibility constructor; R3 budget remains UNKNOWN unless configured. */
    public Config(String workspace,int commandTimeoutSeconds,int maxOutputBytes,
            int maxWriteFileBytes,String approvalMode,int maxProjectInstructionChars,
            String provider,String model,String baseUrl,String apiKeyEnv,String systemPrompt,
            int maxIterations,String verificationCommand,int verificationTimeoutSeconds,
            int maxVerificationOutputBytes,Integer maxRepairAttempts,Boolean enablePlan,
            Boolean enableVerify,Boolean enableRepair,Boolean enableSkills,List<String> skillsDirs,
            Integer maxSkillContextChars,Integer maxSelectedSkills,Boolean enableHooks,
            String hooksConfigPath,Integer hookTimeoutSeconds,Integer maxHookOutputBytes,
            String hookFailurePolicy){
        this(workspace,commandTimeoutSeconds,maxOutputBytes,maxWriteFileBytes,approvalMode,
                maxProjectInstructionChars,provider,model,baseUrl,apiKeyEnv,systemPrompt,maxIterations,
                verificationCommand,verificationTimeoutSeconds,maxVerificationOutputBytes,
                maxRepairAttempts,enablePlan,enableVerify,enableRepair,enableSkills,skillsDirs,
                maxSkillContextChars,maxSelectedSkills,enableHooks,hooksConfigPath,hookTimeoutSeconds,
                maxHookOutputBytes,hookFailurePolicy,null);
    }

    /** v0.1/v0.2 compatible constructor with no implicit production provider. */
    public Config(String workspace, int commandTimeoutSeconds, int maxOutputBytes,
                  int maxWriteFileBytes, String approvalMode, int maxProjectInstructionChars) {
        this(workspace, commandTimeoutSeconds, maxOutputBytes, maxWriteFileBytes,
                approvalMode, maxProjectInstructionChars,
                null, null, null, null, null, 10);
    }

    /** v0.3/v0.4 compatible constructor with runtime defaults. */
    public Config(String workspace, int commandTimeoutSeconds, int maxOutputBytes,
                  int maxWriteFileBytes, String approvalMode, int maxProjectInstructionChars,
                  String provider, String model, String baseUrl, String apiKeyEnv,
                  String systemPrompt, int maxIterations) {
        this(workspace, commandTimeoutSeconds, maxOutputBytes, maxWriteFileBytes,
                approvalMode, maxProjectInstructionChars,
                provider, model, baseUrl, apiKeyEnv, systemPrompt, maxIterations,
                "", DEFAULT_VERIFICATION_TIMEOUT_SECONDS,
                DEFAULT_MAX_VERIFICATION_OUTPUT_BYTES, DEFAULT_MAX_REPAIR_ATTEMPTS,
                false, true, true,
                true, DEFAULT_SKILLS_DIRS, DEFAULT_MAX_SKILL_CONTEXT_CHARS,
                DEFAULT_MAX_SELECTED_SKILLS,
                false, DEFAULT_HOOKS_CONFIG_PATH, DEFAULT_HOOK_TIMEOUT_SECONDS,
                DEFAULT_MAX_HOOK_OUTPUT_BYTES, DEFAULT_HOOK_FAILURE_POLICY);
    }

    /** v0.5 compatible constructor with Plan / Verify / Repair fields. */
    public Config(String workspace, int commandTimeoutSeconds, int maxOutputBytes,
                  int maxWriteFileBytes, String approvalMode, int maxProjectInstructionChars,
                  String provider, String model, String baseUrl, String apiKeyEnv,
                  String systemPrompt, int maxIterations,
                  String verificationCommand, int verificationTimeoutSeconds,
                  int maxVerificationOutputBytes, Integer maxRepairAttempts,
                  Boolean enablePlan, Boolean enableVerify, Boolean enableRepair) {
        this(workspace, commandTimeoutSeconds, maxOutputBytes, maxWriteFileBytes,
                approvalMode, maxProjectInstructionChars,
                provider, model, baseUrl, apiKeyEnv, systemPrompt, maxIterations,
                verificationCommand, verificationTimeoutSeconds,
                maxVerificationOutputBytes, maxRepairAttempts,
                enablePlan, enableVerify, enableRepair,
                true, DEFAULT_SKILLS_DIRS, DEFAULT_MAX_SKILL_CONTEXT_CHARS,
                DEFAULT_MAX_SELECTED_SKILLS,
                false, DEFAULT_HOOKS_CONFIG_PATH, DEFAULT_HOOK_TIMEOUT_SECONDS,
                DEFAULT_MAX_HOOK_OUTPUT_BYTES, DEFAULT_HOOK_FAILURE_POLICY);
    }

    /** v0.6 R2 compatible constructor with Skills fields and Hook defaults. */
    public Config(String workspace, int commandTimeoutSeconds, int maxOutputBytes,
                  int maxWriteFileBytes, String approvalMode, int maxProjectInstructionChars,
                  String provider, String model, String baseUrl, String apiKeyEnv,
                  String systemPrompt, int maxIterations,
                  String verificationCommand, int verificationTimeoutSeconds,
                  int maxVerificationOutputBytes, Integer maxRepairAttempts,
                  Boolean enablePlan, Boolean enableVerify, Boolean enableRepair,
                  Boolean enableSkills, List<String> skillsDirs,
                  Integer maxSkillContextChars, Integer maxSelectedSkills) {
        this(workspace, commandTimeoutSeconds, maxOutputBytes, maxWriteFileBytes,
                approvalMode, maxProjectInstructionChars,
                provider, model, baseUrl, apiKeyEnv, systemPrompt, maxIterations,
                verificationCommand, verificationTimeoutSeconds,
                maxVerificationOutputBytes, maxRepairAttempts,
                enablePlan, enableVerify, enableRepair,
                enableSkills, skillsDirs, maxSkillContextChars, maxSelectedSkills,
                false, DEFAULT_HOOKS_CONFIG_PATH, DEFAULT_HOOK_TIMEOUT_SECONDS,
                DEFAULT_MAX_HOOK_OUTPUT_BYTES, DEFAULT_HOOK_FAILURE_POLICY);
    }

    /** Default configuration values. */
    public static Config defaults() {
        return new Config(
                System.getProperty("user.dir"),  // workspace
                30,                                // command timeout
                50 * 1024,                         // max output bytes (50KB)
                200 * 1024,                        // max write file bytes (200KB)
                "on-request",                      // approval mode
                10 * 1024                          // max project instruction chars (10KB)
        );
    }

    /** Merge this config with overrides from another config. Non-null/non-default values from override win. */
    public Config merge(Config override) {
        if (override == null) return this;
        return new Config(
                override.workspace() != null ? override.workspace() : this.workspace,
                override.commandTimeoutSeconds() > 0 ? override.commandTimeoutSeconds() : this.commandTimeoutSeconds,
                override.maxOutputBytes() > 0 ? override.maxOutputBytes() : this.maxOutputBytes,
                override.maxWriteFileBytes() > 0 ? override.maxWriteFileBytes() : this.maxWriteFileBytes,
                override.approvalMode() != null ? override.approvalMode() : this.approvalMode,
                override.maxProjectInstructionChars() > 0 ? override.maxProjectInstructionChars() : this.maxProjectInstructionChars,
                override.provider() != null ? override.provider() : this.provider,
                override.model() != null ? override.model() : this.model,
                override.baseUrl() != null ? override.baseUrl() : this.baseUrl,
                override.apiKeyEnv() != null ? override.apiKeyEnv() : this.apiKeyEnv,
                override.systemPrompt() != null ? override.systemPrompt() : this.systemPrompt,
                override.maxIterations() > 0 ? override.maxIterations() : this.maxIterations,
                override.verificationCommand() != null
                        ? override.verificationCommand() : this.verificationCommand,
                override.verificationTimeoutSeconds() > 0
                        ? override.verificationTimeoutSeconds() : this.verificationTimeoutSeconds,
                override.maxVerificationOutputBytes() > 0
                        ? override.maxVerificationOutputBytes() : this.maxVerificationOutputBytes,
                override.maxRepairAttempts() != null
                        ? override.maxRepairAttempts() : this.maxRepairAttempts,
                override.enablePlan() != null ? override.enablePlan() : this.enablePlan,
                override.enableVerify() != null ? override.enableVerify() : this.enableVerify,
                override.enableRepair() != null ? override.enableRepair() : this.enableRepair,
                override.enableSkills() != null ? override.enableSkills() : this.enableSkills,
                override.skillsDirs() != null ? override.skillsDirs() : this.skillsDirs,
                override.maxSkillContextChars() != null
                        ? override.maxSkillContextChars() : this.maxSkillContextChars,
                override.maxSelectedSkills() != null
                        ? override.maxSelectedSkills() : this.maxSelectedSkills,
                override.enableHooks() != null ? override.enableHooks() : this.enableHooks,
                override.hooksConfigPath() != null
                        ? override.hooksConfigPath() : this.hooksConfigPath,
                override.hookTimeoutSeconds() != null
                        ? override.hookTimeoutSeconds() : this.hookTimeoutSeconds,
                override.maxHookOutputBytes() != null
                        ? override.maxHookOutputBytes() : this.maxHookOutputBytes,
                override.hookFailurePolicy() != null
                        ? override.hookFailurePolicy() : this.hookFailurePolicy,
                override.contextWindowTokens()!=null
                        ?override.contextWindowTokens():this.contextWindowTokens,
                override.provider()!=null?override.providerPreset():
                        override.providerPreset()!=null?override.providerPreset():this.providerPreset,
                override.provider()!=null?override.thinking():
                        override.thinking()!=null?override.thinking():this.thinking,
                override.provider()!=null?override.reasoningEffort():
                override.reasoningEffort()!=null?override.reasoningEffort():this.reasoningEffort,
                override.credentialRef()!=null?override.credentialRef():this.credentialRef,
                override.taskContinuity()!=null?override.taskContinuity():this.taskContinuity
        );
    }

    /** Known approval modes. */
    public static final String MODE_PLAN = "plan";
    public static final String MODE_READ_ONLY = "read-only";
    public static final String MODE_WORKSPACE_WRITE = "workspace-write";
    public static final String MODE_ON_REQUEST = "on-request";
    public static final String MODE_DENY = "deny";

    /** Known provider names. */
    public static final String PROVIDER_UNCONFIGURED = "unconfigured";
    public static final String PROVIDER_MOCK = "mock";
    public static final String PROVIDER_OLLAMA = "ollama";
    public static final String PROVIDER_OPENAI = "openai";

    public boolean isValidApprovalMode() {
        return MODE_PLAN.equals(approvalMode)
                || MODE_READ_ONLY.equals(approvalMode)
                || MODE_WORKSPACE_WRITE.equals(approvalMode)
                || MODE_ON_REQUEST.equals(approvalMode)
                || MODE_DENY.equals(approvalMode);
    }

    /** Returns the configured provider or a non-executable diagnostic label. */
    public String effectiveProvider() {
        return hasConfiguredProvider() ? provider : PROVIDER_UNCONFIGURED;
    }

    public boolean hasConfiguredProvider() {
        return provider != null && !provider.isBlank();
    }

    public Config withProvider(String providerName, String selectedModel,
            String endpoint, String environmentName) {
        return copyProvider(providerName, selectedModel, endpoint, environmentName, null);
    }

    public Config withModel(String selectedModel) {
        return copyProvider(provider, selectedModel, baseUrl, apiKeyEnv);
    }

    public Config withoutProvider() {
        return copyProvider(null, null, null, null, null);
    }

    public Config withSystemPrompt(String selectedSystemPrompt) {
        return new Config(workspace, commandTimeoutSeconds, maxOutputBytes,
                maxWriteFileBytes, approvalMode, maxProjectInstructionChars,
                provider, model, baseUrl, apiKeyEnv, selectedSystemPrompt,
                maxIterations, verificationCommand, verificationTimeoutSeconds,
                maxVerificationOutputBytes, maxRepairAttempts, enablePlan,
                enableVerify, enableRepair, enableSkills, skillsDirs,
                maxSkillContextChars, maxSelectedSkills, enableHooks,
                hooksConfigPath, hookTimeoutSeconds, maxHookOutputBytes,
                hookFailurePolicy,contextWindowTokens,providerPreset,thinking,reasoningEffort,
                credentialRef,taskContinuity);
    }

    public Config withApprovalMode(String selectedApprovalMode) {
        return new Config(workspace, commandTimeoutSeconds, maxOutputBytes,
                maxWriteFileBytes, selectedApprovalMode, maxProjectInstructionChars,
                provider, model, baseUrl, apiKeyEnv, systemPrompt,
                maxIterations, verificationCommand, verificationTimeoutSeconds,
                maxVerificationOutputBytes, maxRepairAttempts, enablePlan,
                enableVerify, enableRepair, enableSkills, skillsDirs,
                maxSkillContextChars, maxSelectedSkills, enableHooks,
                hooksConfigPath, hookTimeoutSeconds, maxHookOutputBytes,
                hookFailurePolicy,contextWindowTokens,providerPreset,thinking,reasoningEffort,
                credentialRef,taskContinuity);
    }

    public Config withVerification(String command, boolean enabled) {
        return new Config(workspace, commandTimeoutSeconds, maxOutputBytes,
                maxWriteFileBytes, approvalMode, maxProjectInstructionChars,
                provider, model, baseUrl, apiKeyEnv, systemPrompt,
                maxIterations, command, verificationTimeoutSeconds,
                maxVerificationOutputBytes, maxRepairAttempts, enablePlan,
                enabled, enableRepair, enableSkills, skillsDirs,
                maxSkillContextChars, maxSelectedSkills, enableHooks,
                hooksConfigPath, hookTimeoutSeconds, maxHookOutputBytes,
                hookFailurePolicy,contextWindowTokens,providerPreset,thinking,reasoningEffort,
                credentialRef,taskContinuity);
    }

    private Config copyProvider(String providerName, String selectedModel,
            String endpoint, String environmentName) {
        return copyProvider(providerName, selectedModel, endpoint, environmentName,
                providerPreset);
    }

    private Config copyProvider(String providerName, String selectedModel,
            String endpoint, String environmentName, String selectedProviderPreset) {
        return new Config(workspace, commandTimeoutSeconds, maxOutputBytes,
                maxWriteFileBytes, approvalMode, maxProjectInstructionChars,
                providerName, selectedModel, endpoint, environmentName,
                systemPrompt, maxIterations, verificationCommand,
                verificationTimeoutSeconds, maxVerificationOutputBytes,
                maxRepairAttempts, enablePlan, enableVerify, enableRepair,
                enableSkills, skillsDirs, maxSkillContextChars,
                maxSelectedSkills, enableHooks, hooksConfigPath,
                hookTimeoutSeconds, maxHookOutputBytes, hookFailurePolicy,
                contextWindowTokens,selectedProviderPreset,
                selectedProviderPreset==null?null:thinking,
                selectedProviderPreset==null?null:reasoningEffort,
                providerName==null?null:credentialRef,taskContinuity);
    }

    public Config withProviderPreset(String preset){
        return new Config(workspace,commandTimeoutSeconds,maxOutputBytes,maxWriteFileBytes,
                approvalMode,maxProjectInstructionChars,provider,model,baseUrl,apiKeyEnv,
                systemPrompt,maxIterations,verificationCommand,verificationTimeoutSeconds,
                maxVerificationOutputBytes,maxRepairAttempts,enablePlan,enableVerify,enableRepair,
                enableSkills,skillsDirs,maxSkillContextChars,maxSelectedSkills,enableHooks,
                hooksConfigPath,hookTimeoutSeconds,maxHookOutputBytes,hookFailurePolicy,
                contextWindowTokens,preset,
                "deepseek".equals(preset)?thinking:null,
                "deepseek".equals(preset)?reasoningEffort:null,credentialRef,taskContinuity);
    }

    public Config withThinking(String selectedThinking,String selectedReasoningEffort){
        return new Config(workspace,commandTimeoutSeconds,maxOutputBytes,maxWriteFileBytes,
                approvalMode,maxProjectInstructionChars,provider,model,baseUrl,apiKeyEnv,
                systemPrompt,maxIterations,verificationCommand,verificationTimeoutSeconds,
                maxVerificationOutputBytes,maxRepairAttempts,enablePlan,enableVerify,enableRepair,
                enableSkills,skillsDirs,maxSkillContextChars,maxSelectedSkills,enableHooks,
                hooksConfigPath,hookTimeoutSeconds,maxHookOutputBytes,hookFailurePolicy,
                contextWindowTokens,providerPreset,selectedThinking,selectedReasoningEffort,
                credentialRef,taskContinuity);
    }

    public Config withCredentialRef(String selectedCredentialRef) {
        return new Config(workspace,commandTimeoutSeconds,maxOutputBytes,maxWriteFileBytes,
                approvalMode,maxProjectInstructionChars,provider,model,baseUrl,apiKeyEnv,
                systemPrompt,maxIterations,verificationCommand,verificationTimeoutSeconds,
                maxVerificationOutputBytes,maxRepairAttempts,enablePlan,enableVerify,enableRepair,
                enableSkills,skillsDirs,maxSkillContextChars,maxSelectedSkills,enableHooks,
                hooksConfigPath,hookTimeoutSeconds,maxHookOutputBytes,hookFailurePolicy,
                contextWindowTokens,providerPreset,thinking,reasoningEffort,selectedCredentialRef,
                taskContinuity);
    }

    public Config withTaskContinuity(TaskContinuityConfig selectedTaskContinuity) {
        return new Config(workspace,commandTimeoutSeconds,maxOutputBytes,maxWriteFileBytes,
                approvalMode,maxProjectInstructionChars,provider,model,baseUrl,apiKeyEnv,
                systemPrompt,maxIterations,verificationCommand,verificationTimeoutSeconds,
                maxVerificationOutputBytes,maxRepairAttempts,enablePlan,enableVerify,enableRepair,
                enableSkills,skillsDirs,maxSkillContextChars,maxSelectedSkills,enableHooks,
                hooksConfigPath,hookTimeoutSeconds,maxHookOutputBytes,hookFailurePolicy,
                contextWindowTokens,providerPreset,thinking,reasoningEffort,credentialRef,
                selectedTaskContinuity);
    }

    public String effectiveThinking(){
        return thinking!=null?thinking:"deepseek".equals(providerPreset)?"enabled":"disabled";
    }

    public String effectiveReasoningEffort(){
        if(!"enabled".equals(effectiveThinking()))return null;
        return reasoningEffort!=null?reasoningEffort:"deepseek".equals(providerPreset)?"max":null;
    }

    /** Default system prompt for agent loop. */
    public static final String DEFAULT_SYSTEM_PROMPT =
            """
            You are a coding agent. Use the available tools to accomplish the user's task
            within its requested scope and the runtime's tool permissions.

            Ground findings in inspected evidence. Verify file locations with read_file
            line_numbers=true or grep before citing line numbers; otherwise cite a symbol
            or exact snippet without inventing a location.

            A change recommendation must explain both its benefit and its behavioral
            contract in the answer. Compare the original and proposed behavior at a
            concrete failure point: what work has already started, what the caller
            receives, and what work or side effects continue after failure. State any
            required error propagation, cleanup or cancellation explicitly; do not
            assume that returning an error stops already-started work. If equivalence
            depends on an unknown API contract, identify that condition instead of
            claiming a safe replacement. Apply the same comparison to partial results,
            retries and ordering when relevant. A successful-path optimization alone
            is an incomplete recommendation. Give the conclusion and supporting
            evidence, not a private reasoning transcript.

            Address each requested result with evidence or an explicit unknown. Once
            those results are supported, deliver them. Further tool use must resolve a
            specific missing fact needed for the answer; repeating an observation or
            discovering inaccessible internals is not required to report known facts
            and their limits.

            Treat Skills and reference documents as guidance with their own assumptions.
            Distinguish source claims, deductions about this task, and actual measurements.
            Attribute quantitative claims and state their applicability; do not present a
            reference's general estimate as this task's expected or measured improvement.
            Claim a test passed only after observing its result, and name its scope.

            Tool-turn commentary may briefly explain an action. When done, deliver a
            self-contained answer in the user's language with the findings or changes,
            supporting evidence, and relevant limitations. The final answer is the
            completed deliverable: do not include preparatory self-talk, private reasoning,
            or internal execution records. A concise explanation of the conclusion is
            appropriate. If blocked, explain the observed blocker and remaining work.
            """;

    public String effectiveSystemPrompt() {
        return systemPrompt != null ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
    }

    public int effectiveMaxIterations() {
        return maxIterations > 0 ? maxIterations : 10;
    }

    public boolean isTaskContinuityEnabled() {
        return taskContinuity != null && taskContinuity.explicitlyEnabled();
    }

    public String effectiveVerificationCommand() {
        return verificationCommand != null ? verificationCommand : "";
    }

    public int effectiveVerificationTimeoutSeconds() {
        return verificationTimeoutSeconds > 0
                ? verificationTimeoutSeconds : DEFAULT_VERIFICATION_TIMEOUT_SECONDS;
    }

    public int effectiveMaxVerificationOutputBytes() {
        return maxVerificationOutputBytes > 0
                ? maxVerificationOutputBytes : DEFAULT_MAX_VERIFICATION_OUTPUT_BYTES;
    }

    public int effectiveMaxRepairAttempts() {
        return maxRepairAttempts != null ? maxRepairAttempts : DEFAULT_MAX_REPAIR_ATTEMPTS;
    }

    public boolean isPlanEnabled() {
        return Boolean.TRUE.equals(enablePlan);
    }

    public boolean isVerifyEnabled() {
        return enableVerify == null || enableVerify;
    }

    public boolean isRepairEnabled() {
        return enableRepair == null || enableRepair;
    }

    public boolean isSkillsEnabled() {
        return enableSkills == null || enableSkills;
    }

    public List<String> effectiveSkillsDirs() {
        return skillsDirs != null ? skillsDirs : DEFAULT_SKILLS_DIRS;
    }

    public int effectiveMaxSkillContextChars() {
        return maxSkillContextChars != null
                ? maxSkillContextChars : DEFAULT_MAX_SKILL_CONTEXT_CHARS;
    }

    public int effectiveMaxSelectedSkills() {
        return maxSelectedSkills != null
                ? maxSelectedSkills : DEFAULT_MAX_SELECTED_SKILLS;
    }

    public Integer effectiveContextWindowTokens(){
        return contextWindowTokens!=null?contextWindowTokens:
                PROVIDER_OPENAI.equals(provider)&&"deepseek".equals(providerPreset)
                        ?1_000_000:null;
    }

    public boolean isHooksEnabled() {
        return enableHooks != null && enableHooks;
    }

    public String effectiveHooksConfigPath() {
        return hooksConfigPath != null ? hooksConfigPath : DEFAULT_HOOKS_CONFIG_PATH;
    }

    public int effectiveHookTimeoutSeconds() {
        return hookTimeoutSeconds != null ? hookTimeoutSeconds : DEFAULT_HOOK_TIMEOUT_SECONDS;
    }

    public int effectiveMaxHookOutputBytes() {
        return maxHookOutputBytes != null ? maxHookOutputBytes : DEFAULT_MAX_HOOK_OUTPUT_BYTES;
    }

    public String effectiveHookFailurePolicy() {
        return hookFailurePolicy != null ? hookFailurePolicy : DEFAULT_HOOK_FAILURE_POLICY;
    }
}
