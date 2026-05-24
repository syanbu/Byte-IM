# self-desgin Profile Edit Development Status

## Branch

`IM-b1TOb5-fix-chat`

## Scope

Add user-facing profile modification:

- Edit nickname on `Me`.
- Edit or upload avatar URL from `Me`.
- Compress selected avatar image to 1 MB or less before upload.
- Request backend OSS signed upload target.
- Upload selected avatar bytes with a short-lived signed URL.
- Persist updated profile with `PUT /users/me`.

## Current State

- Design document: created.
- Implementation plan: created.
- Android implementation: completed for this slice.
- Mock-server implementation: completed for this slice.
- Verification: passed.

## Implemented Android Changes

- `Me` page now has an `Edit Profile` action.
- Edit mode supports nickname editing.
- Edit mode supports choosing an image from the device via Android content picker.
- Selected image is compressed to JPEG under 1 MB before upload.
- Added avatar upload API boundary:
  - request backend signed upload target
  - PUT compressed bytes to signed URL
  - save resulting `avatarUrl` and `avatarObjectKey` through `PUT /users/me`
- `MeViewModel` now supports edit, cancel, save, saving state, and upload error state.
- Removed the manual Avatar URL input; avatars can only be selected from phone images in the UI.

## Implemented Mock-Server Changes

- Added `OssUploadService`.
- Added `POST /oss/avatar/upload-target`.
- Backend reads OSS runtime config from environment variables:
  - `ALIYUN_OSS_ACCESS_KEY_ID`
  - `ALIYUN_OSS_ACCESS_KEY_SECRET`
  - `ALIYUN_OSS_ENDPOINT`
  - `ALIYUN_OSS_BUCKET`
  - `ALIYUN_OSS_PUBLIC_BASE_URL`
- Upload target response includes:
  - `objectKey`
  - `uploadUrl`
  - `publicUrl`
  - `expiresAt`
- Missing OSS credentials return JSON failure instead of crashing.
- Profile updates now print backend logs:
  - `[IM] PROFILE_UPDATE_REQUEST ...`
  - `[IM] PROFILE_UPDATED ...`
  - `[IM] PROFILE_UPDATE_FAILED ...`

## Verification Log

| Area | Command | Result |
|---|---|---|
| Android RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.profile.MeViewModelTest --tests com.codex.im.profile.AvatarUploadJsonParserTest --console=plain` | Failed as expected before implementation because edit state and avatar upload types did not exist. |
| Mock-server RED | `mvn -q test -Dtest=OssUploadServiceTest` | Failed as expected before implementation because `OssUploadService` did not exist. |
| Android targeted GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.profile.MeViewModelTest --tests com.codex.im.profile.AvatarUploadJsonParserTest --console=plain` | Passed. |
| Mock-server targeted GREEN | `mvn -q test -Dtest=OssUploadServiceTest` | Passed. |
| Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Mock-server full tests | `mvn -q test` in `mock-server` | Passed. |
| Nickname login regression RED | `mvn -q test -Dtest=AuthServiceTest#loginKeepsUsernameAsPhoneAfterNicknameChanges` | Failed before fix because login response returned nickname in `username`. |
| Nickname login regression GREEN | `mvn -q test -Dtest=AuthServiceTest#loginKeepsUsernameAsPhoneAfterNicknameChanges` | Passed after backend keeps `username` as phone and returns nickname separately. |
| Post-regression Android targeted | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.auth.AuthRepositoryTest --tests com.codex.im.auth.AuthJsonParserTest --tests com.codex.im.profile.MeViewModelTest --console=plain` | Passed. |
| Post-regression Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Post-regression Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Post-regression mock-server full tests | `mvn -q test` in `mock-server` | Passed. |

## Bug Fix Log

### Nickname Change Broke Login

Observed behavior:

- After changing account nickname, login showed `Invalid authentication response`.

Root cause:

- Backend login response used `username = record.nickname()`.
- Android still treats `username` as an identity/session compatibility field and requires it to be a mainland China phone number.
- Once nickname became non-phone text, `AuthRepository` rejected the otherwise valid login response.

Fix:

- Backend login/refresh responses keep `username` as the phone number.
- Profile display name remains in the separate `nickname` field.
- Added backend and Android regression coverage for non-phone nicknames.

## Remaining Risks

- Real OSS upload requires backend environment variables for AccessKey ID/Secret.
- Public-read bucket/directory configuration must be applied in Alibaba Cloud console.
- Manual emulator verification of gallery picking and OSS PUT upload was not run in this pass.
- OSS signed URL implementation signs PUT requests for `image/jpeg`; if the bucket requires extra headers such as explicit ACL headers, signing must be extended to include those headers.
