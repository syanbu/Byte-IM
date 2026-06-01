# ByteIM UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the Android Compose client UI toward the `docs/PRD/pages/` prototypes and rename user-visible branding to `ByteIM` without changing app behavior.

**Architecture:** Add a small ByteIM UI token/component layer under `com.codex.im.ui`, then apply it to the existing screens. Keep current routes, ViewModels, repositories, protocol code, persistence, and Back semantics unchanged.

**Tech Stack:** Android Kotlin, Jetpack Compose Material3, Gradle, existing JVM unit tests.

---

## File Structure

- Create `app/src/main/java/com/codex/im/ui/ByteImUi.kt`: shared colors, dimensions, top bars, badges, list surface helpers, chat bubble styling helpers.
- Create `app/src/test/java/com/codex/im/ui/ByteImUiTokensTest.kt`: pure token tests for stable color and dimension constants.
- Modify `app/src/main/java/com/codex/im/app/AppInfo.kt`: user-visible app name.
- Modify `app/src/test/java/com/codex/im/app/AppInfoTest.kt`: brand assertion.
- Modify `app/src/main/res/values/strings.xml`: Android app label.
- Modify `docs/PRD/IM_PRODUCT_REQUIREMENTS_CN_EN.md`: user-facing product name references.
- Modify `app/src/main/java/com/codex/im/MainActivity.kt`: app background, ByteIM Material colors, bottom navigation appearance, fallback skeleton text.
- Modify `app/src/main/java/com/codex/im/auth/LoginScreen.kt`: login/register visual redesign.
- Modify `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt`: Messages top bar, conversation rows, empty/connection styling.
- Modify `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt`: Contacts top bar and list rows.
- Modify `app/src/main/java/com/codex/im/group/GroupCreateScreen.kt`: group contact selection page styling.
- Modify `app/src/main/java/com/codex/im/profile/MeScreen.kt`: Me home, profile detail, name editor styling.
- Modify `app/src/main/java/com/codex/im/chat/ChatScreen.kt`: chat top bar, message area, composer, action menu, text bubbles, status indicators.
- Modify `app/src/main/java/com/codex/im/chat/ChatImageBubble.kt`: image bubble loading/failure overlay styling.
- Modify `app/src/main/java/com/codex/im/chat/ChatImagePreviewScreen.kt`: black full-screen preview background with ByteIM-colored loading and white error text.
- Modify focused existing tests where user-visible labels change:
  - `app/src/test/java/com/codex/im/app/AppInfoTest.kt`
  - Keep `BottomNavigationSpecTest` labels unless product confirms Chinese labels later; prototypes use English top-level labels.

---

### Task 1: ByteIM UI Tokens and Shared Components

**Files:**
- Create: `app/src/main/java/com/codex/im/ui/ByteImUi.kt`
- Create: `app/src/test/java/com/codex/im/ui/ByteImUiTokensTest.kt`

- [ ] **Step 1: Write token tests**

Create `app/src/test/java/com/codex/im/ui/ByteImUiTokensTest.kt`:

```kotlin
package com.codex.im.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteImUiTokensTest {
    @Test
    fun colors_matchPrototypeDirection() {
        assertEquals(Color(0xFFEDEDED), ByteImColors.AppBackground)
        assertEquals(Color(0xFF95EC69), ByteImColors.SelfBubble)
        assertEquals(Color(0xFFFFFFFF), ByteImColors.PeerBubble)
        assertEquals(Color(0xFF07C160), ByteImColors.PrimaryGreen)
        assertEquals(Color(0xFFFA5151), ByteImColors.BadgeRed)
    }

    @Test
    fun dimensions_matchPrototypeScale() {
        assertEquals(56, ByteImDimensions.TopBarHeight.value.toInt())
        assertEquals(72, ByteImDimensions.ListItemHeight.value.toInt())
        assertEquals(50, ByteImDimensions.ListAvatarSize.value.toInt())
        assertEquals(40, ByteImDimensions.ChatAvatarSize.value.toInt())
        assertEquals(16, ByteImDimensions.EdgePadding.value.toInt())
    }
}
```

- [ ] **Step 2: Run token tests and verify failure**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.ui.ByteImUiTokensTest
```

Expected: FAIL because `ByteImColors` and `ByteImDimensions` do not exist.

- [ ] **Step 3: Add ByteIM UI layer**

Create `app/src/main/java/com/codex/im/ui/ByteImUi.kt`:

```kotlin
package com.codex.im.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.im.R

object ByteImColors {
    val AppBackground = Color(0xFFEDEDED)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceLow = Color(0xFFFCF9F8)
    val Divider = Color(0xFFEEEEEE)
    val PrimaryGreen = Color(0xFF07C160)
    val SelfBubble = Color(0xFF95EC69)
    val PeerBubble = Color(0xFFFFFFFF)
    val BadgeRed = Color(0xFFFA5151)
    val TextPrimary = Color(0xFF1C1B1B)
    val TextSecondary = Color(0xFF808080)
    val InverseSurface = Color(0xFF313030)
}

