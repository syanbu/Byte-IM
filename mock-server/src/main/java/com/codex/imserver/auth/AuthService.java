package com.codex.imserver.auth;

import com.google.gson.JsonObject;

import java.nio.file.Path;
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
        boolean inserted = userStore.insert(new UserRecord(
                phone,
                hashedPassword.salt(),
                hashedPassword.hash(),
                System.currentTimeMillis()
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
        TokenService.IssuedToken accessToken = tokenService.issue(record.get().phone());
        return success(record.get().phone(), accessToken, refreshToken, record.get().expiresAt());
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

    private String success(String phone, TokenService.IssuedToken accessToken, String refreshToken, long refreshExpiresAt) {
        JsonObject data = new JsonObject();
        data.addProperty("accessToken", accessToken.token());
        data.addProperty("accessExpiresAt", accessToken.expiresAtMillis());
        data.addProperty("refreshToken", refreshToken);
        data.addProperty("refreshExpiresAt", refreshExpiresAt);
        data.addProperty("token", accessToken.token());
        data.addProperty("expiresAt", accessToken.expiresAtMillis());
        data.addProperty("userId", phone);
        data.addProperty("username", phone);

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
}
