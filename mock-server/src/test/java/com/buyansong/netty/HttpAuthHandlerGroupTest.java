package com.buyansong.imserver.netty;

import com.buyansong.imserver.auth.AuthService;
import com.buyansong.imserver.auth.PasswordHasher;
import com.buyansong.imserver.auth.SaltGenerator;
import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.auth.UserStore;
import com.buyansong.imserver.group.GroupService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public class HttpAuthHandlerGroupTest {
    @Test
    public void postGroupsCreatesGroupForAuthenticatedUser() throws Exception {
        TokenService tokenService = new TokenService("test-secret", () -> 1_000L, 60_000L, 120_000L, () -> "refresh-token-a-with-enough-entropy-for-test");
        AuthService authService = new AuthService(
                new UserStore(Files.createTempFile("mock-im-users", ".db")),
                new PasswordHasher(new TestSaltGenerator()),
                tokenService
        );
        authService.register("13800113800", "P@ssw0rd");
        String accessToken = JsonParser.parseString(authService.login("13800113800", "P@ssw0rd"))
                .getAsJsonObject()
                .getAsJsonObject("data")
                .get("accessToken")
                .getAsString();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpAuthHandler(authService, new com.buyansong.imserver.oss.OssUploadService(), new GroupService(() -> 2_000L)));
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/groups",
                Unpooled.copiedBuffer("""
                    {"name":"群聊(3)","memberUserIds":["13900113900","13700113700"]}
                    """, StandardCharsets.UTF_8)
        );
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + accessToken);

        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        JsonObject body = JsonParser.parseString(response.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(0, body.get("code").getAsInt());
        assertEquals("g_1001", body.getAsJsonObject("data").get("groupId").getAsString());
        assertEquals("13800113800", body.getAsJsonObject("data").get("ownerId").getAsString());
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void patchGroupRenamesGroupForAuthenticatedMember() throws Exception {
        TokenService tokenService = new TokenService("test-secret", () -> 1_000L, 60_000L, 120_000L, () -> "refresh-token-a-with-enough-entropy-for-test");
        AuthService authService = new AuthService(
                new UserStore(Files.createTempFile("mock-im-users", ".db")),
                new PasswordHasher(new TestSaltGenerator()),
                tokenService
        );
        authService.register("13800113800", "P@ssw0rd");
        String accessToken = JsonParser.parseString(authService.login("13800113800", "P@ssw0rd"))
                .getAsJsonObject()
                .getAsJsonObject("data")
                .get("accessToken")
                .getAsString();
        GroupService groupService = new GroupService(() -> 2_000L);
        groupService.createGroup("13800113800", "旧群名", java.util.List.of("13900113900"));
        EmbeddedChannel channel = new EmbeddedChannel(new HttpAuthHandler(authService, new com.buyansong.imserver.oss.OssUploadService(), groupService));
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.PATCH,
                "/groups/g_1001",
                Unpooled.copiedBuffer("{\"name\":\"新群名\"}", StandardCharsets.UTF_8)
        );
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + accessToken);

        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        JsonObject body = JsonParser.parseString(response.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(0, body.get("code").getAsInt());
        assertEquals("新群名", body.getAsJsonObject("data").get("name").getAsString());
        response.release();
        channel.finishAndReleaseAll();
    }

    private static final class TestSaltGenerator implements SaltGenerator {
        @Override
        public String nextSalt() {
            return "fixed-salt";
        }
    }
}
