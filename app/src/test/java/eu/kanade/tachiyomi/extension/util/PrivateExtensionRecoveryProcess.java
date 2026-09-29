package eu.kanade.tachiyomi.extension.util;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import kotlin.Unit;

public final class PrivateExtensionRecoveryProcess {
    public static void main(String[] args) throws Exception {
        File target = new File(args[0]);
        if (args.length > 2 && args[2].equals("transaction")) {
            runInterruptedReplacement(target, new File(args[1]), args[3]);
            return;
        }
        PrivateExtensionFileStore.Lease lease = new PrivateExtensionFileStore.Lease("reader");
        boolean copying = args.length > 2 && args[2].equals("copying");
        boolean installed = new PrivateExtensionFileStore().install(
            target, copying ? null : lease, false, () -> Unit.INSTANCE, boundary -> Unit.INSTANCE, staging -> {
                try {
                    Files.write(staging.toPath(), "child probe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (copying) {
                        Files.createFile(new File(args[1]).toPath());
                        System.in.read();
                    }
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                return Unit.INSTANCE;
            }
        );
        if (!installed) throw new IllegalStateException("Fixture publication failed");
        if (!copying) Files.createFile(new File(args[1]).toPath());
        System.in.read();
    }

    private static void runInterruptedReplacement(File target, File ready, String stage) throws Exception {
        File directory = target.getParentFile();
        File replacement = new File(directory, "extension-stage-" + UUID.randomUUID() + ".tmp");
        File rollback = new File(directory, ".rollback-stage-" + UUID.randomUUID() + ".tmp");
        Files.write(replacement.toPath(), "replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(rollback.toPath(), "original".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Android can delete read-only staging entries; Windows cannot, so keep the host fixture writable there.
        if (!System.getProperty("os.name").startsWith("Windows") && !rollback.setReadOnly()) {
            throw new IOException("Could not make rollback copy read-only");
        }

        File journal = PrivateExtensionRecovery.INSTANCE.beginReplacement(target, replacement, rollback);
        if (stage.equals("journal")) awaitTermination(ready, stage);
        Files.move(rollback.toPath(), new File(directory, ".rollback-" + target.getName() + ".backup").toPath(), StandardCopyOption.ATOMIC_MOVE);
        if (stage.equals("rollback-moved")) awaitTermination(ready, stage);
        Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (stage.equals("replacement-published")) awaitTermination(ready, stage);
        PrivateExtensionRecovery.INSTANCE.commitReplacement(journal);
        if (stage.equals("committed")) awaitTermination(ready, stage);
        Files.delete(new File(directory, ".rollback-" + target.getName() + ".backup").toPath());
        if (stage.equals("backup-cleaned")) awaitTermination(ready, stage);
        throw new IllegalArgumentException("Unknown replacement interruption stage: " + stage);
    }

    private static void awaitTermination(File ready, String stage) throws IOException {
        Files.write(ready.toPath(), stage.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.in.read();
    }
}
