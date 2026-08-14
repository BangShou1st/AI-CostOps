package com.aicostops.iam.application;

public interface InvitationDelivery {

    default void requireAvailable() {
    }

    void deliver(String normalizedEmail, String invitationToken);
}
