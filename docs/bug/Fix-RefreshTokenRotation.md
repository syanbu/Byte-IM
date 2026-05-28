# Fix: Refresh Must Issue A New Access Token And Refresh Token Pair

## Status

- Status: Completed
- Completed on: 2026-05-28
- Branch: current working branch

## Problem

`docs/status/B1-auth.md` 中描述的是双令牌鉴权，但实际 mock-server 的 `/refresh` 行为只签发新的 `accessToken`，把旧 `refreshToken` 原样返回给客户端。

这会导致两个问题：

1. 刷新后的令牌并不是一整对新令牌，和双 token 轮换预期不一致。
2. 旧 `refreshToken` 会持续有效，服务端没有把 refresh 当成一次真正的轮换。

## Root Cause

根因在 mock-server 的 `AuthService.refresh()`：

- 服务端先校验旧 `refreshToken` 是否有效。
- 校验通过后只调用 `tokenService.issue(...)` 生成新的 `accessToken`。
- 返回响应时继续把旧 `refreshToken` 和旧的 `refreshExpiresAt` 放回响应体。
- SQLite 中也没有执行“旧 refresh token 作废 + 新 refresh token 入库”的旋转操作。

## Fix Summary

本次修复将 refresh 流程改成真正的“双令牌轮换”：

1. `AuthService.refresh()` 现在在刷新时同时签发新的 `accessToken` 和新的 `refreshToken`。
2. `UserStore` 新增 refresh token 轮换写入，使用单次数据库事务完成：
   - 将当前 refresh token 标记为 revoked
   - 写入新的 refresh token hash、过期时间、签发时间
3. refresh 响应现在返回新的 `refreshToken` 和新的 `refreshExpiresAt`。
4. Android 侧保留现有持久化逻辑，并补充测试确认客户端会保存刷新后返回的新 `refreshToken`。

## Result

修复后：

- `/refresh` 返回的是新的 `accessToken + refreshToken` 成对令牌。
- 旧 `refreshToken` 在成功 refresh 后立即失效，不能再次用于 refresh。
- Android 本地保存的 refresh token 会跟随服务端轮换结果一起更新。

## Verification

2026-05-28 已完成以下验证：

- Mock server:
  - `mvn -q -Dtest=AuthServiceTest test`
  - 验证 refresh 会返回新的 refresh token、新的 refresh 过期时间，并拒绝再次使用旧 refresh token。
- Android:
  - `.\gradlew.bat :app:testDebugUnitTest --console=plain`
  - 验证 `OkHttpAuthApi` 能解析新的 refresh token，`AuthRepository` 会把刷新后返回的新 token 对写回本地存储。
