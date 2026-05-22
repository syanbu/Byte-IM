package com.codex.imserver.auth;

public interface SaltGenerator {
    String nextSalt();
}
