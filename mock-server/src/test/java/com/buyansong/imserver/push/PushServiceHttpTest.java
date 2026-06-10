package com.buyansong.imserver.push;

import com.buyansong.imserver.auth.AuthService;
import com.buyansong.imserver.auth.PasswordHasher;
import com.buyansong.imserver.auth.SecureSaltGenerator;
import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.auth.UserStore;
import com.buyansong.imserver.netty.HttpAuthHandler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class PushServiceHttpTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void registerToken_requiresAuthAndPushToken() throws Exception {
        Fixture fixture = new Fixture(folder);

        FullHttpResponse unauthorized = fixture.request(HttpMethod.POST, "/push/register-token", "{}", null);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, unauthorized.status());

        FullHttpResponse missing = fixture.request(HttpMethod.POST, "/push/register-token", "{}", fixture.accessToken);
        assertEquals(HttpResponseStatus.BAD_REQUEST, missing.status());

        FullHttpResponse ok = fixture.request(
                HttpMethod.POST,
                "/push/register-token",
                "{\"pushToken\":\"mock-token\",\"platform\":\"android\",\"deviceId\":\"device-1\"}",
                fixture.accessToken
        );
        assertEquals(HttpResponseStatus.OK, ok.status());
        assertEquals("mock-token", fixture.tokenStore.findByUserId(fixture.userId).orElseThrow().pushToken());
    }

    @Test
    public void pendingAndAck_returnOnlyCurrentUsersUnackedRows() throws Exception {
        Fixture fixture = new Fixture(folder);
        fixture.notificationStore.enqueueIfAbsent(fixture.userId, message("m_1", "hello"), 1_000L);
        fixture.notificationStore.enqueueIfAbsent(fixture.userId, message("m_2", "again"), 2_000L);
        fixture.notificationStore.enqueueIfAbsent("13800138001", message("m_other", "nope"), 3_000L);

        FullHttpResponse pending = fixture.request(HttpMethod.GET, "/push/pending?since=0&limit=1", "", fixture.accessToken);
        JsonObject pendingJson = JsonParser.parseString(pending.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
        assertEquals(HttpResponseStatus.OK, pending.status());
        assertEquals(1, pendingJson.getAsJsonObject("data").getAsJsonArray("pending").size());
        long pushId = pendingJson.getAsJsonObject("data").getAsJsonArray("pending")
                .get(0).getAsJsonObject().get("pushId").getAsLong();

        FullHttpResponse ack = fixture.request(
                HttpMethod.POST,
                "/push/ack",
                "{\"pushIds\":[" + pushId + "]}",
                fixture.accessToken
        );
        assertEquals(HttpResponseStatus.OK, ack.status());
        assertEquals(1, JsonParser.parseString(ack.content().toString(CharsetUtil.UTF_8))
                .getAsJsonObject().getAsJsonObject("data").get("ackedCount").getAsInt());
    }

    private static JsonObject message(String messageId, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("senderId", "13900139000");
        message.addProperty("conversationId", "single:13900139000:13800138000");
        message.addProperty("messageId", messageId);
        message.addProperty("messageType", "TEXT");
        message.addProperty("content", content);
        message.addProperty("serverSeq", 1L);
        message.addProperty("serverTime", 1_000L);
        return message;
    }

    private static final class Fixture {
        final String userId = "13800138000";
        final String accessToken;
        final PushTokenStore tokenStore = new InMemoryPushTokenStore();
        final PushNotificationStore notificationStore = new InMemoryPushNotificationStore();
        final EmbeddedChannel channel;

        Fixture(TemporaryFolder folder) throws Exception {
            Path users = folder.newFile("users.sqlite").toPath();
            TokenService tokenService = new TokenService("test-secret", new AtomicLong(1_000L)::get, 60_000L, 60_000L, () -> "refresh-token");
            AuthService authService = new AuthService(
                    new UserStore(users),
                    new PasswordHasher(new SecureSaltGenerator()),
                    tokenService
            );
            JsonObject register = JsonParser.parseString(authService.register(userId, "password")).getAsJsonObject();
            this.accessToken = register.getAsJsonObject("data").get("accessToken").getAsString();
            PushService pushService = new PushService(tokenStore, notificationStore, () -> 5_000L);
            this.channel = new EmbeddedChannel(new HttpAuthHandler(authService, pushService));
        }

        FullHttpResponse request(HttpMethod method, String uri, String body, String accessToken) {
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    method,
                    uri,
                    Unpooled.copiedBuffer(body, CharsetUtil.UTF_8)
            );
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes());
            if (accessToken != null) {
                request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + accessToken);
            }
            channel.writeInbound(request);
            return channel.readOutbound();
        }
    }
}
