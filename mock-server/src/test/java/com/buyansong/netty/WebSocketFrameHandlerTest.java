package com.buyansong.imserver.netty;

import com.buyansong.imserver.protocol.ImCommand;
import com.buyansong.imserver.protocol.ImPacket;
import com.buyansong.imserver.protocol.ImPacketCodec;
import com.buyansong.imserver.session.ClientSessionRegistry;
import com.buyansong.imserver.session.MessageRouter;
import com.google.gson.JsonObject;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebSocketFrameHandlerTest {
    @Test
    public void authFailureSendsAuthNackAndClosesChannel() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = new MessageRouter(registry);
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketFrameHandler(registry, router));

        JsonObject body = new JsonObject();
        body.addProperty("token", "invalid-token");
        BinaryWebSocketFrame frame = new BinaryWebSocketFrame(channel.alloc().buffer().writeBytes(
                ImPacketCodec.encode(new ImPacket(ImCommand.AUTH.value(), body.toString().getBytes(StandardCharsets.UTF_8)))
        ));

        try {
            channel.writeInbound(frame);

            BinaryWebSocketFrame outbound = channel.readOutbound();
            assertNotNull(outbound);
            byte[] bytes = new byte[outbound.content().readableBytes()];
            outbound.content().readBytes(bytes);
            ImPacket packet = ImPacketCodec.decode(bytes);
            assertEquals(ImCommand.AUTH_NACK.value(), packet.cmd());
            assertTrue(new String(packet.body(), StandardCharsets.UTF_8).contains("\"reason\":\"TOKEN_INVALID\""));
            channel.runPendingTasks();
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

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

    @Test
    public void unauthenticatedSendMessageIsRejectedWithoutAckOrPersistence() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = new MessageRouter(registry);
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketFrameHandler(registry, router));
        JsonObject body = new JsonObject();
        body.addProperty("messageId", "unauth-1");
        body.addProperty("conversationId", "single:13800113800:13900113900");
        body.addProperty("senderId", "13800113800");
        body.addProperty("receiverId", "13900113900");
        body.addProperty("clientSeq", 1L);
        body.addProperty("content", "blocked");
        BinaryWebSocketFrame frame = new BinaryWebSocketFrame(channel.alloc().buffer().writeBytes(
                ImPacketCodec.encode(new ImPacket(ImCommand.SEND_MESSAGE.value(), body.toString().getBytes(StandardCharsets.UTF_8)))
        ));

        try {
            channel.writeInbound(frame);

            assertNull(channel.readOutbound());
            assertTrue(registry.find("13800113800").isEmpty());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
