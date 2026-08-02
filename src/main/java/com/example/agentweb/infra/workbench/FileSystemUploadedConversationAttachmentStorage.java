package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.attachment.UploadedAttachmentStorageException;
import com.example.agentweb.app.workbench.attachment.port.StoredUploadedAttachment;
import com.example.agentweb.app.workbench.attachment.port.UploadedAttachmentStorageRequest;
import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Set;

/**
 * Git 忽略根下的上传附件临时存储；所有物理身份均由服务端生成。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class FileSystemUploadedConversationAttachmentStorage
        implements UploadedConversationAttachmentStorage {

    private static final int BUFFER_SIZE = 8192;
    private static final int STORAGE_KEY_BYTES = 32;
    private static final int MAXIMUM_KEY_ATTEMPTS = 8;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> STORED_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> RUNTIME_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("r--------");

    private final Path configuredRoot;
    private final long maximumBytes;
    private final SecureRandom random;

    public FileSystemUploadedConversationAttachmentStorage(
            Path configuredRoot, long maximumBytes) {
        this(configuredRoot, maximumBytes, new SecureRandom());
    }

    FileSystemUploadedConversationAttachmentStorage(
            Path configuredRoot, long maximumBytes, SecureRandom random) {
        if (configuredRoot == null || maximumBytes < 1L
                || maximumBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "uploaded attachment storage configuration is invalid");
        }
        this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
        this.maximumBytes = maximumBytes;
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public StoredUploadedAttachment store(
            UploadedAttachmentStorageRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.getDeclaredSize() < 1L
                || request.getDeclaredSize() > maximumBytes) {
            throw unavailable("uploaded attachment size is outside the storage limit");
        }
        Path root = requireRoot();
        Path temporary = null;
        try {
            byte[] content = readExact(
                    request.getInputStream(), request.getDeclaredSize());
            String storageKey = nextStorageKey(root);
            Path destination = root.resolve(storageKey);
            temporary = Files.createTempFile(root, ".upload-", ".tmp");
            secureFile(temporary, STORED_FILE_PERMISSIONS);
            Files.write(temporary, content,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            moveAtomically(temporary, destination);
            secureFile(destination, STORED_FILE_PERMISSIONS);
            temporary = null;
            return new StoredUploadedAttachment(
                    storageKey, CanonicalHashing.sha256(content), content.length,
                    signature(content));
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(temporary);
            if (failure instanceof UploadedAttachmentStorageException) {
                throw (UploadedAttachmentStorageException) failure;
            }
            throw unavailable("uploaded attachment could not be stored");
        }
    }

    @Override
    public void copyVerified(
            String storageKey, Path destination,
            String expectedSha256, long expectedSize) {
        String key = requireStorageKey(storageKey);
        String expectedHash = DomainText.requireSha256(
                expectedSha256, "uploaded attachment expected hash");
        if (destination == null || expectedSize < 1L
                || expectedSize > maximumBytes) {
            throw unavailable("uploaded attachment materialization request is invalid");
        }
        Path root = requireRoot();
        Path source = root.resolve(key);
        Path temporary = null;
        try {
            requireStoredRegularFile(source, expectedSize);
            BasicFileAttributes before = attributes(source);
            byte[] content;
            try (InputStream input = Files.newInputStream(
                    source, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                content = readExact(input, expectedSize);
            }
            BasicFileAttributes after = attributes(source);
            requireStoredRegularFile(source, expectedSize);
            if (!sameIdentity(before, after)
                    || !source.equals(source.toRealPath())
                    || !expectedHash.equals(CanonicalHashing.sha256(content))) {
                throw unavailable("uploaded attachment changed before materialization");
            }
            Path target = requireRuntimeDestination(destination);
            temporary = Files.createTempFile(
                    target.getParent(), ".attachment-", ".tmp");
            Files.write(temporary, content,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            secureFile(temporary, RUNTIME_FILE_PERMISSIONS);
            moveAtomically(temporary, target);
            secureFile(target, RUNTIME_FILE_PERMISSIONS);
            temporary = null;
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(temporary);
            if (failure instanceof UploadedAttachmentStorageException) {
                throw (UploadedAttachmentStorageException) failure;
            }
            throw unavailable("uploaded attachment could not be materialized");
        }
    }

    @Override
    public void delete(String storageKey) {
        String key = requireStorageKey(storageKey);
        Path root = requireRoot();
        Path candidate = root.resolve(key);
        try {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(candidate)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw unavailable("uploaded attachment cleanup target is invalid");
            }
            Files.delete(candidate);
        } catch (IOException failure) {
            throw unavailable("uploaded attachment could not be cleaned");
        }
    }

    private Path requireRoot() {
        try {
            Files.createDirectories(configuredRoot);
            secureDirectory(configuredRoot);
            if (Files.isSymbolicLink(configuredRoot)
                    || !Files.isDirectory(
                    configuredRoot, LinkOption.NOFOLLOW_LINKS)
                    || !configuredRoot.equals(configuredRoot.toRealPath())) {
                throw unavailable("uploaded attachment storage is unavailable");
            }
            return configuredRoot;
        } catch (IOException failure) {
            throw unavailable("uploaded attachment storage is unavailable");
        }
    }

    private byte[] readExact(InputStream input, long declaredSize)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(declaredSize, BUFFER_SIZE));
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) {
                continue;
            }
            total += count;
            if (total > maximumBytes || total > declaredSize) {
                throw unavailable("uploaded attachment exceeded its declared size");
            }
            output.write(buffer, 0, count);
        }
        if (total != declaredSize || total < 1L) {
            throw unavailable("uploaded attachment size changed during upload");
        }
        return output.toByteArray();
    }

    private UploadedAttachmentContentSignature signature(byte[] content) {
        if (startsWith(content, 0x4d, 0x5a)) {
            return UploadedAttachmentContentSignature.PE_EXECUTABLE;
        }
        if (startsWith(content, 0x7f, 0x45, 0x4c, 0x46)) {
            return UploadedAttachmentContentSignature.ELF_EXECUTABLE;
        }
        if (startsWith(content, 0xca, 0xfe, 0xba, 0xbe)
                || startsWith(content, 0xfe, 0xed, 0xfa, 0xce)
                || startsWith(content, 0xfe, 0xed, 0xfa, 0xcf)
                || startsWith(content, 0xce, 0xfa, 0xed, 0xfe)
                || startsWith(content, 0xcf, 0xfa, 0xed, 0xfe)) {
            return UploadedAttachmentContentSignature.MACHO_EXECUTABLE;
        }
        if (startsWith(content, 0x23, 0x21)) {
            return UploadedAttachmentContentSignature.SHEBANG_EXECUTABLE;
        }
        if (startsWith(content, 0x89, 0x50, 0x4e, 0x47,
                0x0d, 0x0a, 0x1a, 0x0a)) {
            return UploadedAttachmentContentSignature.PNG;
        }
        if (startsWith(content, 0xff, 0xd8, 0xff)) {
            return UploadedAttachmentContentSignature.JPEG;
        }
        if (startsWithText(content, "GIF87a")
                || startsWithText(content, "GIF89a")) {
            return UploadedAttachmentContentSignature.GIF;
        }
        if (content.length >= 12 && startsWithText(content, "RIFF")
                && content[8] == 'W' && content[9] == 'E'
                && content[10] == 'B' && content[11] == 'P') {
            return UploadedAttachmentContentSignature.WEBP;
        }
        if (startsWithText(content, "%PDF-")) {
            return UploadedAttachmentContentSignature.PDF;
        }
        if (isSafeUtf8Text(content)) {
            return UploadedAttachmentContentSignature.TEXT;
        }
        return UploadedAttachmentContentSignature.BINARY_UNKNOWN;
    }

    private boolean isSafeUtf8Text(byte[] content) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            for (int index = 0; index < decoded.length(); index++) {
                char value = decoded.charAt(index);
                if (value == 0 || (Character.isISOControl(value)
                        && value != '\n' && value != '\r' && value != '\t')) {
                    return false;
                }
            }
            return true;
        } catch (CharacterCodingException failure) {
            return false;
        }
    }

    private String nextStorageKey(Path root) throws IOException {
        for (int attempt = 0; attempt < MAXIMUM_KEY_ATTEMPTS; attempt++) {
            byte[] bytes = new byte[STORAGE_KEY_BYTES];
            random.nextBytes(bytes);
            String key = hex(bytes);
            if (!Files.exists(root.resolve(key), LinkOption.NOFOLLOW_LINKS)) {
                return key;
            }
        }
        throw unavailable("uploaded attachment storage identity is unavailable");
    }

    private Path requireRuntimeDestination(Path destination) throws IOException {
        Path normalized = destination.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.equals(parent.toRealPath())
                || Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable("uploaded attachment runtime destination is invalid");
        }
        return normalized;
    }

    private void requireStoredRegularFile(Path source, long size)
            throws IOException {
        BasicFileAttributes attributes = attributes(source);
        if (Files.isSymbolicLink(source) || !attributes.isRegularFile()
                || attributes.isOther() || attributes.size() != size) {
            throw unavailable("uploaded attachment storage object is invalid");
        }
    }

    private BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean sameIdentity(
            BasicFileAttributes before, BasicFileAttributes after) {
        return Objects.equals(before.fileKey(), after.fileKey())
                && before.creationTime().equals(after.creationTime())
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && before.size() == after.size();
    }

    private void moveAtomically(Path source, Path destination)
            throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            throw unavailable("atomic uploaded attachment storage is unavailable");
        }
    }

    private void secureDirectory(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL 由服务账户边界承担。
        }
    }

    private void secureFile(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL 由服务账户边界承担。
        }
    }

    private String requireStorageKey(String storageKey) {
        try {
            return DomainText.requireSha256(
                    storageKey, "uploaded attachment storage key");
        } catch (IllegalArgumentException failure) {
            throw unavailable("uploaded attachment storage identity is invalid");
        }
    }

    private boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((content[index] & 0xff) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWithText(byte[] content, String prefix) {
        return startsWith(content, toUnsignedBytes(prefix));
    }

    private int[] toUnsignedBytes(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int[] result = new int[bytes.length];
        for (int index = 0; index < bytes.length; index++) {
            result[index] = bytes[index] & 0xff;
        }
        return result;
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 周期清理兜底。
        }
    }

    private UploadedAttachmentStorageException unavailable(String message) {
        return new UploadedAttachmentStorageException(message);
    }

    @Override
    public String toString() {
        return "FileSystemUploadedConversationAttachmentStorage{configuredRoot, "
                + "maximumBytes}";
    }
}
