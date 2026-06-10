package com.buyansong.imserver.auth;

import com.buyansong.imserver.ImServerLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AuthService {
    private static final Pattern MAINLAND_CHINA_PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private final UserStore userStore;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;

    public AuthService() {
        this(new UserStore(Path.of("data", "mock-im-users.sqlite")), new PasswordHasher(new SecureSaltGenerator()), TokenService.defaultService());
    }

    public AuthService(UserStore userStore, PasswordHasher passwordHasher) {
        this(userStore, passwordHasher, TokenService.defaultService());
    }

    public AuthService(UserStore userStore, PasswordHasher passwordHasher, TokenService tokenService) {
        this.userStore = userStore;
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
    }

    public String login(String phone, String password) {
        if (!isValidPhone(phone)) {
            return failure(400, "Invalid mainland China phone number");
        }
        Optional<UserRecord> user = userStore.findByPhone(phone);
        if (user.isEmpty()) {
            return failure(404, "User not registered");
        }
        UserRecord record = user.get();
        if (!passwordHasher.verify(password, record.salt(), record.passwordHash())) {
            return failure(401, "Invalid phone or password");
        }
        return success(phone);
    }

    public String register(String phone, String password) {
        if (!isValidPhone(phone)) {
            return failure(400, "Invalid mainland China phone number");
        }
        if (password == null || password.length() < 6) {
            return failure(400, "Password must be at least 6 characters");
        }
        PasswordHasher.HashedPassword hashedPassword = passwordHasher.hash(password);
        long now = System.currentTimeMillis();
        boolean inserted = userStore.insert(new UserRecord(
                phone,
                hashedPassword.salt(),
                hashedPassword.hash(),
                phone,
                null,
                null,
                0L,
                now,
                now,
                null,
                null,
                0L
        ));
        if (!inserted) {
            return failure(409, "User already registered");
        }
        return success(phone);
    }

    public String success(String phone) {
        TokenService.IssuedToken accessToken = tokenService.issue(phone);
        TokenService.IssuedRefreshToken refreshToken = tokenService.issueRefreshToken();
        long now = tokenService.currentTimeMillis();
        userStore.saveRefreshToken(phone, refreshToken.hash(), refreshToken.expiresAtMillis(), now);
        return success(phone, accessToken, refreshToken.token(), refreshToken.expiresAtMillis());
    }

    public String refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return failure(401, "Refresh token expired or revoked");
        }
        String tokenHash = tokenService.hashRefreshToken(refreshToken);
        Optional<RefreshTokenRecord> record = userStore.findActiveRefreshToken(tokenHash, tokenService.currentTimeMillis());
        if (record.isEmpty()) {
            return failure(401, "Refresh token expired or revoked");
        }
        long now = tokenService.currentTimeMillis();
        TokenService.IssuedToken accessToken = tokenService.issue(record.get().phone());
        TokenService.IssuedRefreshToken nextRefreshToken = tokenService.issueRefreshToken();
        userStore.rotateRefreshToken(
                tokenHash,
                record.get().phone(),
                nextRefreshToken.hash(),
                nextRefreshToken.expiresAtMillis(),
                now
        );
        return success(
                record.get().phone(),
                accessToken,
                nextRefreshToken.token(),
                nextRefreshToken.expiresAtMillis()
        );
    }

    public String logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            userStore.revokeRefreshToken(tokenService.hashRefreshToken(refreshToken), tokenService.currentTimeMillis());
        }
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        return root.toString();
    }

    public Optional<String> verifyAccessToken(String accessToken) {
        return tokenService.verify(accessToken);
    }

    public String profile(String phone) {
        Optional<UserRecord> record = userStore.findByPhone(phone);
        if (record.isEmpty()) {
            return failure(404, "User not found");
        }
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", profileJson(record.get()));
        return root.toString();
    }

    public String profiles(List<String> phones) {
        JsonArray profiles = new JsonArray();
        for (UserRecord record : userStore.findByPhones(phones)) {
            profiles.add(profileJson(record));
        }
        JsonObject data = new JsonObject();
        data.add("profiles", profiles);
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", data);
        return root.toString();
    }

    public String updateProfile(String phone, String nickname, String avatarUrl, String avatarObjectKey, String gender, String signature) {
        ImServerLogger.log(
                "[IM] PROFILE_UPDATE_REQUEST userId=%s nickname=%s avatarUrl=%s avatarObjectKey=%s gender=%s signature=%s",
                phone,
                nickname,
                avatarUrl,
                avatarObjectKey,
                gender,
                signature
        );
        Optional<UserRecord> record = userStore.updateProfile(
                phone,
                nickname,
                blankToNull(avatarUrl),
                blankToNull(avatarObjectKey),
                blankToNull(gender),
                blankToNull(signature),
                tokenService.currentTimeMillis()
        );
        if (record.isEmpty()) {
            ImServerLogger.log("[IM] PROFILE_UPDATE_FAILED userId=%s reason=user-not-found", phone);
            return failure(404, "User not found");
        }
        UserRecord updated = record.get();
        ImServerLogger.log(
                "[IM] PROFILE_UPDATED userId=%s nickname=%s avatarUrl=%s avatarObjectKey=%s gender=%s signature=%s updatedAt=%d",
                updated.phone(),
                updated.nickname(),
                updated.avatarUrl(),
                updated.avatarObjectKey(),
                updated.gender(),
                updated.signature(),
                updated.updatedAt()
        );
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", profileJson(updated));
        return root.toString();
    }

    private String success(String phone, TokenService.IssuedToken accessToken, String refreshToken, long refreshExpiresAt) {
        UserRecord record = userStore.findByPhone(phone).orElse(new UserRecord(
                phone,
                "",
                "",
                phone,
                null,
                null,
                0L,
                tokenService.currentTimeMillis(),
                tokenService.currentTimeMillis(),
                null,
                null,
                0L
        ));
        JsonObject data = new JsonObject();
        data.addProperty("accessToken", accessToken.token());
        data.addProperty("accessExpiresAt", accessToken.expiresAtMillis());
        data.addProperty("refreshToken", refreshToken);
        data.addProperty("refreshExpiresAt", refreshExpiresAt);
        data.addProperty("token", accessToken.token());
        data.addProperty("expiresAt", accessToken.expiresAtMillis());
        data.addProperty("userId", phone);
        data.addProperty("username", phone);
        addProfileFields(data, record);

        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", data);
        return root.toString();
    }

    public String failure(String message) {
        return failure(400, message);
    }

    public String failure(int code, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("code", code);
        root.addProperty("message", message);
        return root.toString();
    }

    private boolean isValidPhone(String phone) {
        return phone != null && MAINLAND_CHINA_PHONE.matcher(phone).matches();
    }

    private JsonObject profileJson(UserRecord record) {
        JsonObject data = new JsonObject();
        addProfileFields(data, record);
        return data;
    }

    private void addProfileFields(JsonObject data, UserRecord record) {
        data.addProperty("userId", record.phone());
        data.addProperty("phone", record.phone());
        data.addProperty("nickname", record.nickname());
        if (record.avatarUrl() == null) {
            data.add("avatarUrl", com.google.gson.JsonNull.INSTANCE);
        } else {
            data.addProperty("avatarUrl", record.avatarUrl());
        }
        if (record.avatarObjectKey() == null) {
            data.add("avatarObjectKey", com.google.gson.JsonNull.INSTANCE);
        } else {
            data.addProperty("avatarObjectKey", record.avatarObjectKey());
        }
        data.addProperty("avatarUpdatedAt", record.avatarUpdatedAt());
        data.addProperty("profileUpdatedAt", record.updatedAt());
        data.addProperty("updatedAt", record.updatedAt());
        data.addProperty("profileVersion", record.profileVersion());
        if (record.gender() == null) {
            data.add("gender", com.google.gson.JsonNull.INSTANCE);
        } else {
            data.addProperty("gender", record.gender());
        }
        if (record.signature() == null) {
            data.add("signature", com.google.gson.JsonNull.INSTANCE);
        } else {
            data.addProperty("signature", record.signature());
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
