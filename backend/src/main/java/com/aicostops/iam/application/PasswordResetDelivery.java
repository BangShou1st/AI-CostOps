package com.aicostops.iam.application;

public interface PasswordResetDelivery {
    void deliver(String normalizedEmail, String resetToken);
}
