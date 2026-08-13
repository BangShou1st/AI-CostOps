package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.PasswordResetDelivery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

public class DevPasswordResetMailbox implements PasswordResetDelivery {
    private final Path mailbox;
    private final String resetPageUrl;

    public DevPasswordResetMailbox(Path mailbox, String resetPageUrl) {
        this.mailbox = mailbox;
        this.resetPageUrl = resetPageUrl;
    }

    @Override
    public void deliver(String normalizedEmail, String resetToken) {
        try {
            Files.createDirectories(mailbox);
            var filename = Instant.now().toEpochMilli() + "-" +
                    com.aicostops.iam.domain.TokenDigest.sha256(normalizedEmail).substring(0, 16) + "-" +
                    UUID.randomUUID() + ".txt";
            var content = "email=" + normalizedEmail + System.lineSeparator()
                    + "resetLink=" + resetPageUrl + "?token=" + resetToken + System.lineSeparator();
            Files.writeString(mailbox.resolve(filename), content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write local development password reset mailbox", exception);
        }
    }
}
