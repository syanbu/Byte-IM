package com.codex.imserver.netty;

import com.codex.imserver.auth.AuthService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

public final class HttpAuthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final AuthService authService;

    public HttpAuthHandler(AuthService authService) {
        super(false);
        this.authService = authService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        if ("/ws".equals(request.uri())) {
            context.fireChannelRead(request.retain());
            return;
        }

        try {
            if (request.method() == HttpMethod.GET && "/health".equals(request.uri())) {
                writeJson(context, request, HttpResponseStatus.OK, "{\"status\":\"ok\"}");
                return;
            }

            if (request.method() == HttpMethod.POST && ("/login".equals(request.uri()) || "/register".equals(request.uri()))) {
                String body = request.content().toString(CharsetUtil.UTF_8);
                JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                String username = json.has("username") ? json.get("username").getAsString() : "u1";
                String response = "/register".equals(request.uri()) ? authService.register(username) : authService.login(username);
                writeJson(context, request, HttpResponseStatus.OK, response);
                return;
            }

            writeJson(context, request, HttpResponseStatus.NOT_FOUND, authService.failure("not found"));
        } finally {
            ReferenceCountUtil.release(request);
        }
    }

    private void writeJson(ChannelHandlerContext context, FullHttpRequest request, HttpResponseStatus status, String json) {
        FullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.copiedBuffer(json, CharsetUtil.UTF_8)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        if (HttpUtil.isKeepAlive(request)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        context.writeAndFlush(response);
    }
}
