package com.aicostops.iam.infrastructure;

public record RefreshRotationResult(RefreshRotationOutcome outcome, String nextCredential) {
}
