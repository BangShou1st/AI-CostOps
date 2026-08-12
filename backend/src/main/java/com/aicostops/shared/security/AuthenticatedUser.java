package com.aicostops.shared.security;

public record AuthenticatedUser(long userId, long securityVersion) {
}
