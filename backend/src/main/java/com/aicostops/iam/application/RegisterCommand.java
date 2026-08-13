package com.aicostops.iam.application;

public record RegisterCommand(String email, String displayName, String password) {
}
