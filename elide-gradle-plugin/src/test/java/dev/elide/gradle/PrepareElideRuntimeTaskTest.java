package dev.elide.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrepareElideRuntimeTaskTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acquiresLockImmediatelyWhenUncontended() throws Exception {
        Path lockFile = temporaryDirectory.resolve("test.lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = PrepareElideRuntimeTask.lock(channel,
                    Logging.getLogger(PrepareElideRuntimeTask.class),
                    Duration.ofSeconds(5));
            assertTrue(lock.isValid());
            lock.release();
        }
    }

    @Test
    void failsAfterDeadlineWhenLockIsHeldBySameJvm() throws Exception {
        Path lockFile = temporaryDirectory.resolve("test.lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertNotNull(held);

            long start = System.nanoTime();
            GradleException exception = assertThrows(GradleException.class, () ->
                    PrepareElideRuntimeTask.lock(channel,
                            Logging.getLogger(PrepareElideRuntimeTask.class),
                            Duration.ofSeconds(1)));
            long elapsed = System.nanoTime() - start;

            assertTrue(elapsed >= Duration.ofSeconds(1).toNanos(),
                    "should wait at least 1 second before timing out");
            assertTrue(elapsed < Duration.ofSeconds(10).toNanos(),
                    "should time out within 10 seconds, took "
                            + Duration.ofNanos(elapsed).toSeconds() + "s");
            assertTrue(exception.getMessage().contains("Timed out"),
                    "error message should mention timeout: " + exception.getMessage());
            held.release();
        }
    }

    @Test
    void nextWaiterAcquiresLockAfterRelease() throws Exception {
        Path lockFile = temporaryDirectory.resolve("test.lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock first = PrepareElideRuntimeTask.lock(channel,
                    Logging.getLogger(PrepareElideRuntimeTask.class),
                    Duration.ofSeconds(5));
            first.release();

            FileLock second = PrepareElideRuntimeTask.lock(channel,
                    Logging.getLogger(PrepareElideRuntimeTask.class),
                    Duration.ofSeconds(5));
            assertTrue(second.isValid());
            second.release();
        }
    }
}
