package eu.kanade.tachiyomi.extension.util;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public final class PrivateExtensionLeaseLockProcess {
    public static void main(String[] args) throws Exception {
        int exitCode;
        try (FileChannel channel = FileChannel.open(
                Paths.get(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (FileLock lock = channel.tryLock()) {
                exitCode = lock == null ? 3 : 0;
            }
        }
        System.exit(exitCode);
    }
}
