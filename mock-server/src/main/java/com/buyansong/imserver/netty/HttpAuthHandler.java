package com.buyansong.imserver.netty;

import com.buyansong.imserver.auth.AuthService;
import com.buyansong.imserver.ImServerLogger;
import com.buyansong.imserver.friend.FriendService;
import com.buyansong.imserver.group.GroupService;
import com.buyansong.imserver.oss.OssUploadService;
import com.google.gson.JsonArray;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class HttpAuthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final AuthService authService;
    private final OssUploadService ossUploadService;
    private final GroupService groupService;
    private final FriendService friendService;

    public HttpAuthHandler(AuthService authService) {
        this(authService, new OssUploadService());
    }

    public HttpAuthHandler(AuthService authService, OssUploadService ossUploadService) {
        this(authService, ossUploadService, new GroupService());
    }

    public HttpAuthHandler(AuthService authService, OssUploadService ossUploadService, GroupService groupService) {
        this(authService, ossUploadService, groupService, new FriendService());
    }

    public HttpAuthHandler(AuthService authService, OssUploadService ossUploadService, GroupService groupService, FriendService friendService) {
        super(false);
        this.authService = authService;
        this.ossUploadService = ossUploadService;
        this.groupService = groupService;
        this.friendService = friendService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        String path = request.uri().split("\\?", 2)[0];
        if ("/ws".equals(path)) {
            context.fireChannelRead(request.retain());
            return;
        }

        try {
            if (request.method() == HttpMethod.GET && "/health".equals(path)) {
                writeJson(context, request, HttpResponseStatus.OK, "{\"status\":\"ok\"}");
                return;
            }

            if (request.method() == HttpMethod.POST && ("/login".equals(path) || "/register".equals(path))) {
                String body = request.content().toString(CharsetUtil.UTF_8);
                JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                String phone = readString(json, "phone", readString(json, "username", ""));
                String password = readString(json, "password", "");
                String response = "/register".equals(path)
                        ? authService.register(phone, password)
                        : authService.login(phone, password);
                writeJson(context, request, HttpResponseStatus.OK, response);
                return;
            }

            if (request.method() == HttpMethod.POST && ("/refresh".equals(path) || "/logout".equals(path))) {
                String body = request.content().toString(CharsetUtil.UTF_8);
                JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                String refreshToken = readString(json, "refreshToken", "");
                String response = "/refresh".equals(path)
                        ? authService.refresh(refreshToken)
                        : authService.logout(refreshToken);
                writeJson(context, request, HttpResponseStatus.OK, response);
                return;
            }

            if (path.startsWith("/friends")) {
                Optional<String> authenticatedPhone = authenticatedPhone(request);
                if (authenticatedPhone.isEmpty()) {
                    writeJson(context, request, HttpResponseStatus.UNAUTHORIZED, authService.failure(401, "Unauthorized"));
                    return;
                }

                if (request.method() == HttpMethod.GET && "/friends/me".equals(path)) {
                    writeJson(context, request, HttpResponseStatus.OK, friendService.friendsJson(authenticatedPhone.get()));
                    return;
                }
            }

            if (request.method() == HttpMethod.POST && "/oss/avatar/upload-target".equals(path)) {
                Optional<String> authenticatedPhone = authenticatedPhone(request);
                if (authenticatedPhone.isEmpty()) {
                    writeJson(context, request, HttpResponseStatus.UNAUTHORIZED, authService.failure(401, "Unauthorized"));
                    return;
                }
                String body = request.content().toString(CharsetUtil.UTF_8);
                JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                String response = ossUploadService.avatarUploadTarget(
                        authenticatedPhone.get(),
                        readString(json, "contentType", "image/jpeg")
                );
                writeJson(context, request, HttpResponseStatus.OK, response);
                return;
            }

            if (request.method() == HttpMethod.POST && "/oss/message-image/upload-targets".equals(path)) {
                Optional<String> authenticatedPhone = authenticatedPhone(request);
                if (authenticatedPhone.isEmpty()) {
                    writeJson(context, request, HttpResponseStatus.UNAUTHORIZED, authService.failure(401, "Unauthorized"));
                    return;
                }
                String body = request.content().toString(CharsetUtil.UTF_8);
                JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                ImServerLogger.log(
                        "[IM] IMAGE_UPLOAD_TARGET_REQUEST userId=%s messageId=%s contentType=%s",
                        authenticatedPhone.get(),
                        readString(json, "messageId", ""),
                        readString(json, "contentType", "image/jpeg")
                );
                String response = ossUploadService.messageImageUploadTargets(
                        authenticatedPhone.get(),
                        readString(json, "messageId", ""),
                        readString(json, "contentType", "image/jpeg")
                );
                ImServerLogger.log(
                        "[IM] IMAGE_UPLOAD_TARGET_ISSUED userId=%s messageId=%s",
                        authenticatedPhone.get(),
                        readString(json, "messageId", "")
                );
                writeJson(context, request, HttpResponseStatus.OK, response);
                return;
            }

            if (path.startsWith("/users")) {
                Optional<String> authenticatedPhone = authenticatedPhone(request);
                if (authenticatedPhone.isEmpty()) {
                    writeJson(context, request, HttpResponseStatus.UNAUTHORIZED, authService.failure(401, "Unauthorized"));
                    return;
                }

                if (request.method() == HttpMethod.GET && "/users/me".equals(path)) {
                    writeJson(context, request, HttpResponseStatus.OK, authService.profile(authenticatedPhone.get()));
                    return;
                }

                if (request.method() == HttpMethod.PUT && "/users/me".equals(path)) {
                    String body = request.content().toString(CharsetUtil.UTF_8);
                    JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                    String response = authService.updateProfile(
                            authenticatedPhone.get(),
                            readNullableString(json, "nickname"),
                            readNullableString(json, "avatarUrl"),
                            readNullableString(json, "avatarObjectKey"),
                            readNullableString(json, "gender"),
                            readNullableString(json, "signature")
                    );
                    writeJson(context, request, HttpResponseStatus.OK, response);
                    return;
                }

                if (request.method() == HttpMethod.POST && "/users/batch".equals(path)) {
                    String body = request.content().toString(CharsetUtil.UTF_8);
                    JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                    JsonArray ids = json.has("userIds") && json.get("userIds").isJsonArray()
                            ? json.getAsJsonArray("userIds")
                            : new JsonArray();
                    List<String> userIds = new ArrayList<>();
                    ids.forEach(id -> {
                        if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                            userIds.add(id.getAsString());
                        }
                    });
                    writeJson(context, request, HttpResponseStatus.OK, authService.profiles(userIds));
                    return;
                }

                if (request.method() == HttpMethod.GET && path.startsWith("/users/")) {
                    String userId = path.substring("/users/".length());
                    writeJson(context, request, HttpResponseStatus.OK, authService.profile(userId));
                    return;
                }
            }

            if (path.startsWith("/groups")) {
                Optional<String> authenticatedPhone = authenticatedPhone(request);
                if (authenticatedPhone.isEmpty()) {
                    writeJson(context, request, HttpResponseStatus.UNAUTHORIZED, authService.failure(401, "Unauthorized"));
                    return;
                }

                if (request.method() == HttpMethod.POST && "/groups".equals(path)) {
                    String body = request.content().toString(CharsetUtil.UTF_8);
                    JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                    JsonArray ids = json.has("memberUserIds") && json.get("memberUserIds").isJsonArray()
                            ? json.getAsJsonArray("memberUserIds")
                            : new JsonArray();
                    List<String> memberUserIds = new ArrayList<>();
                    ids.forEach(id -> {
                        if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                            memberUserIds.add(id.getAsString());
                        }
                    });
                    writeJson(
                            context,
                            request,
                            HttpResponseStatus.OK,
                            groupService.createGroupJson(
                                    authenticatedPhone.get(),
                                    readString(json, "name", ""),
                                    memberUserIds
                            )
                    );
                    return;
                }

                if (request.method() == HttpMethod.GET && "/groups".equals(path)) {
                    writeJson(context, request, HttpResponseStatus.OK, groupService.groupsJson(authenticatedPhone.get()));
                    return;
                }

                if (request.method() == HttpMethod.PATCH && path.startsWith("/groups/")) {
                    String groupId = path.substring("/groups/".length());
                    String body = request.content().toString(CharsetUtil.UTF_8);
                    JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                    writeJson(context, request, HttpResponseStatus.OK, groupService.renameGroupJson(groupId, authenticatedPhone.get(), readString(json, "name", "")));
                    return;
                }

                if (request.method() == HttpMethod.GET && path.startsWith("/groups/")) {
                    String suffix = path.substring("/groups/".length());
                    if (suffix.endsWith("/members")) {
                        String groupId = suffix.substring(0, suffix.length() - "/members".length());
                        writeJson(context, request, HttpResponseStatus.OK, groupService.membersJson(groupId, authenticatedPhone.get()));
                        return;
                    }
                    writeJson(context, request, HttpResponseStatus.OK, groupService.groupJson(suffix, authenticatedPhone.get()));
                    return;
                }
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

    private String readString(JsonObject json, String name, String fallback) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : fallback;
    }

    private String readNullableString(JsonObject json, String name) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : null;
    }

    private Optional<String> authenticatedPhone(FullHttpRequest request) {
        String header = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return authService.verifyAccessToken(header.substring("Bearer ".length()).trim());
    }
}
