package com.codex.imserver.oss;

import com.google.gson.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.LongSupplier;

public final class OssUploadService {
    private static final long DEFAULT_EXPIRES_MILLIS = 15 * 60 * 1000L;

    private final Config config;

    public OssUploadService() {
        this(Config.fromEnvironment());
    }

    public OssUploadService(Config config) {
        this.config = config;
    }

    public String avatarUploadTarget(String userId, String contentType) {
        if (!config.isConfigured()) {
            return failure(500, "OSS upload is not configured");
        }
        String normalizedContentType = contentType == null || contentType.isBlank() ? "image/jpeg" : contentType;
        long now = config.nowMillis().getAsLong();
        long expiresAtMillis = now + DEFAULT_EXPIRES_MILLIS;
        long expiresSeconds = expiresAtMillis / 1000L;
        String objectKey = "avatars/" + safePathSegment(userId) + "/" + now + ".jpg";
        String publicUrl = config.publicBaseUrl().replaceAll("/+$", "") + "/" + objectKey;
        String resource = "/" + config.bucket() + "/" + objectKey;
        String stringToSign = "PUT\n\n" + normalizedContentType + "\n" + expiresSeconds + "\n" + resource;
        String signature = hmacSha1(config.accessKeySecret(), stringToSign);
        String uploadUrl = publicUrl
                + "?OSSAccessKeyId=" + urlEncode(config.accessKeyId())
                + "&Expires=" + expiresSeconds
                + "&Signature=" + urlEncode(signature);

        JsonObject data = new JsonObject();
        data.addProperty("objectKey", objectKey);
        data.addProperty("uploadUrl", uploadUrl);
        data.addProperty("publicUrl", publicUrl);
        data.addProperty("expiresAt", expiresAtMillis);

        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", data);
        return root.toString();
    }

    public String messageImageUploadTargets(String userId, String messageId, String contentType) {
        if (!config.isConfigured()) {
            return failure(500, "OSS upload is not configured");
        }
        String normalizedContentType = contentType == null || contentType.isBlank() ? "image/jpeg" : contentType;
        long now = config.nowMillis().getAsLong();
        long expiresAtMillis = now + DEFAULT_EXPIRES_MILLIS;
        String basePath = "chat-images/" + safePathSegment(userId) + "/" + safePathSegment(messageId);

        JsonObject data = new JsonObject();
        data.addProperty("messageId", messageId);
        data.add("thumbnail", signedTarget(basePath + "/thumb.jpg", normalizedContentType, expiresAtMillis));
        data.add("original", signedTarget(basePath + "/origin.jpg", normalizedContentType, expiresAtMillis));
        data.addProperty("expiresAt", expiresAtMillis);

        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", data);
        return root.toString();
    }

    private String failure(int code, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("code", code);
        root.addProperty("message", message);
        return root.toString();
    }

    private JsonObject signedTarget(String objectKey, String contentType, long expiresAtMillis) {
        long expiresSeconds = expiresAtMillis / 1000L;
        String publicUrl = config.publicBaseUrl().replaceAll("/+$", "") + "/" + objectKey;
        String resource = "/" + config.bucket() + "/" + objectKey;
        String stringToSign = "PUT\n\n" + contentType + "\n" + expiresSeconds + "\n" + resource;
        String signature = hmacSha1(config.accessKeySecret(), stringToSign);
        String uploadUrl = publicUrl
                + "?OSSAccessKeyId=" + urlEncode(config.accessKeyId())
                + "&Expires=" + expiresSeconds
                + "&Signature=" + urlEncode(signature);

        JsonObject target = new JsonObject();
        target.addProperty("objectKey", objectKey);
        target.addProperty("uploadUrl", uploadUrl);
        target.addProperty("publicUrl", publicUrl);
        target.addProperty("expiresAt", expiresAtMillis);
        return target;
    }

    private String safePathSegment(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
    }

    private String hmacSha1(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to sign OSS upload URL", error);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Config(
            String accessKeyId,
            String accessKeySecret,
            String endpoint,
            String bucket,
            String publicBaseUrl,
            LongSupplier nowMillis
    ) {
        public static Config fromEnvironment() {
            String endpoint = envOrDefault("ALIYUN_OSS_ENDPOINT", "oss-cn-shenzhen.aliyuncs.com");
            String bucket = envOrDefault("ALIYUN_OSS_BUCKET", "im-byte");
            return new Config(
                    System.getenv("ALIYUN_OSS_ACCESS_KEY_ID"),
                    System.getenv("ALIYUN_OSS_ACCESS_KEY_SECRET"),
                    endpoint,
                    bucket,
                    envOrDefault("ALIYUN_OSS_PUBLIC_BASE_URL", "https://" + bucket + "." + endpoint),
                    System::currentTimeMillis
            );
        }

        private static String envOrDefault(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }

        private boolean isConfigured() {
            return notBlank(accessKeyId)
                    && notBlank(accessKeySecret)
                    && notBlank(endpoint)
                    && notBlank(bucket)
                    && notBlank(publicBaseUrl);
        }

        private boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
