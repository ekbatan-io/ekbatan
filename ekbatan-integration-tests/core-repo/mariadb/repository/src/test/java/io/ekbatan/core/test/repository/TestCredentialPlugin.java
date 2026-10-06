package io.ekbatan.core.test.repository;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.mariadb.jdbc.plugin.Credential;
import org.mariadb.jdbc.plugin.CredentialPlugin;

/**
 * A MariaDB login plugin of the kind a secret store provides - {@code credentialType=EKBATAN_TEST}
 * in a connection's settings makes the driver ask it for the user and password of every login.
 * Registered through {@code META-INF/services}, as MariaDB's driver finds such plugins.
 */
public final class TestCredentialPlugin implements CredentialPlugin {

    /** What the plugin hands out; set by the test. */
    static final AtomicReference<Credential> CURRENT = new AtomicReference<>();

    /** How many logins asked it. */
    static final AtomicInteger ASKED = new AtomicInteger();

    @Override
    public String type() {
        return "EKBATAN_TEST";
    }

    @Override
    public Credential get() {
        ASKED.incrementAndGet();
        return CURRENT.get();
    }
}
