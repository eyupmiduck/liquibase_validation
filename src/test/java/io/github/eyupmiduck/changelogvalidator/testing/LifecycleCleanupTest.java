package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the cleanup contracts of the upgrade and rehearsal lifecycle bases:
 * one failed close or drop must not skip the remaining scratch databases, and a
 * cleanup failure must not replace the setup failure.
 */
class LifecycleCleanupTest {

    private static Connection connectionWhoseCloseFails() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        throw new SQLException("close failed");
                    }
                    return null;
                });
    }

    /**
     * The upgraded connection's close failure and the first database drop
     * failure are both recorded, and the second database is still dropped.
     */
    @Test
    void dropsEveryScratchDatabaseEvenWhenCloseAndFirstDropFail() {
        List<String> dropped = new ArrayList<>();

        Exception failure = UpgradeTestBase.closeAndDropAll(
                connectionWhoseCloseFails(),
                Arrays.asList("upgrade_x", "fresh_x"),
                database -> {
                    dropped.add(database);
                    if ("upgrade_x".equals(database)) {
                        throw new SQLException("drop failed: " + database);
                    }
                });

        assertEquals(List.of("upgrade_x", "fresh_x"), dropped);
        assertNotNull(failure);
        assertEquals("close failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length,
                "the first drop failure should be attached to the close failure");
        assertEquals("drop failed: upgrade_x", failure.getSuppressed()[0].getMessage());
    }

    /**
     * With no connection failure, the first drop failure is the primary failure
     * and the second database is still dropped.
     */
    @Test
    void dropsBothScratchDatabasesWhenTheFirstDropFails() {
        List<String> dropped = new ArrayList<>();

        Exception failure = UpgradeTestBase.closeAndDropAll(null,
                Arrays.asList("upgrade_x", "fresh_x"),
                database -> {
                    dropped.add(database);
                    if ("upgrade_x".equals(database)) {
                        throw new SQLException("drop failed: " + database);
                    }
                });

        assertEquals(List.of("upgrade_x", "fresh_x"), dropped);
        assertNotNull(failure);
        assertEquals("drop failed: upgrade_x", failure.getMessage());
    }

    /**
     * A rehearsal setup failure remains the primary exception when cleanup also
     * fails; the cleanup failure is attached as suppressed.
     */
    @Test
    void rehearsalCleanupFailureIsSuppressedOnTheSetupFailure() {
        Exception setupFailure = new Exception("setup failed");

        Exception result = ChangelogRehearsalTestBase.cleanupFailure(setupFailure,
                () -> {
                    throw new SQLException("cleanup failed");
                });

        assertSame(setupFailure, result);
        assertEquals(1, setupFailure.getSuppressed().length);
        assertEquals("cleanup failed", setupFailure.getSuppressed()[0].getMessage());
    }

    /**
     * A container whose template preparation failed is stopped, and a stop
     * failure is attached to the startup failure rather than masking it.
     */
    @Test
    void containerResetStopsAndSuppressesStopFailure() {
        Exception startupFailure = new Exception("template preparation failed");

        PostgresTestBase.resetContainerAfterFailure(
                () -> {
                    throw new IllegalStateException("stop failed");
                },
                startupFailure);

        assertEquals(1, startupFailure.getSuppressed().length);
        assertEquals("stop failed", startupFailure.getSuppressed()[0].getMessage());
    }
}
