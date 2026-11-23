package ru.fridorin.translator.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AtomicFiles {
    private AtomicFiles() { }

    public static String read(Path source, int maxBytes) throws IOException {
        try (InputStream stream = Files.newInputStream(source)) {
            return readUtf8(stream, maxBytes);
        }
    }

    public static String readUtf8(InputStream stream, int maxBytes) throws IOException {
        byte[] bytes = stream.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new IOException("File exceeded the byte limit");
        }
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    public static void write(Path destination, String contents) throws IOException {
        Path target = destination.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void write(Path destination, String contents, int maxBytes) throws IOException {
        if (contents.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IOException("File exceeded the byte limit");
        }
        write(destination, contents);
    }
}
