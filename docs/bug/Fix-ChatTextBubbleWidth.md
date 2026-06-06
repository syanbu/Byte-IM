# 修复：Chat 单聊 / 群聊 文本气泡宽度超出导致头像被挤出消息行

## 状态

- 状态：已完成
- 完成日期：2026-06-06
- 分支：`redesign-ui`

## 现象

在单聊和群聊中发送或接收较长的文本消息时，文本气泡会沿水平方向一直撑开，把头像挤出消息行的可视区域。

可观察到的行为：

- 长文本 OUTGOING 气泡的右边缘直接顶到屏幕右边。
- 头像（特别是 OUTGOING 气泡右侧的"自己"头像）被推出消息行，肉眼上"消失"在屏幕外。
- 即使头像数据（`user_profiles.avatar_url`）已经存在，UI 也不会渲染出来。
- 短消息下表现正常。
- 单聊和群聊共用同一份 `ChatMessageContent` / `ChatBubbleLine` / `ChatTextBubble` 实现，因此两边都受影响。

期望行为：

- 文本气泡的最大宽度应被限制在行宽的一定比例内。
- 超出宽度的文本应自动换行到下一行。
- 头像、bubble 边距和消息行布局保持稳定，不受消息长度影响。

## 根因

`ChatMessageContent` 原本直接使用 `Box` 包裹气泡，没有读取父容器的可用宽度。`ChatTextBubble` 内部的 `Text` 也没有显式 `widthIn` 约束，因此 `Text` 的测量宽度等于自然排版宽度。

对于长消息：

1. `Text` 自然宽度超过父容器可用宽度。
2. `Box` 没有限制 `Text` 宽度。
3. 气泡的 `background` 和 `byteImBubbleShape` 跟着 `Text` 一同被拉伸，最终撑爆整行。
4. 头像位于气泡同一行的水平端点位置，因此被挤出消息行。

即便 `byteImBubbleShape` 内部使用 `RoundedCornerShape` 也会被拉成横向不规则的形状，整个消息行的视觉布局被破坏。

## 修复方案

把"消息行可用宽度 → 气泡最大宽度"这一步抽成纯策略对象，UI 层只负责读取父容器宽度并把它应用为气泡的 `widthIn(max = ...)`。

实现要点：

1. 新增 [ChatTextBubbleLayoutPolicy.kt](app/src/main/java/com/codex/im/chat/ChatTextBubbleLayoutPolicy.kt)：

   - `MaxBubbleWidthFraction = 0.72f`
   - `maxBubbleWidth(availableRowWidthDp: Int): Int` 接收父容器可用宽度（dp，向下取整），返回气泡允许的最大宽度。
   - 用 `require(availableRowWidthDp > 0)` 守住非法输入。

2. `ChatMessageContent` 把外层 `Box` 换成 `BoxWithConstraints`，并把 `maxWidth` 转换成 dp：

   ```kotlin
   BoxWithConstraints(
       modifier = modifier,
       contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart
   ) {
       val maxBubbleWidth = ChatTextBubbleLayoutPolicy
           .maxBubbleWidth(maxWidth.value.roundToInt())
           .dp
       ChatBubbleLine(
           ...,
           maxBubbleWidth = maxBubbleWidth,
           ...
       )
   }
   ```

3. `ChatBubbleLine` 和 `ChatTextBubble` 增加 `maxBubbleWidth: Dp` 参数，向下透传。

4. `ChatTextBubble` 在 `Text` 的 `Modifier` 上加上 `widthIn(max = maxBubbleWidth)`：

   ```kotlin
   Text(
       ...,
       modifier = Modifier
           .widthIn(max = maxBubbleWidth)
           .background(byteImBubbleColor(outgoing), byteImBubbleShape(outgoing))
           .combinedClickable(...)
   )
   ```

5. 不影响 `ChatBubbleLine` 里图片 / 表情等非文本子分支的现有布局。

## 涉及文件

- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- `app/src/main/java/com/codex/im/chat/ChatTextBubbleLayoutPolicy.kt`（新增）
- `app/src/test/java/com/codex/im/chat/ChatTextBubbleLayoutPolicyTest.kt`（新增）
- `docs/status/B2-single-chat.md`

## 验证

自动化测试（2026-06-06）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.chat.ChatTextBubbleLayoutPolicyTest --console=plain
```

- `BUILD SUCCESSFUL`。
- `ChatTextBubbleLayoutPolicyTest` 覆盖 `maxBubbleWidth` 的常规比例、向下取整边界以及非法输入。

手动验证（与 `B2-single-chat.md` 一致）：

- 单聊中发送长度明显超过屏幕一半的文本消息。
- 文本自动换行，气泡最右侧不再顶到屏幕边。
- OUTGOING 行右侧的"自己"头像和 INCOMING 行左侧的"对方"头像均稳定显示在消息行内。
- 群聊同款长消息行为一致。