object ByteImDimensions {
    val TopBarHeight = 56.dp
    val BottomBarHeight = 64.dp
    val ListItemHeight = 72.dp
    val ListAvatarSize = 50.dp
    val ChatAvatarSize = 40.dp
    val ProfileAvatarSize = 64.dp
    val EdgePadding = 16.dp
    val Gutter = 12.dp
    val BubbleHorizontalPadding = 12.dp
    val BubbleVerticalPadding = 8.dp
}

object ByteImShapes {
    val Avatar = RoundedCornerShape(8.dp)
    val Bubble = RoundedCornerShape(8.dp)
    val BubbleLarge = RoundedCornerShape(12.dp)
    val Notice = RoundedCornerShape(999.dp)
    val ActionMenu = RoundedCornerShape(8.dp)
}

@Composable
fun ByteImTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ByteImDimensions.TopBarHeight)
            .background(ByteImColors.Surface)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_left),
                    contentDescription = "Back",
                    tint = ByteImColors.PrimaryGreen
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = ByteImColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            action()
        }
    }
}

@Composable
fun ByteImUnreadBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Badge(
        containerColor = ByteImColors.BadgeRed,
        contentColor = Color.White,
        modifier = modifier
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ByteImListSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ByteImColors.Surface)
    ) {
        content()
    }
}

@Composable
fun ByteImSystemNotice(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = ByteImColors.TextSecondary,
            modifier = Modifier
                .background(Color(0xFFE5E2E1), ByteImShapes.Notice)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

fun Modifier.byteImListClick(onClick: () -> Unit): Modifier {
    return this.clickable(onClick = onClick)
}

fun byteImBubbleShape(outgoing: Boolean): Shape {
    return if (outgoing) {
        RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp)
    } else {
        RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp)
    }
}

fun byteImBubbleColor(outgoing: Boolean): Color {
    return if (outgoing) ByteImColors.SelfBubble else ByteImColors.PeerBubble
}
```

- [ ] **Step 4: Run token tests and app unit tests**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.ui.ByteImUiTokensTest
bash ./gradlew :app:testDebugUnitTest
```

Expected: token tests PASS; full app unit tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/im/ui/ByteImUi.kt app/src/test/java/com/codex/im/ui/ByteImUiTokensTest.kt
git commit -m "Add ByteIM UI tokens"
```

---

### Task 2: Brand Rename and App Theme Shell

**Files:**
- Modify: `app/src/main/java/com/codex/im/app/AppInfo.kt`
- Modify: `app/src/test/java/com/codex/im/app/AppInfoTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `docs/PRD/IM_PRODUCT_REQUIREMENTS_CN_EN.md`
- Modify: `app/src/main/java/com/codex/im/MainActivity.kt`

- [ ] **Step 1: Update the failing brand test**

Modify `app/src/test/java/com/codex/im/app/AppInfoTest.kt`:

```kotlin
@Test
fun exposesAppInfo() {
    assertEquals("ByteIM", AppInfo.name)
    assertEquals("0.1.0", AppInfo.versionName)
    assertTrue(AppInfo.completedMilestones.contains("phase-0-project-skeleton"))
}
```

- [ ] **Step 2: Run brand test and verify failure**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.app.AppInfoTest
```

Expected: FAIL with expected `ByteIM` but actual `SelfHostedIM`.

- [ ] **Step 3: Rename user-visible app brand**

Modify `app/src/main/java/com/codex/im/app/AppInfo.kt`:

```kotlin
package com.codex.im.app

object AppInfo {
    const val name: String = "ByteIM"
    const val versionName: String = "0.1.0"

    val completedMilestones: Set<String> = setOf("phase-0-project-skeleton")
}
```

Modify `app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">ByteIM</string>
</resources>
```

In `docs/PRD/IM_PRODUCT_REQUIREMENTS_CN_EN.md`, replace user-facing product title and body references:

```text
SelfHostedIM -> ByteIM
```

Keep internal Kotlin names such as `SelfHostedImRoute` unchanged.

- [ ] **Step 4: Add ByteIM color scheme to the app shell**

Modify `app/src/main/java/com/codex/im/MainActivity.kt` imports:

```kotlin
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.codex.im.ui.ByteImColors
```

Add near `SelfHostedImApp`:

```kotlin
private val ByteImColorScheme = lightColorScheme(
    primary = ByteImColors.PrimaryGreen,
    onPrimary = Color.White,
    background = ByteImColors.AppBackground,
    onBackground = ByteImColors.TextPrimary,
    surface = ByteImColors.Surface,
    onSurface = ByteImColors.TextPrimary,
    surfaceVariant = Color(0xFFE5E2E1),
    onSurfaceVariant = ByteImColors.TextSecondary,
    outlineVariant = ByteImColors.Divider,
    error = ByteImColors.BadgeRed
)
```

Change the `MaterialTheme` wrapper:

```kotlin
MaterialTheme(colorScheme = ByteImColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
            .systemBarsPadding()
    ) {
        // existing authentication routing remains here
    }
}
```

Keep the existing `SelfHostedImApp` function name.

- [ ] **Step 5: Run brand checks**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.app.AppInfoTest
rg -n "SelfHostedIM" app/src/main docs/PRD/IM_PRODUCT_REQUIREMENTS_CN_EN.md
```

