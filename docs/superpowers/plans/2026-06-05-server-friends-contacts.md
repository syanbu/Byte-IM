# Server Friends Contacts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move contacts from fixed Android demo IDs to server-backed mutual friendships and add a seed tool for 500 mock accounts.

**Architecture:** The mock server owns two data sets: registered user profiles in `users` and mutual friend edges in a new `friendships` table. Android first fetches friend user IDs from `GET /friends/me`, then reuses existing profile batch refresh for display data.

**Tech Stack:** Java 17, Netty, SQLite JDBC, JUnit 4, Kotlin, OkHttp, coroutines.

---

### Task 1: Server Friend Store

**Files:**
- Create: `mock-server/src/main/java/com/buyansong/imserver/friend/FriendStore.java`
- Test: `mock-server/src/test/java/com/buyansong/imserver/friend/FriendStoreTest.java`

- [x] Write a failing test proving mutual friendship inserts `A -> B` and `B -> A`, excludes self-links, and deduplicates repeated inserts.
- [x] Implement `FriendStore` with `CREATE TABLE IF NOT EXISTS friendships`, `addMutualFriendship`, and `friendsOf`.
- [x] Run `mvn -q -Dtest=FriendStoreTest test` in `mock-server`.

### Task 2: Friend HTTP API

**Files:**
- Create: `mock-server/src/main/java/com/buyansong/imserver/friend/FriendService.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/netty/HttpAuthHandler.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/MockImServer.java`
- Test: `mock-server/src/test/java/com/buyansong/imserver/friend/FriendServiceTest.java`

- [x] Write a failing service test for `GET /friends/me` JSON shape.
- [x] Add authenticated `GET /friends/me` handling that returns `{ "friendUserIds": [...] }`.
- [x] Wire `FriendService` into the server startup path.
- [x] Run targeted mock-server tests.

### Task 3: Seed Tool

**Files:**
- Create: `mock-server/src/main/java/com/buyansong/imserver/tools/MockFriendSeeder.java`
- Test: `mock-server/src/test/java/com/buyansong/imserver/tools/MockFriendSeederTest.java`

- [x] Write a failing test proving 500 users are registered through `AuthService.register()` and the first 3 accounts each have 497 friends.
- [x] Implement idempotent seeding for `15000000000` through `15000000499`, password `123456`, and mutual friendships.
- [x] Support running with `mvn -q exec:java -Dexec.mainClass=com.buyansong.imserver.tools.MockFriendSeeder`.

### Task 4: Android Remote Contacts

**Files:**
- Create: `app/src/main/java/com/buyansong/im/contacts/ContactApi.kt`
- Create: `app/src/main/java/com/buyansong/im/contacts/OkHttpContactApi.kt`
- Create: `app/src/main/java/com/buyansong/im/contacts/ContactJsonParser.kt`
- Create: `app/src/main/java/com/buyansong/im/contacts/ContactRepository.kt`
- Modify: `app/src/main/java/com/buyansong/im/contacts/ContactListViewModel.kt`
- Modify: `app/src/main/java/com/buyansong/im/group/GroupCreateViewModel.kt`
- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt`
- Test: contact and group create ViewModel tests.

- [x] Write failing Android tests for server-provided friend IDs.
- [x] Replace synchronous fixed resolver injection with a suspend remote repository call.
- [x] Keep profile refresh behavior unchanged after friend IDs arrive.
- [x] Run targeted Android JVM tests.

### Task 5: Status Docs

**Files:**
- Modify: `docs/status/mock-server.md`

- [x] Document `friendships` SQLite persistence, `GET /friends/me`, and the seed command.
- [x] Add verification commands and results after tests pass.
