package com.codex.imserver.oss;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OssUploadServiceTest {
    @Test
    public void createsSignedAvatarUploadTarget() {
        OssUploadService service = new OssUploadService(
                new OssUploadService.Config(
                        "test-key",
                        "test-secret",
                        "oss-cn-shenzhen.aliyuncs.com",
                        "im-byte",
                        "https://im-byte.oss-cn-shenzhen.aliyuncs.com",
                        () -> 2_000L
                )
        );

        JsonObject root = JsonParser.parseString(service.avatarUploadTarget("13800138000", "image/jpeg")).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");

        assertEquals(0, root.get("code").getAsInt());
        assertEquals("avatars/13800138000/2000.jpg", data.get("objectKey").getAsString());
        assertEquals("https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138000/2000.jpg", data.get("publicUrl").getAsString());
        assertTrue(data.get("uploadUrl").getAsString().contains("OSSAccessKeyId=test-key"));
        assertTrue(data.get("uploadUrl").getAsString().contains("Expires="));
        assertTrue(data.get("uploadUrl").getAsString().contains("Signature="));
    }

    @Test
    public void returnsFailureWhenOssCredentialsAreMissing() {
        OssUploadService service = new OssUploadService(
                new OssUploadService.Config(
                        "",
                        "",
                        "oss-cn-shenzhen.aliyuncs.com",
                        "im-byte",
                        "https://im-byte.oss-cn-shenzhen.aliyuncs.com",
                        () -> 2_000L
                )
        );

        JsonObject root = JsonParser.parseString(service.avatarUploadTarget("13800138000", "image/jpeg")).getAsJsonObject();

        assertEquals(500, root.get("code").getAsInt());
        assertEquals("OSS upload is not configured", root.get("message").getAsString());
    }

    @Test
    public void createsSignedMessageImageUploadTargets() {
        OssUploadService service = new OssUploadService(
                new OssUploadService.Config(
                        "test-key",
                        "test-secret",
                        "oss-cn-shenzhen.aliyuncs.com",
                        "im-byte",
                        "https://im-byte.oss-cn-shenzhen.aliyuncs.com",
                        () -> 2_000L
                )
        );

        JsonObject root = JsonParser.parseString(
                service.messageImageUploadTargets("13800138000", "m-1", "image/jpeg")
        ).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        JsonObject thumbnail = data.getAsJsonObject("thumbnail");
        JsonObject original = data.getAsJsonObject("original");

        assertEquals(0, root.get("code").getAsInt());
        assertEquals("m-1", data.get("messageId").getAsString());
        assertEquals(
                "chat-images/13800138000/m-1/thumb.jpg",
                thumbnail.get("objectKey").getAsString()
        );
        assertEquals(
                "chat-images/13800138000/m-1/origin.jpg",
                original.get("objectKey").getAsString()
        );
        assertTrue(thumbnail.get("uploadUrl").getAsString().contains("OSSAccessKeyId=test-key"));
        assertTrue(original.get("publicUrl").getAsString().endsWith("/origin.jpg"));
    }
}
