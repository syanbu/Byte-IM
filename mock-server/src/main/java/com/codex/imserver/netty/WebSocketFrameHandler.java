package com.codex.imserver.netty;

import com.codex.imserver.ImServerLogger;
import com.codex.imserver.auth.TokenService.AuthFailureReason;
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
                AuthFailureReason reason = messageRouter.handleAuth(body.has("token") ? body.get("token").getAsString() : null, client);
                if (reason != null) {
                    context.writeAndFlush(new BinaryWebSocketFrame(context.alloc().buffer().writeBytes(
                            ImPacketCodec.encode(authNack(reason))
                    )));
                    context.close();
                }
                return;
            }
            if (packet.cmd() == ImCommand.HEARTBEAT.value()) {
                if (registry.userIdOf(client).isEmpty()) {
                    ImServerLogger.log("[IM] HEARTBEAT rejected unauthenticated client");
                    return;
                }
                messageRouter.handleHeartbeat(client);
                return;
            }
            if (packet.cmd() == ImCommand.SEND_MESSAGE.value()) {
                String senderUserId = registry.userIdOf(client).orElse(null);
                if (senderUserId == null) {
                    ImServerLogger.log("[IM] SEND_MESSAGE rejected unauthenticated client");
                    return;
                }
                messageRouter.handleSendMessage(senderUserId, packet);
                return;
            }
            if (packet.cmd() == ImCommand.DELIVERY_ACK.value()) {
                String receiverUserId = registry.userIdOf(client).orElse(null);
                if (receiverUserId == null) {
                    ImServerLogger.log("[IM] DELIVERY_ACK rejected unauthenticated client");
                    return;
                }
                messageRouter.handleDeliveryAck(receiverUserId, packet);
                return;
            }
            if (packet.cmd() == ImCommand.READ_ACK.value()) {
                String readerUserId = registry.userIdOf(client).orElse(null);
                if (readerUserId == null) {
                    ImServerLogger.log("[IM] READ_ACK rejected unauthenticated client");
                    return;
                }
                messageRouter.handleReadAck(readerUserId, packet);
                return;
            }
            if (packet.cmd() == ImCommand.RECALL_MESSAGE.value()) {
                String requesterUserId = registry.userIdOf(client).orElse(null);
                if (requesterUserId == null) {
                    ImServerLogger.log("[IM] RECALL_MESSAGE rejected unauthenticated client");
                    return;
                }
                messageRouter.handleRecallMessage(requesterUserId, packet);
                return;
            }
            if (packet.cmd() == ImCommand.RECALL_NOTIFY_ACK.value()) {
                String receiverUserId = registry.userIdOf(client).orElse(null);
                if (receiverUserId == null) {
                    ImServerLogger.log("[IM] RECALL_NOTIFY_ACK rejected unauthenticated client");
                    return;
                }
                messageRouter.handleRecallNotifyAck(receiverUserId, packet);
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

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        ImServerLogger.log("[IM] WebSocket connection exception: %s", cause.getMessage());
        context.close();
    }

    private ImPacket authNack(AuthFailureReason reason) {
        JsonObject body = new JsonObject();
        body.addProperty("reason", reason.name());
        return new ImPacket(ImCommand.AUTH_NACK.value(), body.toString().getBytes(StandardCharsets.UTF_8));
    }
}
