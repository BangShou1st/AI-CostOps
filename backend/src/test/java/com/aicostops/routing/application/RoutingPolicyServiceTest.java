package com.aicostops.routing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.organization.application.OrganizationAuditPort;
import com.aicostops.routing.infrastructure.RoutingPolicyMapper;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageRequest;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** P2-14: the policy list loads every page candidate with one batch query. */
@ExtendWith(MockitoExtension.class)
class RoutingPolicyServiceTest {

    private static final long ORG_ID = 11L;
    private static final long USER_ID = 22L;
    private static final long ORG_MEMBER_ID = 33L;
    private static final long MODEL_ID = 44L;

    @Mock
    private AuthorizationContextService authorizationContexts;
    @Mock
    private RoutingPolicyMapper mapper;
    @Mock
    private OrganizationAuditPort audit;

    @Test
    void listLoadsPageCandidatesWithOneBatchQuery() {
        stubReader();
        when(mapper.findPage(ORG_ID, 0L, 50)).thenReturn(List.of(
                new RoutingPolicyMapper.PolicyRow(1L, ORG_ID, null, MODEL_ID, 1, "ACTIVE"),
                new RoutingPolicyMapper.PolicyRow(2L, ORG_ID, null, MODEL_ID, 2, "DRAFT")));
        when(mapper.count(ORG_ID)).thenReturn(2L);
        when(mapper.findCandidatesByPolicyIds(ORG_ID, List.of(1L, 2L))).thenReturn(List.of(
                new RoutingPolicyMapper.PolicyCandidateRow(1L, 10L, 200L, 300L, 0, "ACTIVE", null),
                new RoutingPolicyMapper.PolicyCandidateRow(2L, 11L, 201L, 301L, 1, "DISABLED", "eu")));

        var page = service().list(user(), new PageRequest(0, 50));

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).id()).isEqualTo(ApiId.of(1L));
        assertThat(page.items().get(0).candidates()).hasSize(1);
        assertThat(page.items().get(0).candidates().get(0).providerAccountId()).isEqualTo(ApiId.of(200L));
        assertThat(page.items().get(1).id()).isEqualTo(ApiId.of(2L));
        assertThat(page.items().get(1).candidates()).hasSize(1);
        assertThat(page.items().get(1).candidates().get(0).privacyRegionCode()).isEqualTo("eu");
        assertThat(page.totalElements()).isEqualTo(2L);

        verify(mapper, times(1)).findCandidatesByPolicyIds(ORG_ID, List.of(1L, 2L));
        verify(mapper, never()).findCandidates(anyLong(), anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void emptyPageDoesNotRunTheBatchCandidateQuery() {
        stubReader();
        when(mapper.findPage(ORG_ID, 0L, 50)).thenReturn(List.of());
        when(mapper.count(ORG_ID)).thenReturn(0L);

        var page = service().list(user(), new PageRequest(0, 50));

        assertThat(page.items()).isEmpty();
        verify(mapper, never()).findCandidatesByPolicyIds(anyLong(), any());
        verify(mapper, never()).findCandidates(anyLong(), anyLong());
    }

    private RoutingPolicyService service() {
        return new RoutingPolicyService(authorizationContexts, mapper, Clock.systemUTC(), audit);
    }

    private void stubReader() {
        when(authorizationContexts.current(user())).thenReturn(new AuthorizationContext(
                USER_ID, ORG_ID, ORG_MEMBER_ID, 7,
                Set.of(new ScopedPermissionGrant("PROVIDER_ACCOUNT_READ", ScopeType.ORG, ORG_ID)),
                Set.of("CP_READER")));
    }

    private static AuthenticatedUser user() {
        return new AuthenticatedUser(USER_ID, 7);
    }
}
