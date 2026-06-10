package com.buyansong.imserver;

import com.buyansong.imserver.auth.AuthService;
import com.buyansong.imserver.auth.PasswordHasher;
import com.buyansong.imserver.auth.SecureSaltGenerator;
import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.auth.UserStore;
import com.buyansong.imserver.friend.FriendService;
import com.buyansong.imserver.friend.FriendStore;
import com.buyansong.imserver.group.GroupService;
import com.buyansong.imserver.group.SQLiteGroupStore;
import com.buyansong.imserver.groupread.GroupReadCursorStore;
import com.buyansong.imserver.groupread.SQLiteGroupReadCursorStore;
import com.buyansong.imserver.netty.HttpAuthHandler;
import com.buyansong.imserver.netty.WebSocketFrameHandler;
import com.buyansong.imserver.session.ClientSessionRegistry;
import com.buyansong.imserver.session.MessageRouter;
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
        UserStore userStore = new UserStore(java.nio.file.Path.of("data", "mock-im-users.sqlite"));
        GroupService groupService = new GroupService(
                new SQLiteGroupStore(java.nio.file.Path.of("data", "mock-im-groups.sqlite")),
                userStore,
                System::currentTimeMillis
        );
        FriendService friendService = new FriendService(
                new FriendStore(java.nio.file.Path.of("data", "mock-im-friends.sqlite")),
                System::currentTimeMillis,
                userStore::profileUpdatedAtByPhone
        );
        java.nio.file.Path messageDatabase = java.nio.file.Path.of("data", "mock-im-messages.sqlite");
        GroupReadCursorStore groupReadCursorStore = new SQLiteGroupReadCursorStore(messageDatabase);
        MessageRouter messageRouter = new MessageRouter(
                registry,
                tokenService,
                new MessageRouter.SQLiteServerSeqStore(java.nio.file.Path.of("data", "mock-im-sequences.sqlite")),
                new MessageRouter.SQLiteAcceptedMessageStore(messageDatabase),
                groupService,
                groupReadCursorStore,
                userStore,
                System::currentTimeMillis
        );
        AuthService authService = new AuthService(
                userStore,
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
                                    .addLast(new HttpAuthHandler(authService, new com.buyansong.imserver.oss.OssUploadService(), groupService, friendService))
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
