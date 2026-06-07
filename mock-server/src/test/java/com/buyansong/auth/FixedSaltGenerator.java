package com.buyansong.imserver.auth;

final class FixedSaltGenerator implements SaltGenerator {
    private final String salt;

    FixedSaltGenerator(String salt) {
        this.salt = salt;
    }

    @Override
    public String nextSalt() {
        return salt;
    }
}