Expected: test PASS; `rg` returns no user-visible `SelfHostedIM` references in the checked paths.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/codex/im/app/AppInfo.kt app/src/test/java/com/codex/im/app/AppInfoTest.kt app/src/main/res/values/strings.xml docs/PRD/IM_PRODUCT_REQUIREMENTS_CN_EN.md app/src/main/java/com/codex/im/MainActivity.kt
git commit -m "Rename app brand to ByteIM"
```

---

### Task 3: Main Scaffold and Bottom Navigation Styling

**Files:**
- Modify: `app/src/main/java/com/codex/im/MainActivity.kt`

- [ ] **Step 1: Keep navigation behavior unchanged**

Before editing, run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.TopLevelBackPolicyTest --tests com.codex.im.BottomNavigationSpecTest --tests com.codex.im.MessagesTabUnreadBadgePolicyTest
```

Expected: PASS before visual edits.

- [ ] **Step 2: Style Scaffold and NavigationBar**

Modify imports in `MainActivity.kt`:

```kotlin
import androidx.compose.material3.NavigationBarDefaults
import com.codex.im.ui.ByteImDimensions
```

Change `Scaffold` container and bottom bar:

```kotlin
Scaffold(
    containerColor = ByteImColors.AppBackground,
    bottomBar = {
        if (BottomNavigationSpec.topLevelItems.any { it.route == currentRoute }) {
            NavigationBar(
                containerColor = ByteImColors.Surface,
                tonalElevation = 0.dp,
                modifier = Modifier.height(ByteImDimensions.BottomBarHeight)
            ) {
                BottomNavigationSpec.topLevelItems.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigateToTopLevelTab(tab.route)
                        },
                        label = { Text(tab.label) },
                        icon = {
                            BottomNavigationIcon(
                                spec = tab,
                                unreadCount = if (tab.route == BottomNavigationSpec.messages.route) unreadMessagesCount else 0
                            )
                        }
                    )
                }
            }
        }
    }
) { innerPadding ->
    NavHost(
        navController = navController,
        startDestination = SelfHostedImRoute.Conversations.route,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
        modifier = Modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
            .padding(innerPadding)
    ) {
        composable(SelfHostedImRoute.Conversations.route) {
            // Keep the current Conversations route body exactly as it is in AuthenticatedImNavHost.
        }
        composable(SelfHostedImRoute.Contacts.route) {
            // Keep the current Contacts route body exactly as it is in AuthenticatedImNavHost.
        }
        composable(SelfHostedImRoute.GroupCreate.route) {
            // Keep the current GroupCreate route body exactly as it is in AuthenticatedImNavHost.
        }
        composable(SelfHostedImRoute.Me.route) {
            // Keep the current Me route body exactly as it is in AuthenticatedImNavHost.
        }
        composable(route = SelfHostedImRoute.Chat.pattern) {
            // Keep the current Chat route body exactly as it is in AuthenticatedImNavHost.
        }
    }
}
```

If `NavigationBarDefaults` is not used after editing, remove that import before committing.

- [ ] **Step 3: Style bottom unread badge**

Modify `BottomNavigationIcon` badge:

```kotlin
BadgedBox(
    badge = {
        Badge(
            containerColor = ByteImColors.BadgeRed,
            contentColor = Color.White
        ) {
            Text(text = badgeText)
        }
    }
) {
    iconContent()
}
```

- [ ] **Step 4: Run navigation tests**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.TopLevelBackPolicyTest --tests com.codex.im.BottomNavigationSpecTest --tests com.codex.im.MessagesTabUnreadBadgePolicyTest
```

Expected: PASS. These tests confirm the visual edit did not change routes, labels, or unread badge text policy.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/im/MainActivity.kt
git commit -m "Style ByteIM app scaffold"
```

---

### Task 4: Login and Register Visual Redesign

**Files:**
- Modify: `app/src/main/java/com/codex/im/auth/LoginScreen.kt`

- [ ] **Step 1: Run auth UI-adjacent tests before editing**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.auth.RegistrationInputValidatorTest --tests com.codex.im.auth.LoginViewModelTest
```

Expected: PASS.

- [ ] **Step 2: Update imports**

In `LoginScreen.kt`, add:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.codex.im.app.AppInfo
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImDimensions
```

Remove unused imports after the edit.

- [ ] **Step 3: Replace the top-level layout with prototype-style form**

In `LoginScreen`, replace the current top-level `Column` body with:

```kotlin
Box(
    modifier = modifier
        .fillMaxSize()
        .background(ByteImColors.AppBackground)
        .imePadding()
        .verticalScroll(scrollState)
        .padding(24.dp),
    contentAlignment = Alignment.Center
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .background(ByteImColors.Surface, RoundedCornerShape(12.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = AppInfo.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = ByteImColors.TextPrimary
        )
        Text(
            text = if (isRegisterMode) {
                "Create an account with a mainland China phone number"
            } else {
                "Sign in with a mainland China phone number"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ByteImColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(24.dp))
        // keep the existing fields, buttons, loading, error, and session text inside this Column
    }
}
```

