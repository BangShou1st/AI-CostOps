package com.aicostops.gateway.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.metering.GatewayUsageFinalizationService;
import com.aicostops.gateway.metering.GatewayUsageObservation;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatAdapter;
import com.aicostops.gateway.provider.ProviderChatAdapterRegistry;
import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.provider.ProviderCredentialDecryptor;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.aicostops.gateway.request.GatewayRequestLifecycleService;
import com.aicostops.gateway.request.GatewayRequestOrchestrator;
import com.aicostops.gateway.request.GatewayRequestService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AdvisorInferenceWorkerTest {

    @Mock
    private AdvisorJobMapper jobs;
    @Mock
    private GatewayReadMapper readMapper;
    @Mock
    private GatewayRequestOrchestrator orchestrator;
    @Mock
    private GatewayRequestLifecycleService lifecycleService;
    @Mock
    private ProviderChatAdapterRegistry adapterRegistry;
    @Mock
    private ProviderCredentialDecryptor credentialDecryptor;
    @Mock
    private GatewayUsageFinalizationService usageFinalization;
    @Mock
    private PlatformTransactionManager transactionManager;

    private final Clock clock =
            Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneId.of("UTC"));

    private AdvisorInferenceWorker worker() {
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        return new AdvisorInferenceWorker(jobs, readMapper, orchestrator, lifecycleService,
                adapterRegistry, credentialDecryptor, usageFinalization, new ObjectMapper(),
                clock, transactionManager);
    }

    @Test
    void idleTickClaimsNothing() {
        when(jobs.claimEligibleAny(any())).thenReturn(null);
        worker().tick();
        verify(jobs).reclaimOrphans(any());
        verify(jobs, never()).markCompleted(anyLong(), anyString(), any());
    }

    @Test
    void happyPathLinksDispatchAndCompletes() {
        var worker = worker();
        var job = jobRow();
        when(jobs.claimEligibleAny(any())).thenReturn(job).thenReturn(null);
        when(jobs.markClaimed(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(jobs.findJob(eq(100L), eq(7L))).thenReturn(job);
        when(jobs.findProfileById(eq(7L), eq(10L))).thenReturn(profileRow());
        when(jobs.findCredentialForProfile(eq(7L), eq(10L))).thenReturn(credentialRow());
        when(jobs.findLogicalModelOf(21L)).thenReturn(9L);
        when(jobs.findEvidenceSnapshot(eq(100L), eq(7L))).thenReturn(snapshotRow());
        var dispatch = new GatewayRequestService.DispatchResult(11L, "public-1", 12L, "decision-1",
                13L, 14L, 15L, 16L, "USD", "https://provider.example.test", "CUSTOM_OPENAI_COMPATIBLE",
                "model-x", 9L, 1024, 512, 17L, 18L, "/chat/completions", "OPENAI_CHAT_COMPLETIONS",
                "DIRECT_PUBLIC_ONLY");
        var prepared = new GatewayRequestOrchestrator.PreparedDispatch(dispatch,
                new GatewayPrincipal(5L, 7L, 6L, "SERVICE", null, 4L, "PROJECT", 6L, "OPTIONAL"),
                new GatewayRequestService.AuthorizeCommand(null, 9L, new byte[0], "k", 1024L, false),
                null, java.util.Set.of());
        when(orchestrator.prepareInitial(any(), eq(false))).thenReturn(Mono.just(prepared));
        when(jobs.linkGateway(anyLong(), anyString(), anyString(), anyLong(), any())).thenReturn(1);
        when(credentialDecryptor.decrypt(7L, 14L)).thenReturn(
                new ProviderCredentialDecryptor.DecryptedCredential("BEARER_TOKEN", "s3cr3t".getBytes()));
        when(readMapper.findActiveConnectionProfile(7L, 14L)).thenReturn(
                new GatewayReadMapper.ConnectionProfileRow("BEARER", null, "DIRECT_PUBLIC_ONLY"));
        when(lifecycleService.beginUpstream(11L, 7L, 12L)).thenReturn(Mono.empty());
        var adapter = org.mockito.Mockito.mock(ProviderChatAdapter.class);
        when(adapterRegistry.require("CUSTOM_OPENAI_COMPATIBLE")).thenReturn(adapter);
        var completion = new ProviderChatCompletion("req-1", "cmpl-1", 1L, "model-x",
                List.of(new ProviderChatCompletion.CompletionChoice(0, narrative(), "stop")),
                new ProviderChatCompletion.ProviderUsage(5, 3, 8));
        when(adapter.complete(any(ProviderCallContext.class), any(ChatCompletionCommand.class)))
                .thenReturn(Mono.just(completion));
        when(usageFinalization.finalizeSuccess(eq(11L), eq(7L), eq(12L), any()))
                .thenReturn(Mono.just(org.mockito.Mockito.mock(
                        GatewayUsageFinalizationService.FinalizationResult.class)));
        when(jobs.markCompleted(anyLong(), anyString(), any())).thenReturn(1);

        worker.tick();

        verify(jobs).linkGateway(eq(100L), anyString(), eq("DISPATCHING"), eq(11L), any());
        verify(jobs).insertExplanation(eq(7L), eq(100L), eq(1), eq("Spend rose."), anyString(),
                anyString(), anyString(), anyString(), any());
        verify(jobs).markCompleted(eq(100L), anyString(), any());
    }

    @Test
    void invalidNarrativeFailsWithoutCompleting() {
        var worker = worker();
        when(jobs.claimEligibleAny(any())).thenReturn(jobRow()).thenReturn(null);
        when(jobs.markClaimed(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(jobs.findJob(eq(100L), eq(7L))).thenReturn(jobRow());
        when(jobs.findProfileById(eq(7L), eq(10L))).thenReturn(profileRow());
        when(jobs.findCredentialForProfile(eq(7L), eq(10L))).thenReturn(credentialRow());
        when(jobs.findLogicalModelOf(21L)).thenReturn(9L);
        when(jobs.findEvidenceSnapshot(eq(100L), eq(7L))).thenReturn(snapshotRow());
        var dispatch = dispatch();
        var prepared = new GatewayRequestOrchestrator.PreparedDispatch(dispatch,
                new GatewayPrincipal(5L, 7L, 6L, "SERVICE", null, 4L, "PROJECT", 6L, "OPTIONAL"),
                new GatewayRequestService.AuthorizeCommand(null, 9L, new byte[0], "k", 1024L, false),
                null, java.util.Set.of());
        when(orchestrator.prepareInitial(any(), eq(false))).thenReturn(Mono.just(prepared));
        when(jobs.linkGateway(anyLong(), anyString(), anyString(), anyLong(), any())).thenReturn(1);
        when(credentialDecryptor.decrypt(7L, 14L)).thenReturn(
                new ProviderCredentialDecryptor.DecryptedCredential("BEARER_TOKEN", "s3cr3t".getBytes()));
        when(readMapper.findActiveConnectionProfile(7L, 14L)).thenReturn(
                new GatewayReadMapper.ConnectionProfileRow("BEARER", null, "DIRECT_PUBLIC_ONLY"));
        when(lifecycleService.beginUpstream(11L, 7L, 12L)).thenReturn(Mono.empty());
        var adapter = org.mockito.Mockito.mock(ProviderChatAdapter.class);
        when(adapterRegistry.require("CUSTOM_OPENAI_COMPATIBLE")).thenReturn(adapter);
        var completion = new ProviderChatCompletion("req-1", "cmpl-1", 1L, "model-x",
                List.of(new ProviderChatCompletion.CompletionChoice(0, "{\"savingAmount\":\"3.00\"}",
                        "stop")),
                new ProviderChatCompletion.ProviderUsage(5, 3, 8));
        when(adapter.complete(any(ProviderCallContext.class), any(ChatCompletionCommand.class)))
                .thenReturn(Mono.just(completion));
        when(usageFinalization.finalizeSuccess(eq(11L), eq(7L), eq(12L), any()))
                .thenReturn(Mono.just(org.mockito.Mockito.mock(
                        GatewayUsageFinalizationService.FinalizationResult.class)));
        when(jobs.markFailed(anyLong(), anyString(), anyString(), any())).thenReturn(1);

        worker.tick();

        verify(jobs).markFailed(eq(100L), anyString(), eq("INVALID_RESPONSE"), any());
        verify(jobs, never()).markCompleted(anyLong(), anyString(), any());
    }

    @Test
    void providerTimeoutFailsAndFinalizes() {
        var worker = worker();
        when(jobs.claimEligibleAny(any())).thenReturn(jobRow()).thenReturn(null);
        when(jobs.markClaimed(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(jobs.findJob(eq(100L), eq(7L))).thenReturn(jobRow());
        when(jobs.findProfileById(eq(7L), eq(10L))).thenReturn(profileRow());
        when(jobs.findCredentialForProfile(eq(7L), eq(10L))).thenReturn(credentialRow());
        when(jobs.findLogicalModelOf(21L)).thenReturn(9L);
        when(jobs.findEvidenceSnapshot(eq(100L), eq(7L))).thenReturn(snapshotRow());
        var dispatch = dispatch();
        var prepared = new GatewayRequestOrchestrator.PreparedDispatch(dispatch,
                new GatewayPrincipal(5L, 7L, 6L, "SERVICE", null, 4L, "PROJECT", 6L, "OPTIONAL"),
                new GatewayRequestService.AuthorizeCommand(null, 9L, new byte[0], "k", 1024L, false),
                null, java.util.Set.of());
        when(orchestrator.prepareInitial(any(), eq(false))).thenReturn(Mono.just(prepared));
        when(jobs.linkGateway(anyLong(), anyString(), anyString(), anyLong(), any())).thenReturn(1);
        when(credentialDecryptor.decrypt(7L, 14L)).thenReturn(
                new ProviderCredentialDecryptor.DecryptedCredential("BEARER_TOKEN", "s3cr3t".getBytes()));
        when(readMapper.findActiveConnectionProfile(7L, 14L)).thenReturn(
                new GatewayReadMapper.ConnectionProfileRow("BEARER", null, "DIRECT_PUBLIC_ONLY"));
        when(lifecycleService.beginUpstream(11L, 7L, 12L)).thenReturn(Mono.empty());
        var adapter = org.mockito.Mockito.mock(ProviderChatAdapter.class);
        when(adapterRegistry.require("CUSTOM_OPENAI_COMPATIBLE")).thenReturn(adapter);
        when(adapter.complete(any(ProviderCallContext.class), any(ChatCompletionCommand.class)))
                .thenReturn(Mono.error(new ProviderExecutionException(
                        ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                        ProviderSafetyReason.HEADER_TIMEOUT_WRITE_POSSIBLE,
                        ProviderHealthSignal.QUALIFYING_FAILURE, null, null, true, null)));
        when(usageFinalization.finalizeFailure(eq(11L), eq(7L), eq(12L), any(), any()))
                .thenReturn(Mono.just(org.mockito.Mockito.mock(
                        GatewayUsageFinalizationService.FinalizationResult.class)));
        when(jobs.markFailed(anyLong(), anyString(), anyString(), any())).thenReturn(1);

        worker.tick();

        verify(usageFinalization).finalizeFailure(eq(11L), eq(7L), eq(12L), any(), any());
        verify(jobs).markFailed(eq(100L), anyString(), eq("BILLABLE_UNCERTAIN"), any());
    }

    @Test
    void stuckLinkedJobsConvergeWithoutRedispatch() {
        var worker = worker();
        var billable = stuckJob(1L, 200L, "FAILED_AFTER_DISPATCH");
        var active = stuckJob(2L, 201L, "UPSTREAM_ACTIVE");
        var done = stuckJob(3L, 202L, "TRANSPORT_COMPLETED");
        when(jobs.stuckLinked(any())).thenReturn(java.util.List.of(billable, active, done));
        when(jobs.findGatewayRequestState(200L)).thenReturn("FAILED_AFTER_DISPATCH");
        when(jobs.findGatewayRequestState(201L)).thenReturn("UPSTREAM_ACTIVE");
        when(jobs.findGatewayRequestState(202L)).thenReturn("TRANSPORT_COMPLETED");
        when(jobs.markFailed(anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(jobs.claimEligibleAny(any())).thenReturn(null);

        worker.tick();

        verify(jobs).markFailed(eq(1L), anyString(), eq("BILLABLE_UNCERTAIN"), any());
        verify(jobs).extendLease(eq(2L), any());
        verify(jobs).markFailed(eq(3L), anyString(), eq("EXPLANATION_PENDING"), any());
    }

    @Test
    void supersededProfileNeverDispatches() {
        var worker = worker();
        when(jobs.claimEligibleAny(any())).thenReturn(jobRow()).thenReturn(null);
        when(jobs.markClaimed(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(jobs.findJob(eq(100L), eq(7L))).thenReturn(jobRow());
        when(jobs.findProfileById(eq(7L), eq(10L))).thenReturn(new AdvisorJobMapper.ProfileRow(
                10L, 7L, 1, 21L, 6L, "PROJECT", 6L, "OPTIONAL", "RETIRED"));
        when(jobs.markFailed(anyLong(), anyString(), anyString(), any())).thenReturn(1);

        worker.tick();

        verify(jobs).markFailed(eq(100L), anyString(), eq("PROFILE_SUPERSEDED"), any());
        verify(adapterRegistry, never()).require(anyString());
        verify(jobs, never()).linkGateway(anyLong(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void tamperedSnapshotFailsBeforeProviderIo() {
        var worker = worker();
        when(jobs.claimEligibleAny(any())).thenReturn(jobRow()).thenReturn(null);
        when(jobs.markClaimed(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(jobs.findJob(eq(100L), eq(7L))).thenReturn(jobRow());
        when(jobs.findProfileById(eq(7L), eq(10L))).thenReturn(profileRow());
        when(jobs.findCredentialForProfile(eq(7L), eq(10L))).thenReturn(credentialRow());
        when(jobs.findLogicalModelOf(21L)).thenReturn(9L);
        var good = snapshotRow();
        var tampered = new AdvisorJobMapper.SnapshotRow(good.id(), good.orgId(), good.jobId(),
                good.schemaVersion(), good.subjectType(), good.subjectId(), good.currency(),
                good.factsJson(), good.driversJson(), good.summaryJson(), "0".repeat(64),
                good.generatedAt(), good.createdAt());
        when(jobs.findEvidenceSnapshot(eq(100L), eq(7L))).thenReturn(tampered);
        when(jobs.markFailed(anyLong(), anyString(), anyString(), any())).thenReturn(1);

        worker.tick();

        verify(jobs).markFailed(eq(100L), anyString(), eq("EVIDENCE_INTEGRITY_FAILED"), any());
        verify(adapterRegistry, never()).require(anyString());
        verify(jobs, never()).linkGateway(anyLong(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void legacyJobWithoutProfileBindingResolvesThroughStoredVersion() {
        var worker = worker();
        var legacy = new AdvisorJobMapper.JobRow(100L, 7L, 8L, "ANOMALY", 3L, snapshotFingerprint(), "[]", 1,
                null, "PENDING", null, null, null, 1, null, Instant.now(), null, null);
        when(jobs.claimEligibleAny(any())).thenReturn(legacy).thenReturn(null);
        when(jobs.markClaimed(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(jobs.findJob(eq(100L), eq(7L))).thenReturn(legacy);
        when(jobs.findProfileByOrgVersion(eq(7L), eq(1))).thenReturn(profileRow());
        when(jobs.findCredentialForProfile(eq(7L), eq(10L))).thenReturn(credentialRow());
        when(jobs.findLogicalModelOf(21L)).thenReturn(9L);
        when(jobs.findEvidenceSnapshot(eq(100L), eq(7L))).thenReturn(snapshotRow());
        var dispatch = dispatch();
        var prepared = new GatewayRequestOrchestrator.PreparedDispatch(dispatch,
                new GatewayPrincipal(5L, 7L, 6L, "SERVICE", null, 4L, "PROJECT", 6L, "OPTIONAL"),
                new GatewayRequestService.AuthorizeCommand(null, 9L, new byte[0], "k", 1024L, false),
                null, java.util.Set.of());
        when(orchestrator.prepareInitial(any(), eq(false))).thenReturn(Mono.just(prepared));
        when(jobs.linkGateway(anyLong(), anyString(), anyString(), anyLong(), any())).thenReturn(1);
        when(credentialDecryptor.decrypt(7L, 14L)).thenReturn(
                new ProviderCredentialDecryptor.DecryptedCredential("BEARER_TOKEN", "s3cr3t".getBytes()));
        when(readMapper.findActiveConnectionProfile(7L, 14L)).thenReturn(
                new GatewayReadMapper.ConnectionProfileRow("BEARER", null, "DIRECT_PUBLIC_ONLY"));
        when(lifecycleService.beginUpstream(11L, 7L, 12L)).thenReturn(Mono.empty());
        var adapter = org.mockito.Mockito.mock(ProviderChatAdapter.class);
        when(adapterRegistry.require("CUSTOM_OPENAI_COMPATIBLE")).thenReturn(adapter);
        var completion = new ProviderChatCompletion("req-1", "cmpl-1", 1L, "model-x",
                List.of(new ProviderChatCompletion.CompletionChoice(0, narrative(), "stop")),
                new ProviderChatCompletion.ProviderUsage(5, 3, 8));
        when(adapter.complete(any(ProviderCallContext.class), any(ChatCompletionCommand.class)))
                .thenReturn(Mono.just(completion));
        when(usageFinalization.finalizeSuccess(eq(11L), eq(7L), eq(12L), any()))
                .thenReturn(Mono.just(org.mockito.Mockito.mock(
                        GatewayUsageFinalizationService.FinalizationResult.class)));
        when(jobs.markCompleted(anyLong(), anyString(), any())).thenReturn(1);

        worker.tick();

        verify(jobs).linkGateway(eq(100L), anyString(), eq("DISPATCHING"), eq(11L), any());
        verify(jobs).markCompleted(eq(100L), anyString(), any());
    }

    private static AdvisorJobMapper.JobRow jobRow() {
        return new AdvisorJobMapper.JobRow(100L, 7L, 8L, "ANOMALY", 3L, snapshotFingerprint(), "[]", 1,
                10L, "PENDING", null, null, null, 1, null, Instant.now(), null, null);
    }

    private static String snapshotFingerprint() {
        return EvidenceFingerprint.fingerprint("ANOMALY", 3L, "USD", 1,
                java.util.List.of(new EvidenceFingerprint.Fact("anomaly:3:observed", "30.00")),
                java.util.List.of(), "", "", "");
    }

    private static AdvisorJobMapper.ProfileRow profileRow() {
        return new AdvisorJobMapper.ProfileRow(10L, 7L, 1, 21L, 6L, "PROJECT", 6L, "OPTIONAL", "ACTIVE");
    }

    private static AdvisorJobMapper.InternalCredentialRow credentialRow() {
        return new AdvisorJobMapper.InternalCredentialRow(5L, 7L, 4L, 6L, "PROJECT", 6L, "OPTIONAL");
    }

    private static AdvisorJobMapper.JobRow stuckJob(long jobId, long requestId, String state) {
        return new AdvisorJobMapper.JobRow(jobId, 7L, 8L, "ANOMALY", 3L, "fp", "[]", 1, 10L, "CLAIMED",
                "token", Instant.now().minusSeconds(600), requestId, 1, null, Instant.now(), null, null);
    }

    private static AdvisorJobMapper.SnapshotRow snapshotRow() {
        var fingerprint = snapshotFingerprint();
        return new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 1, "ANOMALY", 3L, "USD",
                "[{\"factId\":\"anomaly:3:observed\",\"label\":\"observed\","
                        + "\"amount\":\"30.00\",\"currency\":\"USD\"}]",
                "[]",
                "{\"forecastSummary\":\"\",\"budgetRiskSummary\":\"\","
                        + "\"savingsSummary\":\"\"}",
                fingerprint, Instant.now(), Instant.now());
    }

    private static GatewayRequestService.DispatchResult dispatch() {
        return new GatewayRequestService.DispatchResult(11L, "public-1", 12L, "decision-1", 13L, 14L,
                15L, 16L, "USD", "https://provider.example.test", "CUSTOM_OPENAI_COMPATIBLE",
                "model-x", 9L, 1024, 512, 17L, 18L, "/chat/completions", "OPENAI_CHAT_COMPLETIONS",
                "DIRECT_PUBLIC_ONLY");
    }

    private static String narrative() {
        return "{\"summary\":\"Spend rose.\",\"driversExplanation\":\"Model X.\","
                + "\"recommendedActions\":[],\"warnings\":[],\"factReferences\":[]}";
    }
}
