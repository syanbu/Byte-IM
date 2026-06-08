package com.buyansong.imserver;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ImServerLogger {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile Clock clock = Clock.systemDefaultZone();

    private ImServerLogger() {
    }

    public static void log(String format, Object... args) {
        String timestamp = LocalDateTime.now(clock).format(FORMATTER);
        System.out.printf("%s %s%n", timestamp, String.format(format, args));
    }

    static Clock clock() {
        return clock;
    }

    static void setClock(Clock replacementClock) {
        clock = replacementClock;
    }
}