Move the existing fields and actions into the inner `Column`. Do not change `onLogin`, `onRegister`, `RegistrationInputValidator`, or local state behavior.

- [ ] **Step 4: Apply ByteIM input and button colors**

For each `OutlinedTextField`, add:

```kotlin
colors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ByteImColors.PrimaryGreen,
    focusedLabelColor = ByteImColors.PrimaryGreen,
    cursorColor = ByteImColors.PrimaryGreen
)
```

For the primary `Button`, add:

```kotlin
colors = ButtonDefaults.buttonColors(
    containerColor = ByteImColors.PrimaryGreen,
    contentColor = Color.White
)
```

For secondary `OutlinedButton`, keep the existing behavior and let the Material border render; do not add new navigation.

- [ ] **Step 5: Run auth tests and compile**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.auth.RegistrationInputValidatorTest --tests com.codex.im.auth.LoginViewModelTest
bash ./gradlew :app:compileDebugKotlin
```

Expected: tests PASS; compile PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/codex/im/auth/LoginScreen.kt
git commit -m "Redesign ByteIM login screen"
```

---

### Task 5: Messages, Contacts, and Group Create Lists

**Files:**
- Modify: `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt`
- Modify: `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt`
- Modify: `app/src/main/java/com/codex/im/group/GroupCreateScreen.kt`

- [ ] **Step 1: Run list-related tests before editing**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.conversation.MessageTopBarTitlePolicyTest --tests com.codex.im.conversation.ConversationListPreviewPolicyTest --tests com.codex.im.group.GroupCreateNavigationPolicyTest
```

Expected: PASS.

- [ ] **Step 2: Redesign Messages screen shell**

In `ConversationListScreen.kt`, import:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImDimensions
import com.codex.im.ui.ByteImListSurface
import com.codex.im.ui.ByteImSystemNotice
import com.codex.im.ui.ByteImTopBar
import com.codex.im.ui.ByteImUnreadBadge
```

Change the outer `Column`:

```kotlin
Column(
    modifier = modifier
        .fillMaxSize()
        .background(ByteImColors.AppBackground)
) {
    MessagesTopBar(
        unreadCount = unreadCount,
        onStartGroupChat = onStartGroupChat
    )
    ConversationConnectionStatusPolicy.visibleLabel(state.connectionStatus)?.let { statusLabel ->
        ByteImSystemNotice(
            text = statusLabel,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
    ByteImListSurface(modifier = Modifier.weight(1f)) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.items, key = { it.conversationId }) { item ->
                ConversationRow(
                    item = item,
                    onClick = {
                        viewModel.openConversation(if (item.isGroup) item.conversationId else item.peerId)
                    }
                )
                HorizontalDivider(color = ByteImColors.Divider)
            }
        }
    }
}
```

- [ ] **Step 3: Redesign Messages top bar**

Replace `MessagesTopBar` content with:

```kotlin
ByteImTopBar(
    title = MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount),
    action = {
        Box {
            IconButton(
                onClick = { menuExpanded = !menuExpanded },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(id = MessageTopBarActionPolicy.addIconResId),
                    contentDescription = "More actions",
                    tint = ByteImColors.PrimaryGreen,
                    modifier = Modifier.size(28.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(text = "发起群聊") },
                    onClick = {
                        menuExpanded = false
                        onStartGroupChat()
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = "添加朋友") },
                    onClick = { menuExpanded = false }
                )
            }
        }
    }
)
```

- [ ] **Step 4: Redesign conversation row**

Replace the row modifier and text styling in `ConversationRow`:

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .height(ByteImDimensions.ListItemHeight)
        .clickable(enabled = onClick != null) { onClick?.invoke() }
        .padding(horizontal = ByteImDimensions.EdgePadding),
    horizontalArrangement = Arrangement.spacedBy(ByteImDimensions.Gutter),
    verticalAlignment = Alignment.CenterVertically
) {
    AvatarImage(
        avatarUrl = item.peerAvatarUrl,
        displayName = item.peerName,
        isGroup = item.isGroup,
        modifier = Modifier.size(ByteImDimensions.ListAvatarSize)
    )
    Column(modifier = Modifier.weight(1f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.peerName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = ByteImColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = item.lastMessageTime.displayTime(),
                style = MaterialTheme.typography.labelSmall,
                color = ByteImColors.TextSecondary
            )
        }
        Text(
            text = ConversationListPreviewPolicy.previewAnnotatedText(
                item = item,
                mentionColor = ByteImColors.BadgeRed
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = ByteImColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (item.unreadCount > 0) {
        ByteImUnreadBadge(text = item.unreadCount.coerceAtMost(99).let { if (item.unreadCount >= 100) "99+" else it.toString() })
    }
}
```

- [ ] **Step 5: Redesign Contacts screen**

In `ContactListScreen.kt`, use:

```kotlin
Column(
    modifier = modifier
        .fillMaxSize()
        .background(ByteImColors.AppBackground)
) {
    ByteImTopBar(title = "Contacts")
    ByteImListSurface(modifier = Modifier.weight(1f)) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.items, key = { it.userId }) { item ->
                ContactRow(
                    item = item,
                    onClick = { viewModel.openContact(item.userId) }
                )
                HorizontalDivider(color = ByteImColors.Divider)
            }
        }
    }
}
```

Set each contact row height and avatar size:

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .height(ByteImDimensions.ListItemHeight)
        .clickable(onClick = onClick)
        .padding(horizontal = ByteImDimensions.EdgePadding),
    horizontalArrangement = Arrangement.spacedBy(ByteImDimensions.Gutter),
    verticalAlignment = Alignment.CenterVertically
) {
    AvatarImage(
        avatarUrl = item.avatarUrl,
        displayName = item.displayName,
        modifier = Modifier.size(ByteImDimensions.ListAvatarSize)
    )
    Column(modifier = Modifier.weight(1f)) {
        Text(text = item.displayName, style = MaterialTheme.typography.titleMedium, color = ByteImColors.TextPrimary)
        Text(text = "ID: ${item.userId}", style = MaterialTheme.typography.bodyMedium, color = ByteImColors.TextSecondary)
    }
}
```

