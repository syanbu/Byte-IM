package com.codex.imserver.netty;

import com.codex.imserver.session.ClientSessionRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ChannelOutboundClientTest {
    @Test
    public void registryCanRemoveEquivalentClientForSameChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ClientSessionRegistry registry = new ClientSessionRegistry();
        registry.register("alice", new ChannelOutboundClient(channel));

        registry.remove(new ChannelOutboundClient(channel));

        assertTrue(registry.find("alice").isEmpty());
    }
}
