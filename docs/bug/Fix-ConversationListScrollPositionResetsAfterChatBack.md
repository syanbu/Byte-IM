# 修复：会话列表进入聊天再返回后滚动位置重置

## 状态

- 状态：已完成
- 完成日期：2026-06-09
- 分支：`redesign-ui`
- 范围：`Messages` 页会话列表（`ConversationListScreen` / `ConversationListViewModel`）的滚动位置恢复。

## 现象

当 `Messages` 页的会话很多、用户已经下滑到较靠后的位置时：

1. 在会话列表中点击任意聊天进入 `Chat` 页。
2. 点击返回回到 `Messages`。

会话列表不会停留在离开前的位置，而是回到顶部或接近顶部的位置。

这个问题在本地灌入大量会话数据后很容易复现，体验上像 `Messages` 页被重新打开了一次。

对比行为：

- `Messages` 页会丢失滚动位置。
- `Contacts` 页在“进入联系人资料/聊天再返回”时可以保持原来的滚动位置。

这说明问题不是全局导航统一失效，而是 `ConversationList` 和 `ContactList` 两页的列表状态保存策略不一致。

## 根因

根因分两层，之前第一次修复只覆盖了第一层，没有覆盖第二层：

1. `ContactListScreen` 已经把 `LazyColumn` 的滚动位置提升到了 `ContactListViewModel`，而 `ConversationListScreen` 最初没有。
2. `ContactListViewModel` 是在 `AuthenticatedImNavHost` 这一层提前创建好的，后面 `Contacts` 页只是拿来用；但 `ConversationListViewModel` 当时是在 `Messages` 页自己的代码块里临时创建的。

这意味着即使会话列表后来补上了 `firstVisibleItemIndex` / `firstVisibleItemScrollOffset`：

- 如果 `ConversationListViewModel` 本身跟着 `Messages` 页一起离开、一起重建，
- 那么刚保存下来的滚动位置状态也会一起丢掉。

所以这次真实根因不是单点，而是：

> 会话列表既缺少滚动位置持久化，也缺少和 `Contacts` 页一致的 ViewModel 作用域。

只有两者同时满足，`Messages -> Chat -> Back -> Messages` 才能稳定回到离开前的位置。

## 修复方案

修复最终完全对齐 `Contacts` 页的做法，分三步：

### 1. 在 `ConversationListUiState` 中保存滚动位置

给 `ConversationListUiState` 增加两个字段：

```kotlin
val firstVisibleItemIndex: Int = 0
val firstVisibleItemScrollOffset: Int = 0
```

这样会话列表的 UI 状态里就有了可恢复的滚动锚点。

### 2. 在 `ConversationListScreen` 和 `ConversationListViewModel` 之间同步滚动位置

`ConversationListScreen` 不再直接用裸 `rememberLazyListState()`，而是改成：

```kotlin
val listState = rememberLazyListState(
    initialFirstVisibleItemIndex = state.firstVisibleItemIndex,
    initialFirstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset
)
```

同时通过 `snapshotFlow` 监听当前列表位置，并持续写回 ViewModel：

```kotlin
snapshotFlow {
    listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
}
```

`ConversationListViewModel` 新增：

```kotlin
fun updateScrollPosition(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int)
```

这个方法会：

- 将 index / offset 归一化到非负值。
- 只有在位置真正变化时才更新 state。

这样无论是从聊天页 `Back` 返回，还是页面因重组重新创建 `LazyListState`，都会先用 ViewModel 里上次记录的位置作为初始值。

### 3. 将 `ConversationListViewModel` 提升到 `AuthenticatedImNavHost` 顶层

和 `ContactListViewModel` 一样，`ConversationListViewModel` 现在在 `AuthenticatedImNavHost` 外层先按 `session.userId` 创建一次，后面只是反复复用：

```kotlin
val conversationListViewModel = remember(session.userId) {
    ConversationListViewModel(...)
}
```

`Messages` 页自己的代码块里不再重新创建新的 `ConversationListViewModel`。

这样当用户进入 `Chat` 再返回时：

- `ConversationListViewModel` 不会因为 `Messages` 页那一层被重建而丢失
- 其中保存的滚动位置也会继续存在

这一步是这次问题真正闭环的关键。

## 修复后的行为

修复后，用户操作：

1. 在 `Messages` 页滑到较长会话列表的中后部。
2. 点击某个会话进入聊天。
3. 点击返回。

应当回到原来的会话附近，而不是重新跳到顶部。

修复后 `Messages` 页和 `Contacts` 页在“列表页 -> 详情/聊天页 -> 返回”的滚动保持行为上保持一致。

## 为什么 `Contacts` 没有这个问题

`Contacts` 页之前已经做了完整的滚动位置保存链路：

- `ContactListUiState` 持有 `firstVisibleItemIndex` / `firstVisibleItemScrollOffset`
- `ContactListScreen` 用这两个值初始化 `rememberLazyListState`
- `snapshotFlow` 持续把滚动位置写回 `ContactListViewModel`
- `ContactListViewModel` 在 `AuthenticatedImNavHost` 顶层创建并跨 destination 复用

而 `ConversationList` 之前缺失的是这整套机制里的后两部分组合。

## 更直白的理解

可以把它想成“外层盒子包着内层页面”：

- `AuthenticatedImNavHost` 是外层盒子。
- `Messages` 页、`Contacts` 页、`Chat` 页是里面切换的页面。

如果一个 `ViewModel` 放在外层盒子里先创建好：

- 你进入别的页面再回来，它通常还在。
- 所以它里面记住的滚动位置也还在。

如果一个 `ViewModel` 是在某个页面自己的代码块里临时创建的：

- 这个页面离开时，它就可能一起被销毁。
- 回来时再新建一个新的，之前记住的位置就没了。

所以这里的第二层根因，本质上就是：

> 不是“有没有记住滚动位置”这么简单，而是“记住位置的那个对象，是放在外层长期活着，还是放在页面里跟着一起消失”。

所以这次不是“导航返回只对会话列表失效”，而是：

> 联系人页早就做了滚动状态持久化，会话页没有做。

## 涉及文件

- `app/src/main/java/com/buyansong/im/MainActivity.kt`
- `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt`
- `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt`
- `app/src/test/java/com/buyansong/im/conversation/ConversationListViewModelScopeTest.kt`
- `app/src/test/java/com/buyansong/im/conversation/ConversationListViewModelTest.kt`

## 验证

自动化验证：

```bash
./gradlew :app:testDebugUnitTest
```

结果：

- `BUILD SUCCESSFUL`
- `ConversationListViewModelScopeTest` 证明 `ConversationListViewModel` 已提升到 `AuthenticatedImNavHost` 顶层
- `ConversationListViewModelTest` 的滚动位置归一化测试通过

手动验证建议：

1. 在本地灌入大量会话数据。
2. 进入 `Messages`，下滑到中间或更靠后位置。
3. 点击某个会话进入聊天页。
4. 点击返回。
5. 确认会话列表回到离开前的位置。

## 相关参考

- `docs/bug/Fix-ContactsListScrollPositionResetsAfterNavigation.md`