- [ ] **Step 6: Redesign Group Create screen**

In `GroupCreateScreen.kt`, replace the page shell with:

```kotlin
Column(
    modifier = modifier
        .fillMaxSize()
        .background(ByteImColors.AppBackground)
) {
    ByteImTopBar(
        title = "发起群聊",
        onBack = onBack,
        action = {
            Button(
                enabled = state.canCreate && !state.isCreating,
                onClick = { viewModel.createGroup() },
                colors = ButtonDefaults.buttonColors(containerColor = ByteImColors.PrimaryGreen)
            ) {
                Text(text = if (state.isCreating) "创建中" else "创建")
            }
        }
    )
    if (state.errorMessage != null) {
        Text(
            text = state.errorMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(ByteImDimensions.EdgePadding)
        )
    }
    ByteImListSurface(modifier = Modifier.weight(1f)) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.contacts, key = { it.userId }) { item ->
                GroupCreateContactRow(
                    item = item,
                    onClick = { viewModel.toggleContact(item.userId) }
                )
                HorizontalDivider(color = ByteImColors.Divider)
            }
        }
    }
}
```

Set `GroupCreateContactRow` row height to `ByteImDimensions.ListItemHeight`, avatar to `ByteImDimensions.ListAvatarSize`, and secondary text to `ID: ${item.userId}`.

- [ ] **Step 7: Run list tests and compile**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.conversation.MessageTopBarTitlePolicyTest --tests com.codex.im.conversation.ConversationListPreviewPolicyTest --tests com.codex.im.group.GroupCreateNavigationPolicyTest
bash ./gradlew :app:compileDebugKotlin
```

Expected: tests PASS; compile PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt app/src/main/java/com/codex/im/contacts/ContactListScreen.kt app/src/main/java/com/codex/im/group/GroupCreateScreen.kt
git commit -m "Redesign ByteIM list screens"
```

---

### Task 6: Me, Profile Detail, and Name Editor Styling

**Files:**
- Modify: `app/src/main/java/com/codex/im/profile/MeScreen.kt`

- [ ] **Step 1: Run profile behavior tests before editing**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.profile.MeBackPolicyTest --tests com.codex.im.profile.MeDisplayPolicyTest --tests com.codex.im.profile.MeViewModelTest
```

Expected: PASS.

- [ ] **Step 2: Add ByteIM imports**

In `MeScreen.kt`, add:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImDimensions
import com.codex.im.ui.ByteImListSurface
import com.codex.im.ui.ByteImTopBar
```

- [ ] **Step 3: Redesign Me home**

Replace the `MeHomeScreen` outer layout with:

```kotlin
Column(
    modifier = modifier
        .fillMaxSize()
        .background(ByteImColors.AppBackground)
) {
    ByteImTopBar(title = "Me")
    ProfileSummaryRow(
        profile = profile,
        onClick = onOpenProfile
    )
    Spacer(modifier = Modifier.height(8.dp))
    ByteImListSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ByteImDimensions.ListItemHeight)
                .padding(horizontal = ByteImDimensions.EdgePadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Services", style = MaterialTheme.typography.titleMedium, color = ByteImColors.TextPrimary)
            ChevronRightIcon()
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = onLogout,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ByteImDimensions.EdgePadding),
        colors = ButtonDefaults.buttonColors(containerColor = ByteImColors.Surface, contentColor = ByteImColors.BadgeRed)
    ) {
        Text("Logout")
    }
    state.errorMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(ByteImDimensions.EdgePadding)
        )
    }
}
```

This `Services` row is visual-only and must not navigate because no service feature exists.

- [ ] **Step 4: Redesign profile summary row**

