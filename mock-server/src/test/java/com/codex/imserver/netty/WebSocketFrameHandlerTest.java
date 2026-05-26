package com.codex.imserver.netty;

import com.codex.imserver.session.ClientSessionRegistry;
import com.codex.imserver.session.MessageRouter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebSocketFrameHandlerTest {
    @Test
    public void exceptionCaughtClosesChannelAndLogsWithoutUnhandledPipelineWarning() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = new MessageRouter(registry);
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketFrameHandler(registry, router));
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(output));
            channel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
            channel.runPendingTasks();
        } finally {
            System.setOut(originalOut);
            channel.finishAndReleaseAll();
        }

        String log = output.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("[IM] WebSocket connection exception: Connection reset"));
        assertFalse(channel.isActive());
    }
}
