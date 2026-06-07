# self-desgin Profile Edit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add editable user profile behavior on Android and a backend OSS signed upload target endpoint.

**Architecture:** Keep profile persistence through `ProfileRepository.updateMe`. Add a small avatar upload API/repository for signed PUT uploads, and let `MeViewModel` coordinate edit state, optional avatar bytes, upload, and profile update.

**Tech Stack:** Kotlin, Compose, OkHttp, Android bitmap APIs, Java mock-server, Netty HTTP handler, HMAC-SHA1 signing, JUnit.

---

### Task 1: Documents

**Files:**
- Create: `docs/superpowers/specs/2026-05-24-self-desgin-profile-edit-design.md`
- Create: `docs/superpowers/plans/2026-05-24-self-desgin-profile-edit.md`
- Create: `docs/status/self-desgin-profile-edit-development-status.md`

- [x] **Step 1: Save design**
- [x] **Step 2: Save plan**
- [x] **Step 3: Update final status after verification**

### Task 2: Android Edit State

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/profile/MeViewModel.kt`
- Modify: `app/src/main/java/com/buyansong/im/profile/MeScreen.kt`
- Test: `app/src/test/java/com/buyansong/im/profile/MeViewModelTest.kt`

- [x] **Step 1: Write failing tests for edit/save/cancel**
- [x] **Step 2: Run targeted test and verify failure**
- [x] **Step 3: Implement edit state and UI controls**
- [x] **Step 4: Run targeted test and verify pass**

### Task 3: Android Avatar Upload Boundary

**Files:**
- Create: `app/src/main/java/com/buyansong/im/profile/AvatarUploadApi.kt`
- Create: `app/src/main/java/com/buyansong/im/profile/AvatarUploadJsonParser.kt`
- Create: `app/src/main/java/com/buyansong/im/profile/OkHttpAvatarUploadApi.kt`
- Create: `app/src/main/java/com/buyansong/im/profile/AvatarImageCompressor.kt`
- Test: `app/src/test/java/com/buyansong/im/profile/AvatarUploadJsonParserTest.kt`

- [x] **Step 1: Write failing parser test**
- [x] **Step 2: Run targeted test and verify failure**
- [x] **Step 3: Implement parser/API/compressor boundary**
- [x] **Step 4: Run targeted test and verify pass**

### Task 4: Mock-Server OSS Upload Target

**Files:**
- Create: `mock-server/src/main/java/com/buyansong/imserver/oss/OssUploadService.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/netty/HttpAuthHandler.java`
- Test: `mock-server/src/test/java/com/buyansong/imserver/oss/OssUploadServiceTest.java`

- [x] **Step 1: Write failing OSS target tests**
- [x] **Step 2: Run mock-server tests and verify failure**
- [x] **Step 3: Implement signed upload target generation**
- [x] **Step 4: Run mock-server tests and verify pass**

### Task 5: Verification

**Files:**
- Modify: `docs/status/self-desgin-profile-edit-development-status.md`

- [x] **Step 1: Run Android full JVM tests**
- [x] **Step 2: Run Android debug build**
- [x] **Step 3: Run mock-server full tests**
- [x] **Step 4: Update status document**

## Self-Review

- Spec coverage: edit mode, nickname update, avatar URL update, signed OSS upload target, no client-side long-lived credentials, tests, and docs are covered.
- Placeholder scan: no TBD/TODO placeholders remain.
- Type consistency: avatar upload target fields are `objectKey`, `uploadUrl`, `publicUrl`, and `expiresAt`.
