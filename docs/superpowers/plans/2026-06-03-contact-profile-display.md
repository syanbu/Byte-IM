# Contact Profile Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Insert a read-only Contact Profile page between the ByteIM Contacts tab and the single-chat screen, showing the peer's avatar, nickname, gender, and signature (everything from `UserProfile` except `userId` / `phone`) with a sticky bottom "发送消息" button that enters the chat. A follow-up increment also lets users open the same profile from single-chat and group-chat message avatars.

**Architecture:** New `SelfHostedImRoute.ContactProfile` route → `ProfileRepository.refreshProfile(accessToken, userId)` force-refresh path → new `ContactProfileViewModel` (read-only state machine, cached-first + background force-refresh) → new `ContactProfileScreen` Composable (header avatar + nickname, three data rows, sticky bottom button). Reuses `ProfileRepository.localProfile(userId)` for first-frame cache rendering and existing ByteIM UI constants (`ByteImTopBar`, `ByteImListSurface`, `ByteImColors`, `ByteImDimensions`, `AvatarImage`).

**Tech Stack:** Kotlin, Jetpack Compose, Kotlin Coroutines + `StateFlow`, JUnit 4, `kotlinx.coroutines.test` (`runTest`, `StandardTestDispatcher`, `runCurrent`). Gradle wrapper (`bash ./gradlew` in this Linux workspace).

**Reference spec:** [docs/superpowers/specs/2026-06-03-contact-profile-display-design.md](../specs/2026-06-03-contact-profile-display-design.md)

**Working directory:** `/home/buyansong/IM`

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/codex/im/SelfHostedImRoute.kt` (modify) | Add `ContactProfile` data object with `userId` path arg. |
| `app/src/main/java/com/codex/im/profile/ProfileRepository.kt` (modify) | Add `refreshProfile(accessToken, userId)` that always calls `GET /users/{userId}` and persists the result even when local cache exists. |
| `app/src/main/java/com/codex/im/contacts/ContactProfileDisplayPolicy.kt` (new) | Pure-Kotlin label constants and `genderLabel(gender)` helper. |
| `app/src/main/java/com/codex/im/contacts/ContactProfileViewModel.kt` (new) | Read-only state machine: cached-first render, background force-refresh, error handling, retry. |
| `app/src/main/java/com/codex/im/contacts/ContactProfileScreen.kt` (new) | `ContactProfileScreen(...)` + `ContactProfileHeader`, `ContactProfileDataRows`, `ContactProfileSendMessageBar`, `ContactProfileFailureBlock`. |
| `app/src/main/java/com/codex/im/MainActivity.kt` (modify) | Add `composable(SelfHostedImRoute.ContactProfile.pattern)` block. Change Contacts block's `onOpenContact` to navigate to `ContactProfile` instead of `Chat`. Route chat avatar taps to `ContactProfile` or `Me`. |
| `app/src/main/java/com/codex/im/chat/ChatScreen.kt` (modify) | Make incoming/outgoing message avatars clickable and emit the tapped user ID. |
| `app/src/main/java/com/codex/im/chat/ChatDisplayPolicy.kt` (modify) | Resolve which user ID belongs to a rendered message avatar. |
| `app/src/test/java/com/codex/im/SelfHostedImRouteTest.kt` (modify) | Add 4 tests for `ContactProfile.createRoute`. |
| `app/src/test/java/com/codex/im/profile/ProfileRepositoryTest.kt` (modify) | Add tests for `refreshProfile` forcing remote fetch and persisting fresh data. |
| `app/src/test/java/com/codex/im/contacts/ContactProfileDisplayPolicyTest.kt` (new) | Testable label constants and `genderLabel`. |
| `app/src/test/java/com/codex/im/contacts/ContactProfileViewModelTest.kt` (new) | ViewModel state machine tests (8 tests). |
| `app/src/test/java/com/codex/im/chat/ChatDisplayPolicyTest.kt` (modify) | Cover avatar-to-user-id resolution. |
| `app/src/test/java/com/codex/im/chat/ChatMessageRowLayoutTest.kt` (modify) | Guard chat avatar click wiring. |

---

## Task 1: Add `ContactProfile` route to `SelfHostedImRoute`

**Files:**
- Modify: `app/src/main/java/com/codex/im/SelfHostedImRoute.kt`
- Modify: `app/src/test/java/com/codex/im/SelfHostedImRouteTest.kt`

- [ ] **Step 1: Add failing tests for the new route**

Open `app/src/test/java/com/codex/im/SelfHostedImRouteTest.kt` and add these four tests after the existing `singleChatRouteBuildsCanonicalSingleConversationId` test (inside the class, before the closing brace):

```kotlin
@Test
fun contactProfileRouteIsUnderContactProfilePath() {
    assertEquals("contact-profile/{userId}", SelfHostedImRoute.ContactProfile.pattern)
}

@Test
fun contactProfileRouteEncodesUserIdFromCreateRoute() {
    assertEquals("contact-profile/13900113900", SelfHostedImRoute.ContactProfile.createRoute("13900113900"))
}

