package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.InvitationDelivery;
import com.aicostops.iam.domain.TokenDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

public class DevInvitationMailbox implements InvitationDelivery {

    private final Path mailbox;
    private final String invitePageUrl;

    public DevInvitationMailbox(Path mailbox, String invitePageUrl) {
        this.mailbox = mailbox;
        this.invitePageUrl = invitePageUrl;
    }

    @Override
    public void deliver(String normalizedEmail, String invitationToken) {
        try {
            Files.createDirectories(mailbox);
            var filename = Instant.now().toEpochMilli() + "-"
                    + TokenDigest.sha256(normalizedEmail).substring(0, 16) + "-"
                    + UUID.randomUUID() + ".txt";
            var content = "email=" + normalizedEmail + System.lineSeparator()
                    + "acceptLink=" + invitePageUrl + "?token=" + invitationToken + System.lineSeparator();
            Files.writeString(mailbox.resolve(filename), content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write local development invitation mailbox", exception);
        }
    }
}
