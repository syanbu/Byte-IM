package com.codex.imserver.netty;

import com.codex.imserver.ImServerLogger;
import com.codex.imserver.protocol.ImCommand;
import com.codex.imserver.protocol.ImPacket;
import com.codex.imserver.protocol.ImPacketCodec;
import com.codex.imserver.session.ClientSessionRegistry;
import com.codex.imserver.session.MessageRouter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.nio.charset.StandardCharsets;

public final class WebSocketFrameHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {
    private final ClientSessionRegistry registry;
    private final MessageRouter messageRouter;

    public WebSocketFrameHandler(ClientSessionRegistry registry, MessageRouter messageRouter) {
        this.registry = registry;
        this.messageRouter = messageRouter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, BinaryWebSocketFrame frame) {
        try {
            byte[] bytes = new byte[frame.content().readableBytes()];
            frame.content().readBytes(bytes);
            ImPacket packet = ImPacketCodec.decode(bytes);
            ChannelOutboundClient client = new ChannelOutboundClient(context.channel());

            if (packet.cmd() == ImCommand.AUTH.value()) {
                JsonObject body = JsonParser.parseString(new String(packet.body(), StandardCharsets.UTF_8)).getAsJsonObject();
                messageRouter.handleAuth(body.get("token").getAsString(), client);
                return;
            }
            if (packet.cmd() == ImCommand.HEARTBEAT.value()) {
                messageRouter.handleHeartbeat(client);
                return;
            }
            if (packet.cmd() == ImCommand.SEND_MESSAGE.value()) {
                String senderUserId = registry.userIdOf(client).orElseGet(() -> senderId(packet));
                messageRouter.handleSendMessage(senderUserId, packet);
                return;
            }
            ImServerLogger.log("[IM] Unknown packet cmd=%d", packet.cmd());
        } catch (RuntimeException error) {
            ImServerLogger.log("[IM] WebSocket packet error: %s", error.getMessage());
            error.printStackTrace(System.out);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        registry.remove(new ChannelOutboundClient(context.channel()));
        super.channelInactive(context);
    }

    private String senderId(ImPacket packet) {
        JsonObject body = JsonParser.parseString(new String(packet.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        return body.get("senderId").getAsString();
    }
}