@Test
fun contactProfileRouteTrimsUserIdFromCreateRoute() {
    assertEquals("contact-profile/13900113900", SelfHostedImRoute.ContactProfile.createRoute(" 13900113900 "))
}

@Test
fun contactProfileRouteIgnoresBlankUserId() {
    assertNull(SelfHostedImRoute.ContactProfile.createRoute(""))
    assertNull(SelfHostedImRoute.ContactProfile.createRoute("   "))
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.SelfHostedImRouteTest --console=plain`

Expected: FAIL with compile error `Unresolved reference: ContactProfile` (the data object does not exist yet).

- [ ] **Step 3: Add the `ContactProfile` data object to `SelfHostedImRoute.kt`**

Open `app/src/main/java/com/codex/im/SelfHostedImRoute.kt`. After the existing `data object Me : SelfHostedImRoute("me")` line, add the new object before the `data object Chat` block:

```kotlin
    data object ContactProfile : SelfHostedImRoute("contact-profile/{userId}") {
        const val USER_ID_ARG = "userId"
        val pattern: String = route

        fun createRoute(userId: String): String? {
            val trimmed = userId.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            return "contact-profile/$trimmed"
        }
    }
```

- [ ] **Step 4: Run the new tests to verify they pass**

Run: `bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.SelfHostedImRouteTest --console=plain`

Expected: PASS (all 11 tests in the file pass — 7 pre-existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/im/SelfHostedImRoute.kt app/src/test/java/com/codex/im/SelfHostedImRouteTest.kt && git commit -m "feat(contacts): add ContactProfile route with userId path arg"
```

---

## Task 2: Create `ContactProfileDisplayPolicy` (TDD)

**Files:**
- Create: `app/src/main/java/com/codex/im/contacts/ContactProfileDisplayPolicy.kt`
- Create: `app/src/test/java/com/codex/im/contacts/ContactProfileDisplayPolicyTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/com/codex/im/contacts/ContactProfileDisplayPolicyTest.kt` with:

```kotlin
package com.codex.im.contacts

import com.codex.im.storage.Gender
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactProfileDisplayPolicyTest {
    @Test
    fun titleIsDetailedProfile() {
        assertEquals("详细资料", ContactProfileDisplayPolicy.title)
    }

    @Test
    fun rowLabelsAreStable() {
        assertEquals("昵称", ContactProfileDisplayPolicy.nicknameRowLabel)
        assertEquals("性别", ContactProfileDisplayPolicy.genderRowLabel)
        assertEquals("个性签名", ContactProfileDisplayPolicy.signatureRowLabel)
    }

    @Test
    fun unsetLabelsAreStable() {
        assertEquals("未设置", ContactProfileDisplayPolicy.genderUnsetLabel)
        assertEquals("未填写", ContactProfileDisplayPolicy.signatureUnsetLabel)
    }

    @Test
    fun bottomBarAndFailureLabelsAreStable() {
        assertEquals("发送消息", ContactProfileDisplayPolicy.sendMessageLabel)
        assertEquals("重试", ContactProfileDisplayPolicy.retryLabel)
        assertEquals("加载失败", ContactProfileDisplayPolicy.loadFailedMessage)
        assertEquals("登录已过期，请重新登录", ContactProfileDisplayPolicy.sessionExpiredMessage)
    }

    @Test
    fun genderLabelMapsMale() {
        assertEquals("男", ContactProfileDisplayPolicy.genderLabel(Gender.MALE))
    }

    @Test
    fun genderLabelMapsFemale() {
        assertEquals("女", ContactProfileDisplayPolicy.genderLabel(Gender.FEMALE))
    }

    @Test
    fun genderLabelMapsNullToUnset() {
        assertEquals(ContactProfileDisplayPolicy.genderUnsetLabel, ContactProfileDisplayPolicy.genderLabel(null))
    }
}
```

- [ ] **Step 2: Run the test file to verify it fails**

Run: `bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.contacts.ContactProfileDisplayPolicyTest --console=plain`

Expected: FAIL with compile error `Unresolved reference: ContactProfileDisplayPolicy`.

- [ ] **Step 3: Create the display policy file**

Create `app/src/main/java/com/codex/im/contacts/ContactProfileDisplayPolicy.kt` with:

```kotlin
package com.codex.im.contacts

import com.codex.im.storage.Gender

object ContactProfileDisplayPolicy {
    const val title = "详细资料"
    const val nicknameRowLabel = "昵称"
    const val genderRowLabel = "性别"
    const val signatureRowLabel = "个性签名"
    const val genderUnsetLabel = "未设置"
    const val signatureUnsetLabel = "未填写"
    const val sendMessageLabel = "发送消息"
    const val retryLabel = "重试"
    const val loadFailedMessage = "加载失败"
    const val sessionExpiredMessage = "登录已过期，请重新登录"

    fun genderLabel(gender: Gender?): String = when (gender) {
        Gender.MALE -> "男"
        Gender.FEMALE -> "女"
        null -> genderUnsetLabel
    }
}
```

- [ ] **Step 4: Run the test file to verify it passes**

Run: `bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.contacts.ContactProfileDisplayPolicyTest --console=plain`

Expected: PASS (7 tests, all green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/im/contacts/ContactProfileDisplayPolicy.kt app/src/test/java/com/codex/im/contacts/ContactProfileDisplayPolicyTest.kt && git commit -m "feat(contacts): add ContactProfileDisplayPolicy with testable labels"
```

---

## Task 3: Create `ContactProfileViewModel` (TDD)

**Files:**
- Modify: `app/src/main/java/com/codex/im/profile/ProfileRepository.kt`
- Modify: `app/src/test/java/com/codex/im/profile/ProfileRepositoryTest.kt`
- Create: `app/src/main/java/com/codex/im/contacts/ContactProfileViewModel.kt`
- Create: `app/src/test/java/com/codex/im/contacts/ContactProfileViewModelTest.kt`

- [ ] **Step 0: Add the force-refresh repository path (TDD)**

Open `app/src/test/java/com/codex/im/profile/ProfileRepositoryTest.kt`, add `import org.junit.Assert.assertNull`, and add tests proving:

```kotlin
@Test
fun refreshProfileFetchesRemoteEvenWhenLocalCacheExists() = runTest {
    val dao = InMemoryUserProfileDao()
    dao.upsert(UserProfile("13900139000", "13900139000", "Cached", null, 1L, 1L))
    val remoteProfile = UserProfile("13900139000", "13900139000", "Fresh", null, 2L, 2L)
    val api = FakeProfileApi(profile = remoteProfile)
    val repository = ProfileRepository(dao, api)

    val profile = repository.refreshProfile("token", "13900139000")

    assertEquals(remoteProfile, profile)
    assertEquals(remoteProfile, dao.findByUserId("13900139000"))
}

@Test
fun refreshProfileIgnoresBlankUserId() = runTest {
    val dao = InMemoryUserProfileDao()
    val repository = ProfileRepository(dao, FakeProfileApi())

    assertNull(repository.refreshProfile("token", " "))
}
```

Then modify `ProfileRepository`:

```kotlin
suspend fun refreshProfile(accessToken: String, userId: String): UserProfile? {
    val trimmedUserId = userId.trim()
    if (trimmedUserId.isEmpty()) {
        return null
    }
    return when (val result = profileApi.user(accessToken, trimmedUserId)) {
        is ProfileResult.Success -> {
            userProfileDao.upsert(result.profile)
            result.profile
        }
        is ProfileResult.Failure -> null
    }
}
```

Run: `bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.profile.ProfileRepositoryTest --console=plain`

Expected: PASS after the implementation. This method is intentionally different from `getProfile(...)`: `getProfile(...)` may return local cache without network; `refreshProfile(...)` always attempts the remote single-user endpoint.

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/com/codex/im/contacts/ContactProfileViewModelTest.kt` with:

```kotlin
package com.codex.im.contacts

import com.codex.im.auth.AuthSession
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
import com.codex.im.storage.Gender
import com.codex.im.storage.InMemoryUserProfileDao
import com.codex.im.storage.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContactProfileViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startRendersCachedProfileImmediately() = runTest {
        val fixture = Fixture(this)
        fixture.profileDao.upsert(cachedProfile("13900113900", nickname = "CachedNick"))

        fixture.viewModel.start()

        assertEquals("CachedNick", fixture.viewModel.state.value.profile?.nickname)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startRefreshesProfileInBackground() = runTest {
        val api = RecordingProfileApi().apply { nextUserResult = ProfileResult.Success(freshProfile("13900113900", nickname = "FreshNick")) }
        val fixture = Fixture(this, api = api)
        fixture.profileDao.upsert(cachedProfile("13900113900", nickname = "CachedNick"))

        fixture.viewModel.start()
        assertEquals("CachedNick", fixture.viewModel.state.value.profile?.nickname)
        runCurrent()

        assertEquals("FreshNick", fixture.viewModel.state.value.profile?.nickname)
        assertEquals(1, api.userCallCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startSurfacesErrorWhenNoCacheAndRemoteFails() = runTest {
        val api = RecordingProfileApi().apply { nextUserResult = ProfileResult.Failure("boom") }
        val fixture = Fixture(this, api = api)

        fixture.viewModel.start()
        runCurrent()

        assertNull(fixture.viewModel.state.value.profile)
        assertEquals(ContactProfileDisplayPolicy.loadFailedMessage, fixture.viewModel.state.value.errorMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startKeepsCacheAndStaysSilentWhenRemoteFails() = runTest {
        val api = RecordingProfileApi().apply { nextUserResult = ProfileResult.Failure("boom") }
        val fixture = Fixture(this, api = api)
        fixture.profileDao.upsert(cachedProfile("13900113900", nickname = "CachedNick"))

        fixture.viewModel.start()
        runCurrent()

        assertEquals("CachedNick", fixture.viewModel.state.value.profile?.nickname)
        assertNull(fixture.viewModel.state.value.errorMessage)
        assertEquals(1, api.userCallCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startHandlesExpiredSessionWhenNoCache() = runTest {
        val fixture = Fixture(this, validSession = { null })

        fixture.viewModel.start()
        runCurrent()

        assertNull(fixture.viewModel.state.value.profile)
        assertEquals(ContactProfileDisplayPolicy.sessionExpiredMessage, fixture.viewModel.state.value.errorMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startHandlesExpiredSessionWhenCacheExists() = runTest {
        val fixture = Fixture(this, validSession = { null })
        fixture.profileDao.upsert(cachedProfile("13900113900", nickname = "CachedNick"))

        fixture.viewModel.start()
        runCurrent()

        assertEquals("CachedNick", fixture.viewModel.state.value.profile?.nickname)
        assertNull(fixture.viewModel.state.value.errorMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun retryClearsErrorAndReRuns() = runTest {
        val api = RecordingProfileApi().apply { nextUserResult = ProfileResult.Failure("boom") }
        val fixture = Fixture(this, api = api)

        fixture.viewModel.start()
        runCurrent()
        assertNotNull(fixture.viewModel.state.value.errorMessage)

        api.nextUserResult = ProfileResult.Success(freshProfile("13900113900", nickname = "FreshNick"))
        fixture.viewModel.retry()
        runCurrent()

        assertNull(fixture.viewModel.state.value.errorMessage)
        assertEquals("FreshNick", fixture.viewModel.state.value.profile?.nickname)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopCancelsInFlightRefresh() = runTest {
        val gate = CompletableDeferred<ProfileResult>()
        val api = RecordingProfileApi().apply { nextUserResultDeferred = gate }
        val fixture = Fixture(this, api = api)

        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.stop()
        gate.complete(ProfileResult.Success(freshProfile("13900113900", nickname = "FreshNick")))
        runCurrent()

        // The completed result must not have been applied because stop() cancelled the coroutine.
        assertNull(fixture.viewModel.state.value.profile)
    }

    private fun cachedProfile(userId: String, nickname: String): UserProfile = UserProfile(
        userId = userId,
        phone = userId,
        nickname = nickname,
        avatarUrl = "https://example.com/$userId.jpg",
        avatarUpdatedAt = 1_000L,
        updatedAt = 1_000L,
        gender = null,
        signature = null
    )

    private fun freshProfile(userId: String, nickname: String): UserProfile = UserProfile(
        userId = userId,
        phone = userId,
        nickname = nickname,
        avatarUrl = "https://example.com/$userId.jpg",
        avatarUpdatedAt = 2_000L,
        updatedAt = 2_000L,
        gender = Gender.MALE,
        signature = "hello"
    )

    private class Fixture(
        scope: TestScope,
        api: ProfileApi = RecordingProfileApi(),
        validSession: suspend () -> AuthSession? = {
            AuthSession(
                accessToken = "token",
                refreshToken = "refresh",
                userId = "13800113800",
                username = "Syan",
                phone = "13800113800",
                nickname = "Syan",
                avatarUrl = "https://example.com/me.jpg",
                avatarUpdatedAt = 1_000L,
                profileUpdatedAt = 1_000L,
                accessExpiresAtMillis = 2_000L,
                refreshExpiresAtMillis = 3_000L
            )
        }
    ) {
        val profileDao = InMemoryUserProfileDao()
        private val profileRepository = ProfileRepository(profileDao, api)
        val viewModel = ContactProfileViewModel(
            userId = "13900113900",
            session = AuthSession(
                accessToken = "token",
                refreshToken = "refresh",
                userId = "13800113800",
                username = "Syan",
                phone = "13800113800",
                nickname = "Syan",
                avatarUrl = "https://example.com/me.jpg",
                avatarUpdatedAt = 1_000L,
                profileUpdatedAt = 1_000L,
                accessExpiresAtMillis = 2_000L,
                refreshExpiresAtMillis = 3_000L
            ),
            profileRepository = profileRepository,
            validSessionProvider = validSession,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class RecordingProfileApi(
        var nextUserResult: ProfileResult = ProfileResult.Failure("default"),
        var nextUserResultDeferred: CompletableDeferred<ProfileResult>? = null
    ) : ProfileApi {
        var userCallCount: Int = 0

        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult {
            userCallCount++
            val deferred = nextUserResultDeferred
            return if (deferred != null) deferred.await() else nextUserResult
        }

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Success(emptyList())
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }
}
```

- [ ] **Step 2: Run the test file to verify it fails**

Run: `bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.contacts.ContactProfileViewModelTest --console=plain`

Expected: FAIL with compile error `Unresolved reference: ContactProfileViewModel` / `ContactProfileUiState` (the display policy already exists from Task 2).

- [ ] **Step 3: Create the ViewModel file**

Create `app/src/main/java/com/codex/im/contacts/ContactProfileViewModel.kt` with:

```kotlin
package com.codex.im.contacts

import com.codex.im.auth.AuthSession
import com.codex.im.auth.ValidSessionProvider
import com.codex.im.profile.ProfileRepository
import com.codex.im.storage.UserProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactProfileUiState(
    val profile: UserProfile? = null,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

class ContactProfileViewModel(
    val userId: String,
    private val session: AuthSession,
    private val profileRepository: ProfileRepository,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ContactProfileUiState())
    val state: StateFlow<ContactProfileUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    fun start() {
        // Re-render local cache every time start() is called; the read is cheap
        // and lets a later entry (e.g. after a back-and-forth) pick up DAO writes
        // that happened between visits.
        val cached = profileRepository.localProfile(userId)
        mutableState.value = mutableState.value.copy(profile = cached)
        if (refreshJob?.isActive == true) return

        refreshJob = scope.launch(dispatcher) {
            val validSession = validSessionProvider()
            if (validSession == null) {
                mutableState.value = mutableState.value.copy(
                    isRefreshing = false,
                    errorMessage = if (cached == null) ContactProfileDisplayPolicy.sessionExpiredMessage else null
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(isRefreshing = true, errorMessage = null)
            val remote = profileRepository.refreshProfile(validSession.accessToken, userId)
            if (remote != null) {
                mutableState.value = mutableState.value.copy(profile = remote, isRefreshing = false)
            } else {
                mutableState.value = mutableState.value.copy(
                    isRefreshing = false,
                    errorMessage = if (cached == null) ContactProfileDisplayPolicy.loadFailedMessage else null
                )
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun retry() {
        mutableState.value = mutableState.value.copy(errorMessage = null)
        start()
    }
}
```

- [ ] **Step 4: Run the test file to verify it passes**

Run: `bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.contacts.ContactProfileViewModelTest --console=plain`

Expected: PASS (8 tests, all green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/im/profile/ProfileRepository.kt app/src/test/java/com/codex/im/profile/ProfileRepositoryTest.kt app/src/main/java/com/codex/im/contacts/ContactProfileViewModel.kt app/src/test/java/com/codex/im/contacts/ContactProfileViewModelTest.kt && git commit -m "feat(contacts): add ContactProfileViewModel with cached-first refresh"
```

---

## Task 4: Create `ContactProfileScreen` (compile-verified)

**Files:**
- Create: `app/src/main/java/com/codex/im/contacts/ContactProfileScreen.kt`

This task does not have unit tests. Compose UI in this project is verified by JVM unit tests of the underlying state machines and policies, plus a `assembleDebug` build. Visual behavior is verified by manual emulator runs documented in the spec's Acceptance Criteria.

- [ ] **Step 1: Create the Composable file**

Create `app/src/main/java/com/codex/im/contacts/ContactProfileScreen.kt` with:

```kotlin
package com.codex.im.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.im.storage.UserProfile
import com.codex.im.ui.AvatarImage
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImDimensions
import com.codex.im.ui.ByteImListSurface
import com.codex.im.ui.ByteImTopBar

@Composable
fun ContactProfileScreen(
    viewModel: ContactProfileViewModel,
    state: ContactProfileUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSendMessage: (peerUserId: String) -> Unit
) {
    LaunchedEffect(viewModel) {
        viewModel.start()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ByteImColors.AppBackground,
        bottomBar = {
            ContactProfileSendMessageBar(
                enabled = state.profile != null,
                onClick = { onSendMessage(viewModel.userId) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ByteImColors.AppBackground)
        ) {
            ByteImTopBar(title = ContactProfileDisplayPolicy.title, onBack = onBack)
            Spacer(modifier = Modifier.height(12.dp))
            ContactProfileBody(
                state = state,
                onRetry = viewModel::retry
            )
        }
    }
}

@Composable
private fun ContactProfileBody(
    state: ContactProfileUiState,
    onRetry: () -> Unit
) {
    val profile = state.profile
    if (profile != null) {
        ContactProfileContent(profile = profile)
        return
    }
    if (state.errorMessage != null) {
        ContactProfileFailureBlock(
            message = state.errorMessage,
            onRetry = onRetry
        )
        return
    }
    // No cache, no error yet: show a centered progress indicator.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = ByteImColors.PrimaryGreen,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun ContactProfileContent(profile: UserProfile) {
    Column(modifier = Modifier.fillMaxSize()) {
        ContactProfileHeader(profile = profile)
        Spacer(modifier = Modifier.height(12.dp))
        ByteImListSurface {
            ContactProfileDataRows(profile = profile)
        }
    }
}

@Composable
private fun ContactProfileHeader(profile: UserProfile) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ByteImColors.AppBackground)
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AvatarImage(
            avatarUrl = profile.avatarUrl,
            displayName = profile.nickname,
            modifier = Modifier.size(ByteImDimensions.ProfileAvatarSize)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = profile.nickname,
            style = MaterialTheme.typography.titleLarge,
            color = ByteImColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ContactProfileDataRows(profile: UserProfile) {
    ContactProfileValueRow(
        label = ContactProfileDisplayPolicy.nicknameRowLabel,
        value = profile.nickname
    )
    HorizontalDivider(color = ByteImColors.Divider)
    ContactProfileValueRow(
        label = ContactProfileDisplayPolicy.genderRowLabel,
        value = ContactProfileDisplayPolicy.genderLabel(profile.gender)
    )
    HorizontalDivider(color = ByteImColors.Divider)
    ContactProfileValueRow(
        label = ContactProfileDisplayPolicy.signatureRowLabel,
        value = profile.signature?.takeIf { it.isNotBlank() }
            ?: ContactProfileDisplayPolicy.signatureUnsetLabel,
        maxLines = 2
    )
}

@Composable
private fun ContactProfileValueRow(
    label: String,
    value: String,
    maxLines: Int = 1
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (maxLines > 1) 80.dp else ByteImDimensions.ListItemHeight)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ByteImColors.TextSecondary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextPrimary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ContactProfileFailureBlock(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text(
                text = ContactProfileDisplayPolicy.retryLabel,
                color = ByteImColors.PrimaryGreen
            )
        }
    }
}

@Composable
private fun ContactProfileSendMessageBar(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ByteImColors.Surface)
    ) {
        HorizontalDivider(color = ByteImColors.Divider)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    PaddingValues(
                        horizontal = ByteImDimensions.EdgePadding,
                        vertical = 12.dp
                    )
                )
        ) {
            Button(
                enabled = enabled,
                onClick = onClick,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ByteImColors.PrimaryGreen,
                    contentColor = Color.White,
                    disabledContainerColor = ByteImColors.PrimaryGreen.copy(alpha = 0.4f),
                    disabledContentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = ContactProfileDisplayPolicy.sendMessageLabel,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `bash ./gradlew :app:assembleDebug --console=plain`

Expected: BUILD SUCCESSFUL. The `ContactProfileScreen` file references `ContactProfileViewModel`, `ContactProfileUiState`, `ContactProfileDisplayPolicy` (all created in Tasks 1-3), and existing `AvatarImage`, `ByteImTopBar`, `ByteImListSurface`, `ByteImColors`, `ByteImDimensions`. No new import surprises — every import is to a symbol that already exists in the codebase or was created in earlier tasks.

If the build fails with "Unresolved reference" for `ByteImColors.PrimaryGreen`, `ByteImDimensions.ProfileAvatarSize`, `AvatarImage`, `ByteImTopBar`, or `ByteImListSurface`, those are existing symbols defined in `app/src/main/java/com/codex/im/ui/ByteImUi.kt` and `app/src/main/java/com/codex/im/ui/AvatarImage.kt`. Re-check the import list.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/codex/im/contacts/ContactProfileScreen.kt && git commit -m "feat(contacts): add ContactProfileScreen with read-only profile and sticky send-message bar"
```

---

## Task 5: Wire `ContactProfileScreen` into `MainActivity`

**Files:**
- Modify: `app/src/main/java/com/codex/im/MainActivity.kt`

This task does not have unit tests. The wiring is verified by `bash ./gradlew :app:assembleDebug` (compile) and `bash ./gradlew :app:testDebugUnitTest` (no regression on existing tests).

- [ ] **Step 1: Add the import for `ContactProfileScreen` and `ContactProfileViewModel`**

Open `app/src/main/java/com/codex/im/MainActivity.kt`. In the `import com.codex.im.contacts.ContactListScreen` block, add two new imports (alphabetical order):

```kotlin
import com.codex.im.contacts.ContactListScreen
import com.codex.im.contacts.ContactListViewModel
import com.codex.im.contacts.ContactProfileScreen
import com.codex.im.contacts.ContactProfileViewModel
import com.codex.im.contacts.DemoContactResolver
```

- [ ] **Step 2: Change `onOpenContact` in the `Contacts` composable to navigate to `ContactProfile`**

In the same file, find the existing `composable(SelfHostedImRoute.Contacts.route) { ... }` block. Inside it, find this lambda:

```kotlin
                    onOpenContact = { peerUserId ->
                        SelfHostedImRoute.Chat.createSingleRoute(session.userId, peerUserId)?.let(navController::navigateToChat)
                    }
```

Replace it with:

```kotlin
                    onOpenContact = { peerUserId ->
                        SelfHostedImRoute.ContactProfile.createRoute(peerUserId)?.let { navController.navigate(it) }
                    }
```

- [ ] **Step 3: Add the new `composable` block for `ContactProfile`**

Find the `composable(SelfHostedImRoute.Me.route) { ... }` block in the `NavHost`. Add the new `composable` block AFTER it and BEFORE the existing `composable(route = SelfHostedImRoute.Chat.pattern) { chatBackStackEntry -> ... }` block. Insert:

```kotlin
            composable(route = SelfHostedImRoute.ContactProfile.pattern) { entry ->
                val userId = entry.arguments
                    ?.getString(SelfHostedImRoute.ContactProfile.USER_ID_ARG)
                    .orEmpty()
                if (userId.isBlank()) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                val contactProfileViewModel = remember(session.userId, userId) {
                    ContactProfileViewModel(
                        userId = userId,
                        session = session,
                        profileRepository = profileRepository,
                        validSessionProvider = validSessionProvider
                    )
                }
                val contactProfileState by contactProfileViewModel.state.collectAsState()
                ContactProfileScreen(
                    viewModel = contactProfileViewModel,
                    state = contactProfileState,
                    onBack = { navController.popBackStack() },
                    onSendMessage = { peerUserId ->
                        SelfHostedImRoute.Chat.createSingleRoute(session.userId, peerUserId)
                            ?.let { navController.navigateToChat(it) }
                    }
                )
            }
```

- [ ] **Step 4: Verify the build**

Run: `bash ./gradlew :app:assembleDebug --console=plain`

Expected: BUILD SUCCESSFUL.

If the build fails with `Unresolved reference: ContactProfileScreen` or `Unresolved reference: ContactProfileViewModel`, re-check the imports added in Step 1 — they must be on their own lines and point to the new files in `com.codex.im.contacts`.

- [ ] **Step 5: Re-run existing tests to confirm no regression**

Run: `bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.SelfHostedImRouteTest --tests com.codex.im.contacts.ContactListViewModelTest --tests com.codex.im.BottomNavigationSpecTest --tests com.codex.im.TopLevelBackPolicyTest --tests com.codex.im.ChatBackPolicyTest --console=plain`

Expected: PASS. The change in Step 2 only altered the `onOpenContact` lambda body; the lambda signature is unchanged, so `ContactListScreen` accepts it without recompilation issues. The change in Step 3 added a new `composable` block without removing any existing one, so the chat back policy and top-level back policy tests stay green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/codex/im/MainActivity.kt && git commit -m "feat(contacts): wire ContactProfileScreen into MainActivity NavHost"
```

---

## Task 6: Full regression and final verification

**Files:** none modified.

> Current workspace note: as of the latest review, `:app:testDebugUnitTest` compilation is already blocked by unrelated `ChatDisplayPolicyTest.kt` unresolved references to `ChatComposerAction` / `composerAction`. If that remains true when this plan is executed, record the blocker explicitly and use `:app:compileDebugKotlin` plus any test subset that can compile after the blocker is fixed. Do not treat that pre-existing compile failure as a ContactProfile regression.

- [ ] **Step 1: Run the full Android JVM unit test suite**

Run: `bash ./gradlew :app:testDebugUnitTest --console=plain`

Expected after any pre-existing test-source compile blockers are fixed: BUILD SUCCESSFUL with all unit tests passing — including the 4 new `SelfHostedImRouteTest` cases, 2 new `ProfileRepositoryTest` cases for `refreshProfile`, 7 new `ContactProfileDisplayPolicyTest` cases, 8 new `ContactProfileViewModelTest` cases, plus all pre-existing tests in `ContactListViewModelTest`, `MeViewModelTest`, `ProfileRepositoryTest`, `ChatBackPolicyTest`, `TopLevelBackPolicyTest`, `BottomNavigationSpecTest`, `MessagesTabUnreadBadge*Test`, `UserProfileDaoContractTest`, `ProfileJsonParserTest`, `MeBackPolicyTest`, `MeDisplayPolicyTest`, `AvatarUploadJsonParserTest`, and the conversation/group/connection/message Dao contract tests.

If any test fails, fix the underlying code (NOT the test) and re-run. Common failure patterns to watch for:

- A test that was passing pre-Task-5 starts failing: the most likely cause is the `onOpenContact` change in Task 5 Step 2. Verify the lambda signature is unchanged (it accepts a `String`, returns `Unit`).
- A test for `ContactListViewModel` starts failing: the only thing that changed in `ContactListViewModel` is the action of the `LaunchedEffect(state.navigationTargetPeerId)` consumer in `ContactListScreen`. The `MainActivity` change routes that consumer to `ContactProfile` instead of `Chat`, but the `ContactListViewModel` itself is unchanged.

- [ ] **Step 2: Run the debug build one more time**

Run: `bash ./gradlew :app:assembleDebug --console=plain`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: (Optional) Manually verify on emulator**

This step is out of scope for the test suite but matches the spec's Acceptance Criteria. The user can install the debug APK on an emulator and verify:

1. Login with `13800113800` (a phone number already registered in the mock server).
2. Open the Contacts tab.
3. Tap the `13900113900` contact row.
4. The Contact Profile page appears within 1 frame, showing the cached avatar, nickname, "未设置" gender, "未填写" signature.
5. Tap top-bar Back from the profile page — returns to the Contacts tab.
6. Re-open the profile from the Contacts tab and tap "发送消息" — the chat screen opens with the single-chat conversation.
7. Tap system Back from the chat — follows the current chat Back policy and returns to the conversation list, not to the profile page.
8. Re-open the profile from the Contacts tab. The page renders cached data immediately, then silently refreshes in the background.
9. Kill the app, restart, login again, and re-tap the contact. The page should still render instantly when the cache from `user_profiles` is present, then refresh remotely.
10. Repeat with a fresh install (no local cache): the page shows a centered spinner for ~200ms, then the profile appears.

If any of these steps fail, file a follow-up issue rather than patching in this slice.

- [ ] **Step 4: (Optional) Update `DEVELOPMENT_STATUS.md`**

If the user wants the status file to reflect this new self-design work, append a row to the "Recent Self-Design Work" table in `docs/DEVELOPMENT_STATUS.md`:

```markdown
| Contact profile display | Insert a read-only profile page between the Contacts tab and single chat; cached-first render, background refresh, sticky bottom "发送消息" button. | Implemented | [contact-profile-display-development-status.md](status/contact-profile-display-development-status.md) |
```

And create a new status file `docs/status/contact-profile-display-development-status.md` mirroring the structure of `docs/status/self-desgin-profile-chat-ui-development-status.md` (Scope, Confirmed Decisions, Current Implementation State, Implemented Android Changes, Verification Log tables, Bug Fix Log). This is optional polish; the implementation is complete without it.

- [ ] **Step 5: Final commit (if Step 4 was performed)**

```bash
git add docs/DEVELOPMENT_STATUS.md docs/status/contact-profile-display-development-status.md && git commit -m "docs: add contact profile display status entry"
```

---

## Self-Review Notes (for the planner, not the executor)

This plan was self-reviewed against the spec before saving:

1. **Spec coverage:** Each Acceptance Criterion in the spec maps to a task:
   - New `contact-profile/{userId}` route in `SelfHostedImRoute` → Task 1.
   - Tapping a contact row navigates to the new page → Task 5 Step 2.
   - Page shows avatar / nickname / gender / signature → Task 4 (`ContactProfileContent` + `ContactProfileDataRows`).
   - Page does NOT show `userId` / `phone` → Task 4 (no ID row in `ContactProfileDataRows`).
   - Page uses existing ByteIM UI constants → Task 4 (all imports from `com.codex.im.ui.*`).
   - Sticky bottom "发送消息" button, enabled only when profile loaded → Task 4 (`ContactProfileSendMessageBar` + `enabled = state.profile != null`).
   - Tapping "发送消息" navigates to single-chat → Task 5 Step 3 (`onSendMessage` callback).
   - Tapping top-bar Back returns to the previous route (Contacts for the Contacts entry point; chat for chat-avatar entry points) → Task 5 Step 3 (`onBack = { navController.popBackStack() }`).
   - Cache-first render, background refresh, no loading screen on cache hit → Task 3 (`start()` synchronous cache read) + Task 4 (`ContactProfileBody` only shows progress when `profile == null && errorMessage == null`).
   - All listed unit tests pass → Task 6 Step 1.
   - `./gradlew :app:assembleDebug` passes → Task 6 Step 2.
   - No mock-server change required → Confirmed by reading `UserRecord.java`, `UserStore.java`, `AuthService.java` during spec writing.
   - No protocol / API / SQLite / other-ViewModel modification → Confirmed by the file structure table at the top of this plan.

2. **Placeholder scan:** No "TBD", "TODO", "implement later", "fill in details", or "add appropriate error handling" appears in any step. Every step shows complete code or the exact command and expected output.

3. **Type consistency:**
   - `ContactProfileUiState` is defined in Task 3 and used in Tasks 3, 4, 5.
   - `ContactProfileViewModel.userId` is `val` (not `private val`) so `ContactProfileScreen` can read it for the `onSendMessage` callback in Task 4. Task 3 defines it `val`, Task 4 reads `viewModel.userId`, Task 5 passes it in.
   - `ContactProfileDisplayPolicy.genderLabel(gender)` is defined in Task 2 and called in Task 4.
   - `SelfHostedImRoute.ContactProfile.createRoute(userId)` is defined in Task 1 and called in Task 5.
   - `ContactProfileViewModel.retry()` is defined in Task 3 and wired to the "重试" button in Task 4.
   - `ContactProfileViewModel.stop()` is defined in Task 3 and called from `DisposableEffect.onDispose` in Task 4.
   - The `Fixture` class in `ContactProfileViewModelTest` (Task 3) uses `RecordingProfileApi` which exposes `userCallCount` and `nextUserResultDeferred` — both used in the test methods.

4. **Plan can be executed in order:** Each task is a self-contained commit. The executor can stop after any task and the codebase still builds (Tasks 1-3 add data/policy/ViewModel and tests without any UI wire-up; Task 4 adds a Composable that is not yet referenced; Task 5 wires the Composable into `MainActivity`).
