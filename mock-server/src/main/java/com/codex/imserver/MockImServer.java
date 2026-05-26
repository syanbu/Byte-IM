package com.codex.imserver;

import com.codex.imserver.auth.AuthService;
import com.codex.imserver.auth.PasswordHasher;
import com.codex.imserver.auth.SecureSaltGenerator;
import com.codex.imserver.auth.TokenService;
import com.codex.imserver.auth.UserStore;
import com.codex.imserver.netty.HttpAuthHandler;
import com.codex.imserver.netty.WebSocketFrameHandler;
import com.codex.imserver.session.ClientSessionRegistry;
import com.codex.imserver.session.MessageRouter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

public final class MockImServer {
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new MockImServer().start(port);
    }

    public void start(int port) throws InterruptedException {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        TokenService tokenService = TokenService.defaultService();
        MessageRouter messageRouter = new MessageRouter(registry, tokenService);
        AuthService authService = new AuthService(
                new UserStore(java.nio.file.Path.of("data", "mock-im-users.sqlite")),
                new PasswordHasher(new SecureSaltGenerator()),
                tokenService
        );
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            Channel channel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(64 * 1024))
                                    .addLast(new HttpAuthHandler(authService))
                                    .addLast(new WebSocketServerProtocolHandler("/ws", null, true))
                                    .addLast(new WebSocketFrameHandler(registry, messageRouter));
                        }
                    })
                    .bind(port)
                    .sync()
                    .channel();
            ImServerLogger.log("Mock IM server listening on http://127.0.0.1:%d", port);
            ImServerLogger.log("WebSocket endpoint ws://127.0.0.1:%d/ws", port);
            channel.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