Replace `ProfileSummaryRow` layout:

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .background(ByteImColors.Surface)
        .clickable(onClick = onClick)
        .padding(horizontal = ByteImDimensions.EdgePadding, vertical = 18.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    AvatarImage(
        avatarUrl = profile?.avatarUrl,
        displayName = profile?.nickname ?: profile?.phone ?: "",
        modifier = Modifier.size(ByteImDimensions.ProfileAvatarSize)
    )
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = profile?.nickname.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = ByteImColors.TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "ID: ${profile?.phone.orEmpty()}",
            style = MaterialTheme.typography.bodyMedium,
            color = ByteImColors.TextSecondary
        )
    }
    ChevronRightIcon()
}
```

- [ ] **Step 5: Redesign profile detail and name editor shells**

In `ProfileDetailScreen`, replace the manual top `Row` with:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .background(ByteImColors.AppBackground)
) {
    ByteImTopBar(title = MeDisplayPolicy.profileTitle, onBack = onBack)
    ByteImListSurface {
        ProfileAvatarRow(profile = profile, onClick = onChooseAvatar)
        HorizontalDivider(color = ByteImColors.Divider)
        ProfileNameRow(state = state, onEditName = onEditName)
        HorizontalDivider(color = ByteImColors.Divider)
        ProfileReadOnlyRow(label = MeDisplayPolicy.idRowLabel, value = profile?.phone.orEmpty())
    }
    state.errorMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(ByteImDimensions.EdgePadding)
        )
    }
}
```

In `ProfileNameEditorScreen`, replace the manual top `Row` with:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .background(ByteImColors.AppBackground)
) {
    ByteImTopBar(
        title = MeDisplayPolicy.nameEditorTitle,
        onBack = onBack,
        action = {
            TextButton(
                enabled = !state.isSaving,
                onClick = onSave
            ) {
                Text(if (state.isSaving) "Saving" else MeDisplayPolicy.nameEditorSaveLabel)
            }
        }
    )
    ByteImListSurface(modifier = Modifier.padding(top = 8.dp)) {
        UnderlinedNameTextField(
            value = state.draftNickname,
            onValueChange = onNicknameChange,
            modifier = Modifier.padding(horizontal = ByteImDimensions.EdgePadding)
        )
    }
    state.errorMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(ByteImDimensions.EdgePadding)
        )
    }
}
```

- [ ] **Step 6: Normalize profile rows**

For `ProfileAvatarRow`, `ProfileNameRow`, and `ProfileReadOnlyRow`, use `height(ByteImDimensions.ListItemHeight)` and `padding(horizontal = ByteImDimensions.EdgePadding)` instead of page-level `20.dp`. Use `height(80.dp)` for `ProfileAvatarRow` so the `48.dp` avatar has enough vertical space. Keep `onChooseAvatar` attached to `ProfileAvatarRow` and `onEditName` attached to `ProfileNameRow`.

- [ ] **Step 7: Run profile tests and compile**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.profile.MeBackPolicyTest --tests com.codex.im.profile.MeDisplayPolicyTest --tests com.codex.im.profile.MeViewModelTest
bash ./gradlew :app:compileDebugKotlin
```

Expected: tests PASS; compile PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/codex/im/profile/MeScreen.kt
git commit -m "Redesign ByteIM profile screens"
```

---

### Task 7: Chat Screen Text, Actions, Composer, and Status

**Files:**
- Modify: `app/src/main/java/com/codex/im/chat/ChatScreen.kt`

- [ ] **Step 1: Run chat policy tests before editing**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.chat.ChatDisplayPolicyTest --tests com.codex.im.chat.ChatMentionPolicyTest --tests com.codex.im.chat.ChatMessageActionLayoutPolicyTest --tests com.codex.im.chat.ChatKeyboardInsetsPolicyTest
```

Expected: PASS.

- [ ] **Step 2: Add ByteIM imports**

In `ChatScreen.kt`, add:

```kotlin
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImDimensions
import com.codex.im.ui.ByteImSystemNotice
import com.codex.im.ui.ByteImTopBar
import com.codex.im.ui.byteImBubbleColor
import com.codex.im.ui.byteImBubbleShape
```

- [ ] **Step 3: Redesign chat shell and top bar**

Replace the manual top `Row` with:

```kotlin
ByteImTopBar(
    title = state.peerName,
    onBack = onBack,
    action = {
        if (state.peerId.startsWith("group:")) {
            IconButton(onClick = { showGroupRename = true }) {
                Text(
                    text = "...",
                    style = MaterialTheme.typography.titleMedium,
                    color = ByteImColors.TextPrimary
                )
            }
        }
    }
)
```

Set the root column background:

```kotlin
Column(
    modifier = modifier
        .fillMaxSize()
        .background(ByteImColors.AppBackground)
        .imePadding()
        .clickable(
            indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            onClick = { activeActionMessageId = null }
        )
) {
    // top bar, list, errors, composer
}
```

- [ ] **Step 4: Style message list and system notice**

Change `LazyColumn` modifier:

```kotlin
modifier = Modifier
    .weight(1f)
    .fillMaxWidth()
    .background(ByteImColors.AppBackground)
    .padding(horizontal = ByteImDimensions.EdgePadding)
```

Change `RecalledMessageNotice` body to:

```kotlin
ByteImSystemNotice(
    text = text,
    modifier = Modifier.padding(vertical = 8.dp)
)
```

For history loader text, use secondary small styling:

```kotlin
Text(
    text = statusText,
    style = MaterialTheme.typography.bodySmall,
    color = ByteImColors.TextSecondary,
    modifier = Modifier.padding(vertical = 8.dp)
)
```

