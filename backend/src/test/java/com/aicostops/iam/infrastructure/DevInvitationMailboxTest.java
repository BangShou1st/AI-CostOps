package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DevInvitationMailboxTest {

    @TempDir
    java.nio.file.Path mailbox;

    @Test
    void devMailboxWritesAcceptLinkWithoutLogging(CapturedOutput output) throws Exception {
        var secureMailbox = mailbox.resolve("invitations");
        var sink = new DevInvitationMailbox(secureMailbox, "http://localhost:8080/accept-invitation");

        sink.deliver("person@example.com", "invitation-secret-value");

        var messages = Files.list(secureMailbox).toList();
        assertThat(messages).hasSize(1);
        assertThat(Files.readString(messages.getFirst()))
                .contains("email=person@example.com")
                .contains("acceptLink=http://localhost:8080/accept-invitation?token=invitation-secret-value");
        assertThat(output.getAll()).doesNotContain("invitation-secret-value", "person@example.com");
    }

    @Test
    void devMailboxRestrictsRawTokenToOwnerOnly() throws Exception {
        var secureMailbox = mailbox.resolve("invitations");
        var sink = new DevInvitationMailbox(secureMailbox, "http://localhost:8080/accept-invitation");

        sink.deliver("person@example.com", "invitation-secret-value");

        var message = Files.list(secureMailbox).toList().getFirst();
        if (Files.getFileAttributeView(secureMailbox, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) != null) {
            assertThat(Files.getPosixFilePermissions(secureMailbox))
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE);
            assertThat(Files.getPosixFilePermissions(message))
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
        } else {
            assertOwnerOnlyAcl(secureMailbox);
            assertOwnerOnlyAcl(message);
        }
    }

    @Test
    void posixSymlinkCannotChangeTargetPermissions() throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(mailbox, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) != null);
        var target = mailbox.resolve("target.txt");
        Files.writeString(target, "target");
        var originalPermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ);
        Files.setPosixFilePermissions(target, originalPermissions);
        var link = mailbox.resolve("mailbox-link");
        Files.createSymbolicLink(link, target.getFileName());
        var sink = new DevInvitationMailbox(mailbox.resolve("invitations"),
                "http://localhost:8080/accept-invitation");

        assertThatThrownBy(() -> sink.enforceOwnerOnly(link, false))
                .isInstanceOf(IOException.class);
        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(originalPermissions);
    }

    private void assertOwnerOnlyAcl(Path path) throws Exception {
        var view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        assertThat(view).as("ACL view for %s", path).isNotNull();
        var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        assertThat(view.getAcl()).hasSize(1);
        var entry = view.getAcl().getFirst();
        assertThat(entry.principal()).isEqualTo(owner);
        assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
        assertThat(entry.permissions()).containsAll(EnumSet.allOf(AclEntryPermission.class));
    }
}
