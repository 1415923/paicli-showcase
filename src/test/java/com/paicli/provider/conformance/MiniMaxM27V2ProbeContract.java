package com.paicli.provider.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.config.Config;
import com.paicli.credential.ProviderCredentialResolver;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;
import com.paicli.provider.ProviderRetrySignal;
import com.paicli.provider.ProviderRetrySignalSource;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.provider.telemetry.ProviderHttpStatusSource;
import com.paicli.provider.telemetry.ProviderObservationSource;
import com.paicli.provider.telemetry.ProviderTransportEvidence;
import com.paicli.provider.telemetry.ProviderTransportEvidenceSource;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolAuthorityProjection;
import com.paicli.tool.ToolResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only frozen contract for the MiniMax M2.7 live conformance probe. */
final class MiniMaxM27V2ProbeContract {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String CAMPAIGN = "provider-b-minimax-m27-live-conformance-probe-v2";
    static final String RESULT_SCHEMA = "provider-b-minimax-m27-live-result-v2";
    static final String MODEL = "MiniMax-M2.7";
    static final String BASE_URL = "https://api.minimaxi.com";
    static final String AUTHORIZATION =
            "AUTHORIZE_MINIMAX_M27_CHAT_CONFORMANCE_PAYGO_PAID_V2_CNY_3_00_ONE_SHOT";
    static final int CONTEXT_WINDOW = 204_800;
    static final int ATTEMPT_CAP = 12;
    static final Duration WALL_CLOCK = Duration.ofSeconds(900);
    static final BigDecimal HARD_BUDGET = new BigDecimal("3.00");
    static final BigDecimal RESERVE = new BigDecimal("0.50");
    static final BigDecimal MISSING_USAGE_DEBIT = new BigDecimal("0.50");
    static final String SYSTEM_PROMPT = Config.DEFAULT_SYSTEM_PROMPT;
    static final Path CAMPAIGN_ROOT = Path.of("benchmarks", "provider-conformance",
            "minimax-m27-chat-v2");

    private MiniMaxM27V2ProbeContract() { }

