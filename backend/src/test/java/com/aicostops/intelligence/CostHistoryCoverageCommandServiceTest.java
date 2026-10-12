package com.aicostops.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.intelligence.application.CostHistoryCoverageCommandService;
import com.aicostops.intelligence.application.CostHistoryCoverageCommandService.ReviewRequest;
import com.aicostops.intelligence.domain.CostHistoryCoverage.*;
import com.aicostops.intelligence.infrastructure.CostHistoryCoverageMapper;
import com.aicostops.intelligence.infrastructure.CostHistoryCoverageMapper.CoverageRow;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CostHistoryCoverageCommandServiceTest {
    private final AuthorizationContextService contexts = mock(AuthorizationContextService.class);
    private final CostHistoryCoverageMapper store = mock(CostHistoryCoverageMapper.class);
    private final AuditService audit = mock(AuditService.class);
    private final AuthenticatedUser user = new AuthenticatedUser(5, 1);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 11);
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private CostHistoryCoverageCommandService service;

    @BeforeEach void setup() {
        when(contexts.fresh(user)).thenReturn(context(true));
        when(store.ownedScope(7, "PROJECT", 9)).thenReturn(1);
        when(store.ledgerStamp(7, "CNY", "PROJECT", 9, DAY, DAY.plusDays(1)))
                .thenReturn(new LedgerStamp(0, 0));
        service = new CostHistoryCoverageCommandService(contexts, store, audit, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(com.aicostops.intelligence.application.CostIntelligenceAnalysisService.class));
    }

    private AuthorizationContext context(boolean permitted) {
        return new AuthorizationContext(5, 7, 6, 1,
                permitted ? Set.of(new ScopedPermissionGrant("COST_COVERAGE_MANAGE", ScopeType.ORG, 7)) : Set.of(), Set.of());
    }
    private ReviewRequest request(long revision, Status status) {
        return new ReviewRequest("CNY", Scope.PROJECT, 9, DAY, status, "Reviewed all posting sources", revision);
    }

    @Test void freshPermissionIsRequiredBeforeAnyReadOrWrite() {
        when(contexts.fresh(user)).thenReturn(context(false));
        assertEquals(ProblemCode.FORBIDDEN,
                assertThrows(DomainException.class, () -> service.review(user, request(0, Status.REVIEWED_COMPLETE))).code());
        verifyNoInteractions(store, audit);
    }

    @Test void foreignScopeAndStaleRevisionCannotMutateCoverage() {
        when(store.ownedScope(7, "PROJECT", 9)).thenReturn(0);
        assertEquals(ProblemCode.RESOURCE_NOT_FOUND,
                assertThrows(DomainException.class, () -> service.review(user, request(0, Status.REVIEWED_COMPLETE))).code());
        verify(store, never()).ensureRevision(anyLong(), anyString());
        when(store.ownedScope(7, "PROJECT", 9)).thenReturn(1);
        when(store.lockRevision(7, "CNY")).thenReturn(2L);
        assertEquals(ProblemCode.STATE_CONFLICT,
                assertThrows(DomainException.class, () -> service.review(user, request(1, Status.REVIEWED_COMPLETE))).code());
        verify(store, never()).advanceRevision(anyLong(), anyString());
        verifyNoInteractions(audit);
    }

    @Test void futureOrUnfinishedUtcDayCannotBeReviewed() {
        assertEquals(ProblemCode.VALIDATION_FAILED, assertThrows(DomainException.class, () -> service.review(user,
                new ReviewRequest("CNY", Scope.PROJECT, 9, DAY.plusDays(1), Status.REVIEWED_COMPLETE, "review", 0L))).code());
        verifyNoInteractions(store, audit);
    }

    @Test void repeatedEquivalentReviewDoesNotCreateAnotherRevision() {
        var row = new CoverageRow(1, "PROJECT", 9, DAY, "REVIEWED_COMPLETE", "MANUAL_LEDGER_REVIEW",
                "Reviewed all posting sources", 0, 0, 5, NOW);
        when(store.find(7, "CNY", "PROJECT", 9, DAY)).thenReturn(row);
        assertEquals(0, service.review(user, request(0, Status.REVIEWED_COMPLETE)).revision());
        verify(store, never()).advanceRevision(anyLong(), anyString());
        verifyNoInteractions(audit);
    }

    @Test void declarationCapturesLedgerStampAndAuditsTheActorAndRevision() {
        var row = new CoverageRow(1, "PROJECT", 9, DAY, "REVIEWED_COMPLETE", "MANUAL_LEDGER_REVIEW",
                "Reviewed all posting sources", 0, 0, 5, NOW);
        when(store.find(7, "CNY", "PROJECT", 9, DAY)).thenReturn(null, row);
        when(store.advanceRevision(7, "CNY")).thenReturn(1);
        var result = service.review(user, request(0, Status.REVIEWED_COMPLETE));
        assertEquals(1, result.revision());
        verify(store).save(7, "CNY", "PROJECT", 9, DAY, "REVIEWED_COMPLETE",
                "Reviewed all posting sources", new LedgerStamp(0, 0), 5, NOW);
        verify(audit).append(eq("COST_HISTORY_COVERAGE_REVIEWED"), eq(7L), eq(5L),
                eq("COST_HISTORY_COVERAGE"), eq(1L), argThat(m -> m.get("revision").equals(1L)));
    }

    @Test void absentDeclarationCannotBeRevoked() {
        assertEquals(ProblemCode.STATE_CONFLICT,
                assertThrows(DomainException.class, () -> service.review(user, request(0, Status.REVOKED))).code());
        verify(store, never()).advanceRevision(anyLong(), anyString());
        verifyNoInteractions(audit);
    }

    @Test void callerMustExplicitlySupplyTheExpectedRevision() {
        assertEquals(ProblemCode.VALIDATION_FAILED, assertThrows(DomainException.class,
                () -> service.review(user, new ReviewRequest("CNY", Scope.PROJECT, 9, DAY,
                        Status.REVIEWED_COMPLETE, "review", null))).code());
        verifyNoInteractions(store, audit);
    }

    @Test void aDifferentReviewerMustGetTheirOwnAttributionAndAuditRecord() {
        var previous = new CoverageRow(1, "PROJECT", 9, DAY, "REVIEWED_COMPLETE", "MANUAL_LEDGER_REVIEW",
                "Reviewed all posting sources", 0, 0, 99, NOW);
        var current = new CoverageRow(1, "PROJECT", 9, DAY, "REVIEWED_COMPLETE", "MANUAL_LEDGER_REVIEW",
                "Reviewed all posting sources", 0, 0, 5, NOW);
        when(store.find(7, "CNY", "PROJECT", 9, DAY)).thenReturn(previous, current);
        when(store.advanceRevision(7, "CNY")).thenReturn(1);
        var result = service.review(user, request(0, Status.REVIEWED_COMPLETE));
        assertEquals(1, result.revision());
        assertEquals(5, result.review().reviewedByUserId());
        verify(audit).append(eq("COST_HISTORY_COVERAGE_REVIEWED"), eq(7L), eq(5L),
                eq("COST_HISTORY_COVERAGE"), eq(1L), anyMap());
    }
}
