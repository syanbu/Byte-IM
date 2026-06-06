# 修复：通讯录导航返回后滚动位置重置

## 目前的问题

通讯录列表滑动到中间位置后，如果用户执行下面任一操作：

1. 从通讯录切换到消息页或我的页面，再切回通讯录。
2. 在通讯录中点击某个联系人进入资料页，然后点击返回。

通讯录会回到列表顶部，而不是保持用户离开前的滚动位置。

这个问题在好友数量较多时尤其明显。例如用户已经滑到 `15000000038` 附近，切走再回来后又看到“新的朋友”“群聊”和前几个联系人，体验上像通讯录被重新打开了一次。

## 为什么会这个问题

问题分成两层：

1. `ContactListScreen` 里的 `LazyColumn` 最初没有显式持有 `LazyListState`，滚动位置只存在于 Compose 内部状态里。
2. `ContactListViewModel` 原本是在 `Contacts` 这个 destination 内部创建的。切换到其他顶层 Tab 或进入资料页时，`Contacts` destination 可能离开 composition，回来时会重新创建页面内部状态。

第一次修复只把 `LazyColumn` 的位置写进了 `ContactListViewModel`，但如果 `ContactListViewModel` 本身也随着 `Contacts` destination 被重新创建，滚动位置仍然会丢。

根因是：

> 滚动位置需要保存到比通讯录页面 Composable 生命周期更长的状态持有者里。

## 修复设计

修复采用两步：

### 1. 将滚动位置提升到 ContactListViewModel

`ContactListUiState` 增加滚动位置字段：

```kotlin
data class ContactListUiState(
    val items: List<ContactListItem> = emptyList(),
    val navigationTargetPeerId: String? = null,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)
```

`ContactListViewModel` 提供更新方法：

```kotlin
fun updateScrollPosition(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int)
```

`ContactListScreen` 使用：

```kotlin
val listState = rememberLazyListState(
    initialFirstVisibleItemIndex = state.firstVisibleItemIndex,
    initialFirstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset
)
```

并通过 `snapshotFlow` 把 `LazyColumn` 当前滚动位置同步回 ViewModel。

### 2. 将 ContactListViewModel 提升到 AuthenticatedImNavHost 级别

`ContactListViewModel` 不再在 `Contacts` destination 内部创建，而是在 `AuthenticatedImNavHost` 顶层按 `session.userId` 创建一次：

```kotlin
val contactListViewModel = remember(session.userId) {
    ContactListViewModel(...)
}
```

然后 `Contacts` destination 只复用这个实例。

这样即使通讯录页面 Composable 因为 Tab 切换或资料页导航被销毁，ViewModel 仍然保留：

- 联系人列表数据
- 已启动状态
- 滚动位置
- 导航目标状态

## 修复后的行为

修复后：

1. 滑动通讯录到中间位置。
2. 切换到消息页或我的页面。
3. 再切回通讯录。
4. 通讯录保持原来的滚动位置。

进入联系人资料页再返回时，也会回到原来的联系人附近，而不是回到顶部。

## 验证结果

已增加测试：

- `ContactListViewModelTest.scrollPositionSurvivesStopStartAndRefreshUpdates`
- `ContactListViewModelScopeTest.contactListViewModelIsRememberedAboveContactsDestination`

验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.contacts.* --console=plain
```

结果：

- `BUILD SUCCESSFUL`
- contacts 相关测试全部通过

## 相关文件

- `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt`
- `app/src/main/java/com/codex/im/contacts/ContactListViewModel.kt`
- `app/src/main/java/com/codex/im/MainActivity.kt`
- `app/src/test/java/com/codex/im/contacts/ContactListViewModelTest.kt`
- `app/src/test/java/com/codex/im/contacts/ContactListViewModelScopeTest.kt`