    static Config config(Path workspace, String baseUrl) {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_READ_ONLY, 10_000, Config.PROVIDER_OPENAI, MODEL,
                baseUrl, "MINIMAX_API_KEY", SYSTEM_PROMPT, 8,
                "", 30, 16_384, 1, false, false, false,
                false, Config.DEFAULT_SKILLS_DIRS, Config.DEFAULT_MAX_SKILL_CONTEXT_CHARS,
                Config.DEFAULT_MAX_SELECTED_SKILLS, false,
                Config.DEFAULT_HOOKS_CONFIG_PATH, Config.DEFAULT_HOOK_TIMEOUT_SECONDS,
                Config.DEFAULT_MAX_HOOK_OUTPUT_BYTES, Config.DEFAULT_HOOK_FAILURE_POLICY,
                CONTEXT_WINDOW, null, null, null,
                "PaiCLI/provider/minimax/m27-paygo", null);
    }

    record CredentialFact(boolean credentialPresent, String credentialSource,
            boolean credentialValuePersisted) { }

    static CredentialFact credentialFact(ProviderCredentialResolver resolver, Config config) {
        ProviderCredentialResolver.ResolutionStatus status = resolver.status(config);
        String source = status.present()
                ? "environment".equals(status.source())
                ? "environment" : "configured_secure_reference"
                : "absent";
        return new CredentialFact(status.present(), source, false);
    }

    static final class PriceCalculator {
        private static final BigDecimal MILLION = new BigDecimal("1000000");
        private final Rates rates;

        PriceCalculator(JsonNode snapshot) {
            if (!"official-pay-as-you-go-standard-table".equals(snapshot.path(
                    "settlement_authority").path("kind").asText())
                    || !"standard".equals(snapshot.path("service_tier").asText())) {
                throw new IllegalArgumentException(
                        "MiniMax pay-as-you-go Standard table is required");
            }
            rates = Rates.from(snapshot.path("rates"));
            if (!rates.equals(new Rates("2.10", "0.42", "2.625", "8.40"))) {
                throw new IllegalArgumentException("Frozen MiniMax price snapshot drift");
            }
        }

        BigDecimal settle(int input, int output, Integer cacheRead, Integer cacheWrite) {
            if (input < 0 || output < 0) throw new IllegalArgumentException("negative usage");
            int cached = cacheRead == null ? 0 : cacheRead;
            if (cached < 0 || cached > input) throw new IllegalArgumentException("cached usage");
            int written;
            int ordinary;
            if (cacheWrite == null) {
                written = input - cached;
                ordinary = 0;
            } else {
                written = cacheWrite;
                if (written < 0 || cached + written > input) {
                    throw new IllegalArgumentException("cache-write usage");
                }
                ordinary = input - cached - written;
            }
            return rates.input.multiply(BigDecimal.valueOf(ordinary))
                    .add(rates.cachedInput.multiply(BigDecimal.valueOf(cached)))
                    .add(rates.cacheWrite.multiply(BigDecimal.valueOf(written)))
                    .add(rates.output.multiply(BigDecimal.valueOf(output)))
                    .divide(MILLION, 6, RoundingMode.HALF_UP);
        }

        private record Rates(BigDecimal input, BigDecimal cachedInput,
                BigDecimal cacheWrite, BigDecimal output) {
            Rates(String input, String cachedInput, String cacheWrite, String output) {
                this(new BigDecimal(input), new BigDecimal(cachedInput),
                        new BigDecimal(cacheWrite), new BigDecimal(output));
            }
            static Rates from(JsonNode value) {
                return new Rates(value.path("input").asText(),
                        value.path("cached_input").asText(),
                        value.path("cache_write").asText(),
                        value.path("output").asText());
            }
        }
    }

    static final class CampaignBudget {
        private final PriceCalculator calculator;
        private final Instant started = Instant.now();
        private BigDecimal settled = BigDecimal.ZERO.setScale(6);
        private int attempts;
        private boolean stopped;

        CampaignBudget(PriceCalculator calculator) {
            this.calculator = calculator;
        }

        synchronized int admit() {
            if (stopped) throw new ProbeStopException("campaign already stopped");
            if (Duration.between(started, Instant.now()).compareTo(WALL_CLOCK) > 0) {
                stopped = true;
                throw new ProbeStopException("wall-clock cap reached");
            }
            if (attempts >= ATTEMPT_CAP) {
                stopped = true;
                throw new ProbeStopException("external attempt cap reached");
            }
            if (settled.add(RESERVE).compareTo(HARD_BUDGET) > 0) {
                stopped = true;
                throw new ProbeStopException("next-attempt reserve unavailable");
            }
            attempts++;
            return attempts;
        }

        synchronized Settlement settle(ProviderCallObservation observation,
                ProviderTransportEvidence transport) {
            boolean noRequest = transport != null && transport.transportState()
                    == ProviderTransportEvidence.TransportState.PRE_TRANSPORT_REJECTED;
            boolean complete = observation != null
                    && observation.usageSource()
                    == ProviderCallObservation.UsageSource.PROVIDER_REPORTED
                    && observation.usageCoverage()
                    == ProviderCallObservation.UsageCoverage.COMPLETE
                    && observation.inputTokens() != null && observation.outputTokens() != null;
            BigDecimal debit;
            if (noRequest) debit = BigDecimal.ZERO.setScale(6);
            else if (complete) debit = calculator.settle(observation.inputTokens(),
                    observation.outputTokens(), observation.cacheReadTokens(),
                    observation.cacheWriteTokens());
            else {
                debit = MISSING_USAGE_DEBIT.setScale(6);
                stopped = true;
            }
            settled = settled.add(debit).setScale(6, RoundingMode.HALF_UP);
            if (settled.compareTo(HARD_BUDGET) > 0) {
                stopped = true;
                throw new ProbeStopException("hard budget exceeded");
            }
            return new Settlement(debit, settled, complete, !complete && !noRequest);
        }

        synchronized BigDecimal settled() { return settled; }
        synchronized int attempts() { return attempts; }
    }

    record Settlement(BigDecimal debit, BigDecimal total, boolean usageComplete,
            boolean stopAfterAttempt) { }

    static final class ProbeStopException extends RuntimeException {
        ProbeStopException(String message) { super(message); }
    }

    static final class EvidenceWriter {
        private final Path result;
        private final String mode;
        private final String identityManifestSha256;
        private final CredentialFact credential;

        EvidenceWriter(Path evidenceRoot, String mode, String identityManifestSha256,
                CredentialFact credential) {
            this.result = evidenceRoot.resolve("result.jsonl").toAbsolutePath().normalize();
            this.mode = mode;
            this.identityManifestSha256 = identityManifestSha256;
            this.credential = credential;
        }

        synchronized void append(ObjectNode row) {
            row.put("schema_version", RESULT_SCHEMA);
            row.put("campaign", CAMPAIGN);
            row.put("mode", mode);
            row.put("credential_present", credential.credentialPresent());
            row.put("credential_source", credential.credentialSource());
            row.put("credential_value_persisted", false);
            row.put("identity_manifest_sha256", identityManifestSha256);
            try {
                Files.createDirectories(result.getParent());
                byte[] bytes = (JSON.writeValueAsString(row) + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(result,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
                     var ignored = channel.lock()) {
                    channel.write(ByteBuffer.wrap(bytes));
                    channel.force(true);
                }
            } catch (IOException error) {
                throw new ProbeStopException("evidence append/fsync failed");
            }
        }

        Path path() { return result; }
    }

    /** Attempt-level wrapper; it preserves all production one-shot telemetry seams. */
    static final class BudgetedEvidenceProvider implements PreparedTurnProvider,
            ProviderObservationSource, ProviderHttpStatusSource,
            ProviderTransportEvidenceSource, ProviderRetrySignalSource {
        private final ModelProvider delegate;
        private final String fixture;
        private final CampaignBudget budget;
        private final EvidenceWriter evidence;
        private final AtomicInteger logicalTurns = new AtomicInteger();
        private final AtomicInteger observedToolCalls = new AtomicInteger();
        private final AtomicInteger finalResponses = new AtomicInteger();
        private final List<String> failureTaxonomies = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final ThreadLocal<ProviderCallObservation> observation = new ThreadLocal<>();
        private final ThreadLocal<Integer> status = new ThreadLocal<>();
        private final ThreadLocal<ProviderTransportEvidence> transport = new ThreadLocal<>();
        private final ThreadLocal<ProviderRetrySignal> retry = new ThreadLocal<>();

        BudgetedEvidenceProvider(ModelProvider delegate, String fixture,
                CampaignBudget budget, EvidenceWriter evidence) {
            this.delegate = delegate;
            this.fixture = fixture;
            this.budget = budget;
            this.evidence = evidence;
        }

        @Override public PreparedProviderTurn prepareTurn(String operation,
                List<com.paicli.model.AgentMessage> messages,
                List<ToolDefinition> tools) {
            int logicalTurn = logicalTurns.incrementAndGet();
            AtomicInteger attempt = new AtomicInteger();
            PreparedProviderTurn prepared = delegate instanceof PreparedTurnProvider source
                    ? source.prepareTurn(operation, messages, tools)
                    : new PreparedProviderTurn("0".repeat(64),
                            () -> delegate.chatForOperation(operation, messages, tools));
            return new PreparedProviderTurn(prepared.requestDigest(), () -> execute(
                    prepared, logicalTurn, attempt.incrementAndGet()));
        }

        private ModelResponse execute(PreparedProviderTurn prepared, int logicalTurn,
                int attempt) {
            int ordinal = budget.admit();
            long started = System.nanoTime();
            ModelResponse response = null;
            RuntimeException failure = null;
            try {
                response = prepared.execute();
                return response;
            } catch (RuntimeException error) {
                failure = error;
                throw error;
            } finally {
                ProviderCallObservation observed = delegate instanceof ProviderObservationSource source
                        ? nullSafe(source.takeObservation())
                        : ProviderCallObservation.notExposed();
                Integer http = delegate instanceof ProviderHttpStatusSource source
                        ? source.takeHttpStatus() : null;
                ProviderTransportEvidence terminal = delegate
                        instanceof ProviderTransportEvidenceSource source
                        ? source.takeTransportEvidence()
                        : ProviderTransportEvidence.unknownTerminal(
                                "TRANSPORT_EVIDENCE_SOURCE_UNAVAILABLE");
                ProviderRetrySignal signal = delegate instanceof ProviderRetrySignalSource source
                        ? source.takeRetrySignal() : null;
                observation.set(observed);
                status.set(http);
                transport.set(terminal);
                retry.set(signal);
                Settlement settlement = budget.settle(observed, terminal);
                String outcome = typedOutcome(response, failure, terminal);
                String taxonomy = failureTaxonomy(fixture, logicalTurn, http, outcome, signal);
                if (observed.toolCallCount() != null) {
                    observedToolCalls.addAndGet(observed.toolCallCount());
                }
                if ("FINAL".equals(outcome)) finalResponses.incrementAndGet();
                if (taxonomy != null) failureTaxonomies.add(taxonomy);
                ObjectNode row = JSON.createObjectNode();
                row.put("record_kind", "ATTEMPT");
                row.put("ordinal", ordinal);
                row.put("fixture", fixture);
                row.put("logical_turn", logicalTurn);
                row.put("attempt", attempt);
                if (http == null) row.putNull("http_status"); else row.put("http_status", http);
                if (outcome == null) row.putNull("typed_outcome"); else row.put("typed_outcome", outcome);
                if (observed.safeRequestId() == null) row.putNull("response_identity");
                else row.put("response_identity", observed.safeRequestId());
                row.put("response_identity_provenance",
                        observed.responseIdentityProvenance().name());
                putNullable(row, "input_tokens", observed.inputTokens());
                putNullable(row, "output_tokens", observed.outputTokens());
                putNullable(row, "total_tokens", observed.totalTokens());
                putNullable(row, "cache_read_tokens", observed.cacheReadTokens());
                putNullable(row, "cache_write_tokens", observed.cacheWriteTokens());
                row.put("usage_complete", settlement.usageComplete());
                row.put("latency_ms", Math.max(0L,
                        (System.nanoTime() - started) / 1_000_000L));
                row.put("budget_debit_cny", money(settlement.debit()));
                row.put("settled_budget_cny", money(settlement.total()));
                row.put("tool_call_count", observed.toolCallCount() == null
                        ? 0 : observed.toolCallCount());
                row.put("tool_effect_count", 0);
                row.put("not_executed_count", 0);
                row.put("verdict", taxonomy == null ? "PASS" : "FAIL");
                if (taxonomy == null) row.putNull("failure_taxonomy");
                else row.put("failure_taxonomy", taxonomy);
                evidence.append(row);
                if (settlement.stopAfterAttempt()) {
                    throw new ProbeStopException("sent attempt lacked complete usage");
                }
            }
        }

        @Override public ProviderCallObservation takeObservation() {
            ProviderCallObservation value = nullSafe(observation.get());
            observation.remove();
            return value;
        }
        @Override public Integer takeHttpStatus() { Integer value=status.get();status.remove();return value; }
        @Override public ProviderTransportEvidence takeTransportEvidence() {
            ProviderTransportEvidence value=transport.get();transport.remove();return value;
        }
        @Override public ProviderRetrySignal takeRetrySignal() {
            ProviderRetrySignal value=retry.get();retry.remove();return value;
        }
        int logicalTurns() { return logicalTurns.get(); }
        int observedToolCalls() { return observedToolCalls.get(); }
        int finalResponses() { return finalResponses.get(); }
        List<String> failureTaxonomies() { return List.copyOf(failureTaxonomies); }

        private static ProviderCallObservation nullSafe(ProviderCallObservation value) {
            return value == null ? ProviderCallObservation.notExposed() : value;
        }
        private static String typedOutcome(ModelResponse response, RuntimeException failure,
                ProviderTransportEvidence transport) {
            if (transport != null && transport.transportState()
                    == ProviderTransportEvidence.TransportState.PRE_TRANSPORT_REJECTED) {
                return "PRE_TRANSPORT_REJECTED";
            }
            if (response == null) return failure == null ? null : "RUNTIME_EXCEPTION";
            return switch (response.type()) {
                case FINAL -> "FINAL";
                case TOOL_CALL -> "TOOL_CALL";
                case PROTOCOL_ERROR -> "PROTOCOL_ERROR";
                case PROVIDER_ERROR -> response.providerErrorKind().name();
            };
        }
        private static String failureTaxonomy(String fixture, int logicalTurn, Integer http,
                String outcome, ProviderRetrySignal signal) {
            if (http != null && (http == 401 || http == 403)) {
                return "AUTH_ACCOUNT_MODEL_ACCESS";
            }
            if (http != null && http == 404) return "ENDPOINT_INCOMPATIBLE";
            if (http != null && http == 400) {
                if ("F1".equals(fixture)) return "REQUEST_SCHEMA_INCOMPATIBLE";
                return logicalTurn == 1 ? "TOOL_SCHEMA_REJECTED"
                        : "TOOL_RESULT_CONTINUATION_REJECTED";
            }
            if (signal != null && signal.retryable()) return "RATE_LIMIT_TRANSIENT_TRANSPORT";
            if ("PRE_TRANSPORT_REJECTED".equals(outcome)) return "PRODUCT_RUNTIME_ERROR";
            if ("PROVIDER_RESPONSE_PARSE_FAILURE".equals(outcome)) {
                return logicalTurn > 1 ? "TOOL_RESULT_CONTINUATION_REJECTED"
                        : "TOOL_RESPONSE_PARSE_INCOMPATIBLE";
            }
            if (outcome != null && (outcome.startsWith("PROVIDER_")
                    || "PROTOCOL_ERROR".equals(outcome) || "RUNTIME_EXCEPTION".equals(outcome))) {
                return "PRODUCT_RUNTIME_ERROR";
            }
            return null;
        }
    }

    static final class SyntheticTool implements Tool {
        private final String name;
        private final String description;
        private final JsonNode schema;
        private final EffectClass effectClass;
        private final AtomicInteger executions;

        SyntheticTool(String name, String description, JsonNode schema,
                EffectClass effectClass, AtomicInteger executions) {
            this.name = name;
            this.description = description;
            this.schema = schema.deepCopy();
            this.effectClass = effectClass;
            this.executions = executions;
        }
        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public ToolDefinition toDefinition() {
            return new ToolDefinition(name, description, schema.deepCopy());
        }
        @Override public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call) {
            return Optional.of(ToolAuthorityProjection.exactPath(name, effectClass,
                    "synthetic/" + name, null, false,
                    effectClass == EffectClass.WRITE ? Boolean.TRUE : null));
        }
        @Override public ToolResult execute(ToolCall call) {
            executions.incrementAndGet();
            return ToolResult.success(switch (name) {
                case "lookup_fixture_fact" -> "alpha=7";
                case "lookup_fixture_label" -> "beta=minimax";
                default -> "effect-counter-incremented";
            });
        }
    }

    static String money(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    static JsonNode load(Path project, String file) {
        try {
            return JSON.readTree(project.resolve(CAMPAIGN_ROOT).resolve(file).toFile());
        } catch (IOException error) {
            throw new IllegalStateException("campaign file unavailable: " + file, error);
        }
    }

    private static void putNullable(ObjectNode row, String field, Integer value) {
        if (value == null) row.putNull(field); else row.put(field, value);
    }
}
