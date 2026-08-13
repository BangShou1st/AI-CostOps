package com.aicostops.iam.infrastructure;

public record IssuedAccessToken(String token, long expiresInSeconds) {
}
