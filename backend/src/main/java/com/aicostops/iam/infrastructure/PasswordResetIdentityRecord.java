package com.aicostops.iam.infrastructure;

public record PasswordResetIdentityRecord(long userId, String emailNormalized, String status) {
}
