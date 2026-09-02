package com.aicostops.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.core.publisher.Mono;

/** AIC-096 key authentication and principal/project/scope authorization. */
@ExtendWith(MockitoExtension.class)
class GatewayAuthenticationManagerTest {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final long ORG_ID = 11L;
    private static final long PROJECT_ID = 7L;
    private static final long SERVICE_ID = 33L;
    private static final long CREDENTIAL_ID = 44L;

    @Mock
    private GatewayReadMapper readMapper;

    @Mock
    private BlockingIoScheduler blockingIo;

    private GatewayAuthenticationManager manager;

    @BeforeEach
    void setUp() {
        var properties = new GatewayProperties();
        properties.setCredentialHmacKeyV1(HMAC_KEY);
        manager = new GatewayAuthenticationManager(
                readMapper, properties, blockingIo, Clock.fixed(
                        Instant.parse("2026-09-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        when(blockingIo.call(any()))
                .thenAnswer(invocation -> Mono.fromCallable(invocation.getArgument(0)));
    }

    @Test
    void validKeyAuthenticatesWithResolvedPrincipal() {
        var row = validCredentialRow();
        when(readMapper.findCredentialByPrefix("0123456789ab")).thenReturn(row);
        when(readMapper.findServiceIdentityStatus(SERVICE_ID)).thenReturn("ACTIVE");
        when(readMapper.findProjectStatus(PROJECT_ID, ORG_ID)).thenReturn("ACTIVE");
        when(readMapper.findProjectStatus(PROJECT_ID, ORG_ID)).thenReturn("ACTIVE");

        var authentication = manager.authenticate(
                new UsernamePasswordAuthenticationToken(key().raw(), key().raw())).block();

        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(GatewayPrincipal.class);
        var principal = (GatewayPrincipal) authentication.getPrincipal();
        assertThat(principal.credentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(principal.organizationId()).isEqualTo(ORG_ID);
        assertThat(principal.principalType()).isEqualTo("SERVICE");
        assertThat(principal.budgetEnforcementMode()).isEqualTo("OPTIONAL");
    }

    @Test
    void wrongSecretIsRejectedAsAuthInvalid() {
        when(readMapper.findCredentialByPrefix("0123456789ab")).thenReturn(validCredentialRow());

        assertThatThrownBy(() -> manager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        "aic_0123456789ab_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                        "aic_0123456789ab_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")).block())
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_AUTH_INVALID));
    }

    @Test
    void unknownPrefixIsRejectedAsAuthInvalid() {
        when(readMapper.findCredentialByPrefix("0123456789ab")).thenReturn(null);

        assertThatThrownBy(() -> manager.authenticate(
                new UsernamePasswordAuthenticationToken(key().raw(), key().raw())).block())
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_AUTH_INVALID));
    }

    @Test
    void expiredCredentialIsRejected() {
        var row = new GatewayReadMapper.CredentialRow(
                CREDENTIAL_ID, ORG_ID, "0123456789ab", digest(key().secret()), (short) 1,
                "SERVICE", null, SERVICE_ID, PROJECT_ID, "PROJECT", PROJECT_ID,
                "OPTIONAL", "ACTIVE", Instant.parse("2020-01-01T00:00:00Z"));
        when(readMapper.findCredentialByPrefix("0123456789ab")).thenReturn(row);

        assertThatThrownBy(() -> manager.authenticate(
                new UsernamePasswordAuthenticationToken(key().raw(), key().raw())).block())
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_AUTH_INVALID));
    }

    @Test
    void inactiveServicePrincipalIsForbidden() {
        when(readMapper.findCredentialByPrefix("0123456789ab")).thenReturn(validCredentialRow());
        when(readMapper.findServiceIdentityStatus(SERVICE_ID)).thenReturn("ARCHIVED");

        assertThatThrownBy(() -> manager.authenticate(
                new UsernamePasswordAuthenticationToken(key().raw(), key().raw())).block())
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_FORBIDDEN));
    }

    @Test
    void inactiveProjectIsForbidden() {
        when(readMapper.findCredentialByPrefix("0123456789ab")).thenReturn(validCredentialRow());
        when(readMapper.findServiceIdentityStatus(SERVICE_ID)).thenReturn("ACTIVE");
        when(readMapper.findProjectStatus(PROJECT_ID, ORG_ID)).thenReturn("DISABLED");

        assertThatThrownBy(() -> manager.authenticate(
                new UsernamePasswordAuthenticationToken(key().raw(), key().raw())).block())
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_FORBIDDEN));
    }

    @Test
    void malformedKeyShapeIsRejected() {
        assertThatThrownBy(() -> manager.authenticate(
                new UsernamePasswordAuthenticationToken("not-a-key", "not-a-key")).block())
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_AUTH_INVALID));
    }

    private GatewayReadMapper.CredentialRow validCredentialRow() {
        return new GatewayReadMapper.CredentialRow(
                CREDENTIAL_ID, ORG_ID, "0123456789ab", digest(key().secret()), (short) 1,
                "SERVICE", null, SERVICE_ID, PROJECT_ID, "PROJECT", PROJECT_ID,
                "OPTIONAL", "ACTIVE", null);
    }

    private static Key key() {
        var raw = "aic_0123456789ab_" + "A".repeat(43);
        return new Key(raw, raw.substring(17));
    }

    private static byte[] digest(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(HMAC_KEY), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Key(String raw, String secret) {
    }
}