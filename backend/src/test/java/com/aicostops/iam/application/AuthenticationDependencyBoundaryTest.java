package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.iam.infrastructure.LoginIdentityRecord;
import com.aicostops.iam.infrastructure.RedisPasswordResetRepository;
import com.aicostops.iam.infrastructure.RedisRateLimiter;
import com.aicostops.iam.infrastructure.RedisRefreshSessionRepository;
import com.aicostops.iam.infrastructure.RateLimitDecision;
import com.aicostops.iam.infrastructure.RefreshCredential;
import com.aicostops.iam.infrastructure.RefreshRotationOutcome;
import com.aicostops.iam.infrastructure.RefreshRotationResult;
import com.aicostops.iam.infrastructure.RefreshSessionData;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthenticationDependencyBoundaryTest {
    @Test
    void loginMapsOnlyRefreshRedisFailureAndDoesNotMislabelAuditDatabaseFailure() {
        var limiter = mock(RedisRateLimiter.class); var iam = mock(IamMapper.class);
        var passwords = mock(PasswordEncoder.class); var sessions = mock(RedisRefreshSessionRepository.class);
        var audit = mock(AuditService.class);
        when(limiter.checkLogin("ip", "user@example.com")).thenReturn(RateLimitDecision.allow());
        when(iam.findLoginIdentity("user@example.com")).thenReturn(new LoginIdentityRecord(
                1, "user@example.com", "User", "ACTIVE", 0, "hash", 2, 3));
        when(passwords.matches("password", "hash")).thenReturn(true);
        when(sessions.create(1, 2, 0, "device")).thenReturn(new RefreshCredential("session.secret"));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("audit db failed"))
                .when(audit).append(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
        var service = new LoginService(limiter, iam, passwords, sessions,
                new JwtTokenService("boundary-test-only-secret-with-more-than-32-bytes", Duration.ofMinutes(15), Clock.systemUTC()), audit);

        assertThatThrownBy(() -> service.login(new LoginCommand("user@example.com", "password", "ip", "device")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(sessions).revoke("session.secret");
    }

    @Test
    void refreshRedisFailureUsesStableUnavailableProblem() {
        var sessions = mock(RedisRefreshSessionRepository.class);
        when(sessions.load("session.secret")).thenThrow(new DataAccessResourceFailureException("redis down"));
        var service = new RefreshService(sessions, mock(IamMapper.class), mock(JwtTokenService.class), mock(AuditService.class));
        var error = org.assertj.core.api.Assertions.catchThrowableOfType(DomainException.class,
                () -> service.refresh("session.secret"));
        assertThat(error.code()).isEqualTo(ProblemCode.REDIS_UNAVAILABLE_FOR_AUTH);
    }

    @Test
    void refreshMapsRedisFailureWhileRevokingAStaleRotatedSession() {
        var sessions = mock(RedisRefreshSessionRepository.class);
        when(sessions.load("session.secret")).thenReturn(new RefreshSessionData(1, 2, 0));
        when(sessions.rotate("session.secret"))
                .thenReturn(new RefreshRotationResult(RefreshRotationOutcome.ROTATED, "session.next"));
        when(sessions.load("session.next")).thenReturn(new RefreshSessionData(1, 2, 0));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("redis down"))
                .when(sessions).revoke("session.next");
        var service = new RefreshService(sessions, mock(IamMapper.class), mock(JwtTokenService.class),
                mock(AuditService.class));

        var error = org.assertj.core.api.Assertions.catchThrowableOfType(DomainException.class,
                () -> service.refresh("session.secret"));

        assertThat(error.code()).isEqualTo(ProblemCode.REDIS_UNAVAILABLE_FOR_AUTH);
    }

    @Test
    void resetChallengeConsumeRedisFailureUsesStableUnavailableProblem() {
        var resets = mock(RedisPasswordResetRepository.class);
        when(resets.consume("token.secret")).thenThrow(new DataAccessResourceFailureException("redis down"));
        var service = new PasswordResetService(mock(IamMapper.class), resets,
                mock(RedisRefreshSessionRepository.class), mock(PasswordResetDelivery.class), mock(PasswordEncoder.class),
                mock(SecurityVersionService.class), mock(AuditService.class), Clock.systemUTC(),
                mock(org.springframework.data.redis.core.StringRedisTemplate.class), 5);
        var error = org.assertj.core.api.Assertions.catchThrowableOfType(DomainException.class,
                () -> service.reset("token.secret", "new-password"));
        assertThat(error.code()).isEqualTo(ProblemCode.REDIS_UNAVAILABLE_FOR_AUTH);
    }

    @Test
    @SuppressWarnings("unchecked")
    void forgotDoesNotMislabelMysqlFailureAsRedisUnavailable() {
        var iam = mock(IamMapper.class);
        when(iam.findPasswordResetIdentity("user@example.com"))
                .thenThrow(new DataIntegrityViolationException("mysql failed"));
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var values = (org.springframework.data.redis.core.ValueOperations<String, String>)
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        var service = new PasswordResetService(iam, mock(RedisPasswordResetRepository.class),
                mock(RedisRefreshSessionRepository.class), mock(PasswordResetDelivery.class), mock(PasswordEncoder.class),
                mock(SecurityVersionService.class), mock(AuditService.class), Clock.systemUTC(), redis, 5);

        assertThatThrownBy(() -> service.forgot("user@example.com", "ip"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
