package com.buyansong.imserver.netty;

import com.buyansong.im.protocol.v2.ImEnvelope;
import com.buyansong.imserver.ImServerLogger;
import com.buyansong.imserver.auth.TokenService.AuthFailureReason;
import com.buyansong.imserver.protocol.ImEnvelopeCodec;
import com.buyansong.imserver.protocol.ProtocolException;
import com.buyansong.imserver.session.ClientSessionRegistry;
import com.buyansong.imserver.session.MessageProtoMapper;
import com.buyansong.imserver.session.MessageRouter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

public final class WebSocketFrameHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {
    private final ClientSessionRegistry registry;
    private final MessageRouter messageRouter;

    public WebSocketFrameHandler(ClientSessionRegistry registry, MessageRouter messageRouter) {
        this.registry = registry;
        this.messageRouter = messageRouter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, BinaryWebSocketFrame frame) {
        ChannelOutboundClient client = new ChannelOutboundClient(context.channel());
        ImEnvelope envelope;
        try {
            byte[] bytes = new byte[frame.content().readableBytes()];
            frame.content().readBytes(bytes);
            envelope = ImEnvelopeCodec.decode(bytes);
        } catch (ProtocolException error) {
            ImServerLogger.log("[IM] WebSocket protocol failure: %s", error.getMessage());
            context.close();
            return;
        }
        try {
            switch (envelope.getPayloadCase()) {
                case AUTH -> {
                    AuthFailureReason reason = messageRouter.handleAuth(envelope.getAuth().getAccessToken(), client);
                    if (reason != null) {
                        client.send(MessageProtoMapper.authNackEnvelope(toProtoReason(reason)));
                        context.close();
                    }
                }
                case HEARTBEAT -> {
                    if (registry.userIdOf(client).isEmpty()) {
                        ImServerLogger.log("[IM] HEARTBEAT rejected unauthenticated client");
                        return;
                    }
                    messageRouter.handleHeartbeat(client, envelope.getHeartbeat());
                }
                case SEND_MESSAGE -> {
                    String senderUserId = registry.userIdOf(client).orElse(null);
                    if (senderUserId == null) {
                        ImServerLogger.log("[IM] SEND_MESSAGE rejected unauthenticated client");
                        return;
                    }
                    messageRouter.handleSendMessage(senderUserId, envelope.getSendMessage());
                }
                case DELIVERY_ACK -> {
                    String receiverUserId = registry.userIdOf(client).orElse(null);
                    if (receiverUserId == null) {
                        ImServerLogger.log("[IM] DELIVERY_ACK rejected unauthenticated client");
                        return;
                    }
                    messageRouter.handleDeliveryAck(receiverUserId, envelope.getDeliveryAck());
                }
                case READ_ACK -> {
                    String readerUserId = registry.userIdOf(client).orElse(null);
                    if (readerUserId == null) {
                        ImServerLogger.log("[IM] READ_ACK rejected unauthenticated client");
                        return;
                    }
                    messageRouter.handleReadAck(readerUserId, envelope.getReadAck());
                }
                case RECALL_MESSAGE -> {
                    String requesterUserId = registry.userIdOf(client).orElse(null);
                    if (requesterUserId == null) {
                        ImServerLogger.log("[IM] RECALL_MESSAGE rejected unauthenticated client");
                        return;
                    }
                    messageRouter.handleRecallMessage(requesterUserId, envelope.getRecallMessage());
                }
                case RECALL_NOTIFY_ACK -> {
                    String receiverUserId = registry.userIdOf(client).orElse(null);
                    if (receiverUserId == null) {
                        ImServerLogger.log("[IM] RECALL_NOTIFY_ACK rejected unauthenticated client");
                        return;
                    }
                    messageRouter.handleRecallNotifyAck(receiverUserId, envelope.getRecallNotifyAck());
                }
                default -> {
                    ImServerLogger.log("[IM] Protocol failure: unexpected client payload %s", envelope.getPayloadCase());
                    context.close();
                }
            }
        } catch (ProtocolException error) {
            ImServerLogger.log("[IM] WebSocket protocol failure: %s", error.getMessage());
            context.close();
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

    private static com.buyansong.im.protocol.v2.AuthFailureReason toProtoReason(AuthFailureReason reason) {
        return switch (reason) {
            case TOKEN_EXPIRED -> com.buyansong.im.protocol.v2.AuthFailureReason.AUTH_FAILURE_REASON_TOKEN_EXPIRED;
            case TOKEN_INVALID -> com.buyansong.im.protocol.v2.AuthFailureReason.AUTH_FAILURE_REASON_TOKEN_INVALID;
            case TOKEN_MISSING -> com.buyansong.im.protocol.v2.AuthFailureReason.AUTH_FAILURE_REASON_TOKEN_MISSING;
        };
    }
}
