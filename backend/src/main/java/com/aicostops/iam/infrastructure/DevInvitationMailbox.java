package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.InvitationDelivery;
import com.aicostops.iam.domain.TokenDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class DevInvitationMailbox implements InvitationDelivery {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path mailbox;
    private final String invitePageUrl;

    public DevInvitationMailbox(Path mailbox, String invitePageUrl) {
        this.mailbox = mailbox;
        this.invitePageUrl = invitePageUrl;
    }

    @Override
    public void deliver(String normalizedEmail, String invitationToken) {
        try {
            prepareMailbox();
            var filename = Instant.now().toEpochMilli() + "-"
                    + TokenDigest.sha256(normalizedEmail).substring(0, 16) + "-"
                    + UUID.randomUUID() + ".txt";
            var content = "email=" + normalizedEmail + System.lineSeparator()
                    + "acceptLink=" + invitePageUrl + "?token=" + invitationToken + System.lineSeparator();
            var message = mailbox.resolve(filename);
            createOwnerOnly(message, false);
            Files.writeString(message, content, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            enforceOwnerOnly(message, false);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write local development invitation mailbox", exception);
        }
    }

    private void prepareMailbox() throws IOException {
        if (Files.notExists(mailbox, LinkOption.NOFOLLOW_LINKS)) {
            var parent = mailbox.toAbsolutePath().getParent();
            if (parent == null) {
                throw new IOException("Invitation mailbox must have a parent directory");
            }
            Files.createDirectories(parent);
            createOwnerOnly(mailbox, true);
        }
        if (!Files.isDirectory(mailbox, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invitation mailbox path is not a directory");
        }
        enforceOwnerOnly(mailbox, true);
    }

    private void createOwnerOnly(Path path, boolean directory) throws IOException {
        var parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Secure path must have a parent directory");
        }
        if (supportsPosix(parent)) {
            var attribute = PosixFilePermissions.asFileAttribute(
                    directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS);
            if (directory) {
                Files.createDirectory(path, attribute);
            } else {
                Files.createFile(path, attribute);
            }
            return;
        }

        var parentAcl = Files.getFileAttributeView(parent, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (parentAcl == null) {
            throw new IOException("File system cannot enforce owner-only mailbox permissions");
        }
        var attribute = ownerOnlyAclAttribute(Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS), directory);
        if (directory) {
            Files.createDirectory(path, attribute);
        } else {
            Files.createFile(path, attribute);
        }
    }

    private void enforceOwnerOnly(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Invitation mailbox paths cannot be symbolic links");
        }
        if (supportsPosix(path)) {
            var expected = directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS;
            var view = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            view.setPermissions(expected);
            if (!view.readAttributes().permissions().equals(expected)) {
                throw new IOException("Unable to enforce owner-only POSIX permissions");
            }
            return;
        }

        var view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("File system cannot enforce owner-only mailbox permissions");
        }
        var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        view.setAcl(List.of(ownerOnlyAclEntry(owner, directory)));
        if (view.getAcl().isEmpty() || view.getAcl().stream()
                .anyMatch(entry -> !entry.principal().equals(owner) || entry.type() != AclEntryType.ALLOW)) {
            throw new IOException("Unable to enforce owner-only ACL permissions");
        }
    }

    private boolean supportsPosix(Path path) throws IOException {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) != null;
    }

    private FileAttribute<List<AclEntry>> ownerOnlyAclAttribute(UserPrincipal owner, boolean directory) {
        var acl = List.of(ownerOnlyAclEntry(owner, directory));
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return acl;
            }
        };
    }

    private AclEntry ownerOnlyAclEntry(UserPrincipal owner, boolean directory) {
        var builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        return builder.build();
    }
}
