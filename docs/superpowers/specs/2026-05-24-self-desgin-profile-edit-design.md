# self-desgin Profile Edit and Avatar Upload Design

## Goal

Add the next profile slice: users can edit their nickname and avatar from the `Me` page, persist the updated profile to the backend, and refresh the local profile cache.

## Scope

This slice builds:

- Edit mode on `Me`.
- Nickname draft/edit/save/cancel behavior.
- Android boundary for selecting and compressing an avatar image to 1 MB or less.
- Backend boundary for issuing an OSS signed upload target.
- Android boundary for uploading compressed bytes to the signed upload URL.
- Existing `PUT /users/me` remains the final profile persistence endpoint.

## OSS Decision

The app must not contain long-lived OSS credentials. The backend owns `AccessKey ID` and `AccessKey Secret`, reads them from environment variables, and returns a short-lived signed PUT URL for one avatar object path.

Runtime environment variables:

```text
ALIYUN_OSS_ACCESS_KEY_ID
ALIYUN_OSS_ACCESS_KEY_SECRET
ALIYUN_OSS_ENDPOINT=oss-cn-shenzhen.aliyuncs.com
ALIYUN_OSS_BUCKET=im-byte
ALIYUN_OSS_PUBLIC_BASE_URL=https://im-byte.oss-cn-shenzhen.aliyuncs.com
```

Signed avatar object path:

```text
avatars/{userId}/{timestamp}.jpg
```

## Android Flow

```text
Me -> Edit
-> user changes nickname
-> optional: user selects avatar image
-> app compresses selected image to JPEG <= 1 MB
-> app requests /oss/avatar/upload-target
-> app PUTs compressed bytes to uploadUrl
-> app calls PUT /users/me with nickname + avatarUrl + avatarObjectKey
-> app updates user_profiles and Me UI
```

The edit form does not expose a manual Avatar URL field. Avatar changes must come from a phone image selection.

## Backend API

```text
POST /oss/avatar/upload-target
Authorization: Bearer <accessToken>
Body: {"contentType":"image/jpeg"}
```

Success response:

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "objectKey": "avatars/13800138000/1710000000000.jpg",
    "uploadUrl": "https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138000/1710000000000.jpg?...",
    "publicUrl": "https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138000/1710000000000.jpg",
    "expiresAt": 1710000900000
  }
}
```

If OSS env variables are missing, return a normal JSON failure instead of crashing the server.

## Testing Strategy

Android JVM tests:

- `MeViewModel` enters edit mode with current profile values.
- Cancel leaves the saved profile unchanged.
- Save trims nickname, calls repository, updates state, and leaves edit mode.
- Avatar upload JSON parser parses upload target.
- Avatar upload repository requests target and uploads bytes before profile update when selected avatar bytes exist.

Mock-server JUnit tests:

- OSS upload target response includes object key, public URL, signed upload URL, and expiration.
- Missing OSS configuration returns a failure response.

## Status

Approved by user after the profile display slice was manually tested.
