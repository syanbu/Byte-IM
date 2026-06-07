package com.buyansong.imserver;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;

public class ImServerLoggerTest {
    @Test
    public void logPrefixesMessageWithMillisecondTimestamp() {
        Clock originalClock = ImServerLogger.clock();
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            ImServerLogger.setClock(Clock.fixed(
                    Instant.parse("2026-05-26T02:03:04.567Z"),
                    ZoneId.of("Asia/Shanghai")
            ));
            System.setOut(new PrintStream(output));

            ImServerLogger.log("[IM] HEARTBEAT received userId=%s", "13800113800");
        } finally {
            System.setOut(originalOut);
            ImServerLogger.setClock(originalClock);
        }

        assertEquals(
                "2026-05-26 10:03:04.567 [IM] HEARTBEAT received userId=13800113800%n".formatted(),
                output.toString()
        );
    }
}
