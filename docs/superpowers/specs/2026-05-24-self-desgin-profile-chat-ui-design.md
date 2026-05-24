# self-desgin IM Profile, Avatar, and Chat UI Design

## Current Branch

`IM-b1TOb5-fix-chat`

## Goal

Redesign the authenticated Android IM experience around a two-tab mobile layout:

- `Messages`: conversation list only.
- `Me`: current user profile and logout.

The chat screen should display the peer nickname in the top bar and use avatars in conversation/chat surfaces. Login and registration flow stay unchanged, except new accounts receive default profile values.

## Confirmed Product Decisions

- User ID is the phone number.
- Default nickname is the phone number at registration time.
- Users can later update nickname and avatar.
- Avatar files are stored in Alibaba Cloud OSS.
- OSS bucket or avatar directory uses public-read for the first implementation.
- Long-lived OSS `AccessKey ID` and `AccessKey Secret` stay on the backend only.
- Android can upload with backend-issued short-lived credentials later; the first app UI/data slice stores and displays avatar URLs.

## OSS Configuration

```text
Region: cn-shenzhen
Endpoint: oss-cn-shenzhen.aliyuncs.com
Bucket: im-byte
Bucket domain: im-byte.oss-cn-shenzhen.aliyuncs.com
CNAME domain: im-byte.cn-shenzhen.taihangpfm.cn
```

Canonical avatar URL pattern for this project:

```text
https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/{userId}/{timestamp}.jpg
```

Server-side storage should keep both:

```text
avatarObjectKey = avatars/13800113800/1710000000000.jpg
avatarUrl = https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800113800/1710000000000.jpg
```

## Android Information Architecture

Authenticated root:

```text
Bottom tabs:
  Messages
  Me
```

`Messages` tab:

- Shows conversation list.
- Does not show logout.
- Row displays peer avatar, nickname, last message, last message time, unread count.

`Me` tab:

- Shows current user's avatar, nickname, and phone ID.
- Provides one wide logout button below the profile area.

`Chat` route:

- Is opened from `Messages`.
- Top bar shows back control and peer nickname.
- Message rows use sender avatar.
- Phone numbers are not used as primary chat display labels when a nickname exists.

## Data Model

Android local model:

```text
UserProfile
- userId: String
- phone: String
- nickname: String
- avatarUrl: String?
- avatarUpdatedAt: Long
- updatedAt: Long
```

SQLite table:

```sql
CREATE TABLE user_profiles (
  user_id TEXT PRIMARY KEY,
  phone TEXT NOT NULL,
  nickname TEXT NOT NULL,
  avatar_url TEXT,
  avatar_updated_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
```

Display fallback:

```text
displayName = user_profiles.nickname -> conversations.peer_name -> peerId
avatar = user_profiles.avatar_url -> default placeholder
```

## Backend Model

Extend the mock-server `users` table with:

```text
nickname TEXT NOT NULL DEFAULT phone
avatar_url TEXT
avatar_object_key TEXT
avatar_updated_at BIGINT NOT NULL DEFAULT 0
updated_at BIGINT NOT NULL
```

For existing databases, migration should add missing columns and backfill nickname with phone.

## Backend API

First implementation:

```text
GET /users/me
GET /users/{userId}
PUT /users/me
POST /users/batch
```

`PUT /users/me` accepts:

```json
{
  "nickname": "Syan",
  "avatarUrl": "https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800113800/1710000000000.jpg",
  "avatarObjectKey": "avatars/13800113800/1710000000000.jpg"
}
```

`POST /users/batch` accepts:

```json
{
  "userIds": ["13800113800", "13900113900"]
}
```

and returns a `profiles` array.

Authentication for `/users/me`, `/users/batch`, and `PUT /users/me` should use the existing bearer access token. For the mock-server token format, the phone can be extracted from the token subject after validation.

## Avatar Upload Boundary

This design records the OSS decision, but the first development slice does not need to implement native image picking or real OSS upload. The intended later flow is:

```text
APP compresses avatar <= 1MB
-> APP requests backend OSS upload credential
-> backend uses RAM AccessKey ID/Secret to issue a short-lived credential
-> APP uploads to public-read avatar path
-> APP sends avatarUrl/avatarObjectKey to backend profile API
-> backend stores profile
-> APP updates local user_profiles
```

## Testing Strategy

Android JVM tests:

- Auth parser accepts profile fields in login/register responses.
- Profile DAO upserts and reads local profiles.
- Conversation list display name and avatar prefer `UserProfile`.
- Chat VM exposes peer nickname/avatar and current user profile.
- Me VM exposes current user profile and update state.
- Navigation route set includes bottom tabs and chat route.

Mock-server JUnit tests:

- Registration defaults nickname to phone.
- Auth response includes profile fields.
- Profile update changes nickname and avatar URL.
- Batch profile lookup returns known users.

## Status

Design approved by user and ready for implementation.
