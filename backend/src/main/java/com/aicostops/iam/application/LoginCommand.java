package com.aicostops.iam.application;

public record LoginCommand(String email, String password, String remoteIp, String deviceLabel) {
}
