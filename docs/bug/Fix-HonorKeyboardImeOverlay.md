# Fix: Honor Keyboard IME Overlay In Chat Screen

## Status

- Status: Completed
- Completed on: 2026-05-30
- Branch: current working branch

## Observed Symptoms

On a Honor Android device, tapping the chat input field opened the IME over the
chat activity instead of moving the chat composer above the keyboard.

Observed behavior:

- The input method covered the bottom portion of the chat screen.
- The chat composer did not reliably move above the keyboard.
- Google Pixel 7 did not reproduce the issue with the same app flow.

An earlier attempt using `android:windowSoftInputMode="adjustResize"` fixed the
layout on Pixel, but Honor still did not resize the Activity reliably.

## Root Cause

The chat screen originally mixed app-side IME padding with system-driven window
resize behavior. On some devices this produced excessive blank space above the
keyboard. After switching to system `adjustResize`, Pixel handled the resize
correctly, but the Honor ROM/input-method combination did not resize the
Activity consistently and allowed the keyboard to overlay the content.

The stable fix is to avoid depending on platform-specific Activity resize
behavior for the chat composer and let Compose handle IME insets directly.

## Implemented Fix Summary

Implemented changes:

1. Set `MainActivity` to `android:windowSoftInputMode="adjustNothing"`.
2. Kept `Modifier.imePadding()` on the chat composer container.
3. Added `ChatKeyboardInsetsPolicyTest` to guard the chosen inset strategy.

This makes the system leave the Activity bounds alone while Compose moves the
composer according to the reported IME inset.

## Changed Files

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- `app/src/test/java/com/codex/im/chat/ChatKeyboardInsetsPolicyTest.kt`

## Result

After the fix:

- Honor device no longer leaves the composer covered by the keyboard.
- Pixel behavior remains compatible.
- The app uses one clear keyboard strategy for the chat composer:
  `adjustNothing` at the Activity level plus Compose `imePadding()` at the
  composer level.

## Verification Result

Verified with automated tests on 2026-05-30:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.chat.ChatKeyboardInsetsPolicyTest --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.chat.* --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

All commands completed with `BUILD SUCCESSFUL`.

Manual verification:

- Retested on the Honor device after reinstalling the app.
- The chat input bar now moves above the keyboard instead of being covered.