- [ ] **Step 5: Redesign message row**

In `ChatMessageRow`, set row spacing and avatar size:

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp),
    horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    verticalAlignment = Alignment.Top
) {
    if (!outgoing) {
        AvatarImage(
            avatarUrl = avatar.avatarUrl,
            displayName = avatar.displayName,
            modifier = Modifier.size(ByteImDimensions.ChatAvatarSize)
        )
    }
    ChatMessageContent(
        message = message,
        currentUserId = currentUserId,
        outgoing = outgoing,
        peerReadUpToServerSeq = peerReadUpToServerSeq,
        mentionMembers = mentionMembers,
        showActions = showActions,
        onOpenImagePreview = onOpenImagePreview,
        onOpenActions = onOpenActions,
        onDismissActions = onDismissActions,
        onRetryImage = onRetryImage,
        onCopyText = onCopyText,
        onRecall = onRecall,
        modifier = Modifier.padding(horizontal = ByteImDimensions.Gutter)
    )
    if (outgoing) {
        AvatarImage(
            avatarUrl = avatar.avatarUrl,
            displayName = avatar.displayName,
            modifier = Modifier.size(ByteImDimensions.ChatAvatarSize)
        )
    }
}
```

- [ ] **Step 6: Redesign text bubble**

In `ChatTextBubble`, replace `bubbleColor` and background:

```kotlin
val bubbleColor = byteImBubbleColor(outgoing)
val bubbleShape = byteImBubbleShape(outgoing)
Box(modifier = modifier) {
    Text(
        text = message.mentionText(mentionMembers),
        style = MaterialTheme.typography.bodyLarge,
        color = ByteImColors.TextPrimary,
        modifier = Modifier
            .background(bubbleColor, bubbleShape)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            )
            .padding(
                horizontal = ByteImDimensions.BubbleHorizontalPadding,
                vertical = ByteImDimensions.BubbleVerticalPadding
            )
    )
}
```

Change mention highlight color:

```kotlin
val highlightColor = ByteImColors.PrimaryGreen
```

- [ ] **Step 7: Redesign action menu and outgoing status**

In `ChatMessageActionBar`, use:

```kotlin
Row(
    modifier = modifier
        .background(ByteImColors.InverseSurface, RoundedCornerShape(8.dp))
        .padding(horizontal = 4.dp, vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    actions.forEach { action ->
        Text(
            text = when (action) {
                ChatMessageAction.COPY -> "复制"
                ChatMessageAction.RECALL -> "撤回"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier
                .widthIn(min = 48.dp)
                .clickable(
                    onClick = when (action) {
                        ChatMessageAction.COPY -> onCopy
                        ChatMessageAction.RECALL -> onRecall
                    }
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}
```

In `OutgoingMessageStatus`, keep status semantics but set colors:

```kotlin
MessageStatus.UPLOAD_FAILED,
MessageStatus.FAILED -> Text(
    text = "!",
    style = MaterialTheme.typography.labelLarge,
    color = ByteImColors.BadgeRed,
    modifier = Modifier.clickable(onClick = onRetry)
)
MessageStatus.SENT,
MessageStatus.RECEIVED -> Text(
    text = if (message.serverSeq != null && peerReadUpToServerSeq != null && message.serverSeq <= peerReadUpToServerSeq) "✓" else "○",
    style = MaterialTheme.typography.labelMedium,
    color = if (message.serverSeq != null && peerReadUpToServerSeq != null && message.serverSeq <= peerReadUpToServerSeq) ByteImColors.PrimaryGreen else ByteImColors.TextSecondary
)
```

- [ ] **Step 8: Redesign composer**

In `ChatComposerBar`, set:

```kotlin
val barColor = ByteImColors.Surface
val inputShape = RoundedCornerShape(18.dp)
```

Change the action buttons:

```kotlin
ChatComposerAction.PICK_IMAGE -> Button(
    onClick = onPickImage,
    colors = ButtonDefaults.buttonColors(containerColor = ByteImColors.SurfaceLow, contentColor = ByteImColors.PrimaryGreen),
    contentPadding = ButtonDefaults.ContentPadding
) {
    Text("Image")
}
ChatComposerAction.SEND_TEXT -> Button(
    onClick = onSend,
    enabled = canSend,
    colors = ButtonDefaults.buttonColors(containerColor = ByteImColors.PrimaryGreen, contentColor = Color.White),
    contentPadding = ButtonDefaults.ContentPadding
) {
    Text("Send")
}
```

Keep the current mention picker block above the composer row. The image button still calls the `onPickImage` lambda, which launches `imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))`. The send button still calls the `onSend` lambda, which clears `draft`, computes `mentionIds`, clears `selectedMentions`, and calls `viewModel.sendText(content, mentionedUserIds = mentionIds)`.

- [ ] **Step 9: Run chat tests and compile**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.chat.ChatDisplayPolicyTest --tests com.codex.im.chat.ChatMentionPolicyTest --tests com.codex.im.chat.ChatMessageActionLayoutPolicyTest --tests com.codex.im.chat.ChatKeyboardInsetsPolicyTest
bash ./gradlew :app:compileDebugKotlin
```

Expected: tests PASS; compile PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/codex/im/chat/ChatScreen.kt
git commit -m "Redesign ByteIM chat screen"
```

---

### Task 8: Chat Image Bubble and Preview Styling

**Files:**
- Modify: `app/src/main/java/com/codex/im/chat/ChatImageBubble.kt`
- Modify: `app/src/main/java/com/codex/im/chat/ChatImagePreviewScreen.kt`

- [ ] **Step 1: Run image-related tests before editing**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.chat.ChatImageBubbleLayoutPolicyTest --tests com.codex.im.chat.ChatImageBubbleLoadingPolicyTest
```

Expected: PASS.

- [ ] **Step 2: Style image bubble**

In `ChatImageBubble.kt`, import:

```kotlin
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.ui.graphics.Color
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImShapes
```

Set shape and background:

```kotlin
val bubbleShape = ByteImShapes.BubbleLarge
```

Change bubble background:

```kotlin
.background(Color(0xFFE5E2E1))
```

For upload/send progress overlay, use:

```kotlin
Box(
    modifier = Modifier
        .matchParentSize()
        .background(Color.Black.copy(alpha = 0.18f)),
    contentAlignment = Alignment.Center
) {
    CircularProgressIndicator(color = ByteImColors.PrimaryGreen)
}
```

For `UPLOAD_FAILED` and `FAILED`, use a compact retry-looking overlay:

```kotlin
Text(
    text = if (message.status == MessageStatus.UPLOAD_FAILED) "Upload failed" else "Send failed",
    style = MaterialTheme.typography.bodySmall,
    color = Color.White,
    modifier = Modifier
        .background(Color.Black.copy(alpha = 0.45f), ByteImShapes.Notice)
        .padding(horizontal = 10.dp, vertical = 4.dp)
)
```

- [ ] **Step 3: Style image preview page**

In `ChatImagePreviewScreen.kt`, keep this exact model selection line:

```kotlin
val model = message.localOriginalPath ?: message.imageUrl ?: message.localThumbnailPath ?: message.thumbnailUrl
```

Set the full-screen background to black, keep `clickable(onClick = onDismiss)`, and keep `SubcomposeAsyncImage` with `ContentScale.Fit`.

Use this shape for the root:

```kotlin
Box(
    modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .clickable(onClick = onDismiss),
    contentAlignment = Alignment.Center
) {
    SubcomposeAsyncImage(
        model = model,
        contentDescription = message.content,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        loading = {
            CircularProgressIndicator(color = ByteImColors.PrimaryGreen)
        },
        error = {
            Text(
                text = "Image load failed",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
    )
}
```

- [ ] **Step 4: Run image tests and compile**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.chat.ChatImageBubbleLayoutPolicyTest --tests com.codex.im.chat.ChatImageBubbleLoadingPolicyTest
bash ./gradlew :app:compileDebugKotlin
```

Expected: tests PASS; compile PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/im/chat/ChatImageBubble.kt app/src/main/java/com/codex/im/chat/ChatImagePreviewScreen.kt
git commit -m "Redesign ByteIM image message UI"
```

---

### Task 9: Final Verification and Cleanup

**Files:**
- Review all files modified by Tasks 1-8.

- [ ] **Step 1: Run full app unit tests**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest
```

Expected: PASS. If a test fails because an assertion expects `SelfHostedIM`, update that test only when it is user-visible brand behavior.

- [ ] **Step 2: Compile app**

Run:

```bash
bash ./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Verify brand strings**

Run:

```bash
rg -n "SelfHostedIM" app/src/main docs/PRD/IM_PRODUCT_REQUIREMENTS_CN_EN.md
```

Expected: no matches. Matches in generated build output or historical docs outside this command are not part of acceptance.

- [ ] **Step 4: Inspect changed files**

Run:

```bash
git diff --stat
git diff -- app/src/main/java/com/codex/im/connection app/src/main/java/com/codex/im/message app/src/main/java/com/codex/im/storage mock-server
```

Expected: `git diff --stat` lists UI, tests, and docs only; the second command prints no diff for protocol, message repository, storage, connection, or mock server paths.

- [ ] **Step 5: Manual UI checklist on emulator when an emulator or device is connected**

Run the app and inspect:

```bash
bash ./gradlew :app:installDebug
```

Expected manual results:

- Login shows `ByteIM`, accepts existing login behavior, shows errors without layout overlap.
- Register mode still validates confirm password.
- Messages tab shows `Message` or `Message(n)`, plus menu, unread badge, mention preview, and real empty state.
- Contacts opens single chat.
- Group create selects contacts and creates group using the existing flow.
- Chat text, image, copy, recall, retry, read status, and mention states remain usable.
- Me/Profile/Edit preserve avatar and nickname behavior.
- Android Back behavior remains unchanged on top-level and secondary pages.

- [ ] **Step 6: Final commit if cleanup changed files**

If Step 4 or Step 5 caused cleanup edits:

```bash
git add app/src/main docs/PRD app/src/test
git commit -m "Polish ByteIM UI redesign"
```

If no cleanup edits were needed, do not create an empty commit.
