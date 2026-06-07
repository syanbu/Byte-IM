package com.buyansong.imserver.auth;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UserStoreTest {
    @Test
    public void usesRequestedSqliteDatabaseFileWithoutH2Sidecar() throws Exception {
        Path directory = Files.createTempDirectory("mock-im-sqlite");
        Path database = directory.resolve("mock-im-users.sqlite");

        new UserStore(database);

        assertTrue(Files.exists(database));
        assertFalse(Files.exists(directory.resolve("mock-im-users.sqlite.mv.db")));
        assertFalse(Files.exists(directory.resolve("mock-im-users.sqlite.trace.db")));
    }
}
