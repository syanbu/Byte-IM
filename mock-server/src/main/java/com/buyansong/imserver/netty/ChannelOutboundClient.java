package com.buyansong.imserver.netty;

import com.buyansong.im.protocol.v2.ImEnvelope;
import com.buyansong.imserver.protocol.ImEnvelopeCodec;
import com.buyansong.imserver.session.OutboundClient;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

public final class ChannelOutboundClient implements OutboundClient {
    private final Channel channel;

    public ChannelOutboundClient(Channel channel) {
        this.channel = channel;
    }

    @Override
    public void send(ImEnvelope envelope) {
        channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(ImEnvelopeCodec.encode(envelope))));
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
