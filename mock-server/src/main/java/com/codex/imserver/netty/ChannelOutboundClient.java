package com.codex.imserver.netty;

import com.codex.imserver.protocol.ImPacket;
import com.codex.imserver.protocol.ImPacketCodec;
import com.codex.imserver.session.OutboundClient;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

public final class ChannelOutboundClient implements OutboundClient {
    private final Channel channel;

    public ChannelOutboundClient(Channel channel) {
        this.channel = channel;
    }

    @Override
    public void send(ImPacket packet) {
        channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(ImPacketCodec.encode(packet))));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChannelOutboundClient that)) {
            return false;
        }
        return channel.id().equals(that.channel.id());
    }

    @Override
    public int hashCode() {
        return channel.id().hashCode();
    }
}
