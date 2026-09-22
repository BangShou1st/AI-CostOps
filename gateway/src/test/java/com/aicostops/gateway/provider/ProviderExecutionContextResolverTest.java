package com.aicostops.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.request.GatewayRequestService.DispatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderExecutionContextResolverTest {

    @Mock
    private GatewayReadMapper readMapper;
    @Mock
    private ProviderCredentialDecryptor credentialDecryptor;

    private final GatewayPrincipal principal =
            new GatewayPrincipal(1L, 7L, 3L, "SERVICE", null, 4L, "PROJECT", 6L, "OPTIONAL");

    private ProviderExecutionContextResolver resolver() {
        return new ProviderExecutionContextResolver(readMapper, credentialDecryptor);
    }

    @Test
    void noneProfileNeverLooksUpOrDecryptsCredential() {
        when(readMapper.findConnectionProfileById(7L, 101L)).thenReturn(
                new GatewayReadMapper.ConnectionProfileRow(101L, 7L, 10L,
                        "NONE", null, "DIRECT_PUBLIC_ONLY"));

        var context = resolver().resolve(principal, dispatch(101L, 10L,
                "https://old.example", "DIRECT_PUBLIC_ONLY"));

        assertThat(context.credentialType()).isEqualTo("NONE");
        assertThat(context.providerSecret()).isNull();
        verify(credentialDecryptor, never()).decrypt(anyLong(), anyLong());
    }

    @Test
    void frozenProfileRotationUsesExactProfileAndFrozenRouteLineage() {
        when(readMapper.findConnectionProfileById(7L, 101L)).thenReturn(
                new GatewayReadMapper.ConnectionProfileRow(101L, 7L, 10L,
                        "BEARER", null, "DIRECT_PUBLIC_ONLY"));
        lenient().when(readMapper.findActiveConnectionProfile(7L, 10L)).thenReturn(
                new GatewayReadMapper.ConnectionProfileRow(102L, 7L, 10L,
                        "API_KEY_HEADER", "X-New-Key", "DIRECT_ONLY"));
        when(credentialDecryptor.decrypt(7L, 10L)).thenReturn(
                new ProviderCredentialDecryptor.DecryptedCredential(
                        "BEARER_TOKEN", "old-secret".getBytes()));

        var context = resolver().resolve(principal, dispatch(101L, 10L,
                "https://old.example", "DIRECT_PUBLIC_ONLY"));

        assertThat(context.providerConnectionProfileId()).isEqualTo(101L);
        assertThat(context.baseUrl()).isEqualTo("https://old.example");
        assertThat(context.credentialType()).isEqualTo("BEARER_TOKEN");
        assertThat(context.authHeaderName()).isNull();
        assertThat(context.networkPolicy()).isEqualTo("DIRECT_PUBLIC_ONLY");
        assertThat(new String(context.providerSecret())).isEqualTo("old-secret");
        verify(readMapper).findConnectionProfileById(7L, 101L);
        verify(readMapper, never()).findActiveConnectionProfile(7L, 10L);
    }

    @Test
    void foreignOrMismatchedFrozenProfileFailsClosedBeforeCredentialDecrypt() {
        when(readMapper.findConnectionProfileById(7L, 101L)).thenReturn(
                new GatewayReadMapper.ConnectionProfileRow(101L, 99L, 20L,
                        "BEARER", null, "DIRECT_PUBLIC_ONLY"));

        assertThatThrownBy(() -> resolver().resolve(principal,
                dispatch(101L, 10L, "https://old.example", "DIRECT_PUBLIC_ONLY")))
                .isInstanceOf(ProviderContextResolutionException.class)
                .extracting(error -> ((ProviderExecutionException) error).safetyOutcome())
                .isEqualTo(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION);

        verify(credentialDecryptor, never()).decrypt(anyLong(), anyLong());
        verify(readMapper, never()).findActiveConnectionProfile(anyLong(), anyLong());
    }

    private static DispatchResult dispatch(long profileId, long accountId,
            String baseUrl, String networkPolicy) {
        return new DispatchResult(11L, "public-1", 12L, "decision-1", 13L, accountId,
                15L, 16L, "USD", baseUrl, "CUSTOM_OPENAI_COMPATIBLE", "model-x", 9L,
                1024, 512, 17L, profileId, "/chat/completions", "OPENAI_CHAT_COMPLETIONS",
                networkPolicy);
    }
}
