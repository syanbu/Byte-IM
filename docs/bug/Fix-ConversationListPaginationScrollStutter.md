# 修复：Conversation List 滚动分页在分页边界漏触发与卡顿

## 状态

- 状态：已完成
- 完成日期：2026-06-06
- 分支：`redesign-ui`
- 范围：会话列表（`ConversationListScreen` / `ConversationListViewModel`）的滚动分页触发与后台预取。

## 现象

会话列表在快滑通过 50 条分页边界时存在两类问题：

1. **触发丢失**：用户快速滚动穿过 50 条边界时，列表没有及时触发下一页加载，需要回滑一段或停留一会儿才会补一次翻页。
2. **页面卡顿**：即使翻页被触发，每跨一次 50 条边界都会出现一次明显的卡顿（DB 往返 + Compose 重排/重组装，≈ 100–300 ms / 边界）。在 499 条种子会话的列表里，每翻 50 行都能感受到一次 stutter。

可观察到的行为：

- 快滑到第 ~60 行时，列表可能继续停在 50 条，没有无缝衔接第 51~100 条。
- 翻页发生的那一帧会出现可感知的卡顿，气泡 / 头像 / 时间戳短暂停顿。
- 收到新消息触发 `refresh` 后再次快滑，`refresh` 之前预热好的下一页可能被错误复用。

期望行为：

- 跨分页边界时列表应无缝衔接下一页。
- 翻页边界不应在主线程上同步阻塞 Compose 渲染。
- 后续翻页的 DB 查询尽量在用户滚到边界之前完成。

## 根因

会话列表的滚动分页触发与数据加载分两层实现，两层都存在缺陷：

### 1. UI 触发层：`snapshotFlow` + `LaunchedEffect` 被反复取消

`ConversationListScreen` 原本使用如下结构：

```kotlin
LaunchedEffect(viewModel, listState, state.items.size,
    state.hasMoreConversations, state.isLoadingMore) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
        .distinctUntilChanged()
        .collect { lastVisibleIndex ->
            val shouldLoadMore = state.hasMoreConversations &&
                !state.isLoadingMore &&
                state.items.isNotEmpty() &&
                lastVisibleIndex >= state.items.lastIndex - 5
            if (shouldLoadMore) viewModel.loadMoreConversations()
        }
}
```

问题：

- `LaunchedEffect` 的 key 包含 `state.items.size` / `state.hasMoreConversations` / `state.isLoadingMore`。
- 每当一次翻页成功完成，这几个 key 都会变化，`LaunchedEffect` 取消当前 collect、随后重建。
- 重建后 `snapshotFlow` 的第一次发射要等下一个 snapshot 周期才到，期间用户继续快滑时滚动事件可能从指缝里漏掉，触发不到 `loadMoreConversations`。
- 触发的判定逻辑（`hasMore && !isLoadingMore && items.isNotEmpty() && lastVisibleIndex >= lastIndex - 5`）直接写在 UI 层的闭包里，无法被单元测试独立验证。

### 2. 数据加载层：没有下一页预取 + 命中缓存也在主线程拼装

`ConversationListViewModel.loadMoreConversations()` 原本的流程是：

- 直接调用 `repository.conversationPage(...)` 拉下一页。
- 在当前 `scope.launch` 的协程里做 `mergeConversations` + `updateItems`。

问题：

- 每次跨越 50 条边界都至少要 1 次 DAO 查询（~100 ms）+ 1 次主线程 merge / sort / item-build，UI 侧能看到 stutter。
- 没有"在用户滚到边界之前就把下一页查好"的预取机制，所有 DAO 查询都是"on demand"。

## 修复方案

修复分两个 follow-up，分别解决上面两个根因。

### Follow-up 1：把滚动触发改为 `derivedStateOf` + `LaunchedEffect(Boolean)`

`ConversationListScreen`：

- 引入 `derivedStateOf` 持有"是否应该加载更多"的布尔状态：

  ```kotlin
  val shouldLoadMore by remember(listState, state.items.size,
      state.hasMoreConversations, state.isLoadingMore) {
      derivedStateOf {
          val visibleLastIndex = listState.layoutInfo.visibleItemsInfo
              .lastOrNull()?.index ?: -1
          ConversationListLoadMorePolicy.shouldLoadMore(
              visibleLastIndex = visibleLastIndex,
              itemCount = state.items.size,
              hasMore = state.hasMoreConversations,
              isLoadingMore = state.isLoadingMore
          )
      }
  }
  ```

- 把 `LaunchedEffect` 简化为只对这个布尔值响应：

  ```kotlin
  LaunchedEffect(shouldLoadMore) {
      if (shouldLoadMore) viewModel.loadMoreConversations()
  }
  ```

- 这样订阅滚动状态的协程只在 `shouldLoadMore` 翻转时启停，不再因 `state.items.size` 变化被撕掉重建。
- 模式与 `ChatScreen` 的 history-pagination 触发保持一致。

把判定逻辑抽出为纯对象，便于单测与复用：

- 新增 [ConversationListLoadMorePolicy.kt](app/src/main/java/com/buyansong/im/conversation/ConversationListLoadMorePolicy.kt)：
  - `LOAD_MORE_THRESHOLD_ITEMS = 10`（距离列表底部 10 条时触发）
  - `shouldLoadMore(visibleLastIndex, itemCount, hasMore, isLoadingMore)` 返回布尔。
  - 把"列表为空 / 已无更多 / 正在加载 / 未到阈值 / 列表短于阈值"几条边界全部覆盖。

### Follow-up 2：在后台预取下一页，且消费缓存也走后台 dispatcher

`ConversationListViewModel` 新增两个私有字段：

```kotlin
private var prefetchedPage: List<Conversation>? = null
private var prefetchJob: Job? = null
```

并改造以下路径：

1. **`loadMoreConversations()`**：
   - 先看 `prefetchedPage`：如果非空，把它清空并取消可能在跑的 `prefetchJob`，然后调用 `applyPage(cached)`。
   - 缓存为空：取消 `prefetchJob`、清空缓存，然后走原来的 `repository.conversationPage(...)` 路径。
   - 命中缓存时整个 `applyPage` 仍然在 `paging dispatcher` 上跑，`mergeConversations` / `updateItems` 不再卡在调用方线程。

2. **新增 `applyPage(page)`**：
   - 做 `loadedConversations = mergeConversations(loadedConversations, page)`。
   - 调用 `updateItems(...)`（包含 `isLoadingMore = false` 和 `hasMore = page.size == CONVERSATION_PAGE_SIZE`）。
   - 若 `hasMore == true`，立即调用 `scheduleNextPagePrefetch()` 预热下一页。

3. **新增 `scheduleNextPagePrefetch()`**：
   - 取消上一个 `prefetchJob`。
   - 在 `paging dispatcher` 上启动新协程，查询下一页并写入 `prefetchedPage`。
   - 写入前用 `ensureActive()` 守护，避免在 `loadMoreConversations` 抢占时被覆盖。

4. **`refresh()`**：
   - 开头先 `prefetchJob?.cancel()` + `prefetchedPage = null`，确保新消息 / 手动刷新 / 会话恢复不会复用脏的预取数据。
   - 完成首屏 + 合并后再判断是否需要 `scheduleNextPagePrefetch()`，让首屏打开后的下一个 50 条边界也是"热"的。

5. **`RECENT_THUMBNAIL_PRELOAD_LIMIT`** 由 5 提升到 10，与触发阈值保持一致。

## 涉及文件

- `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt`
- `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt`
- `app/src/main/java/com/buyansong/im/conversation/ConversationListLoadMorePolicy.kt`（新增）
- `app/src/test/java/com/buyansong/im/conversation/ConversationListLoadMorePolicyTest.kt`（新增）
- `app/src/test/java/com/buyansong/im/conversation/ConversationListViewModelTest.kt`（新增 2 个测试 + `CountingConversationDao` 包装器）
- `app/src/test/java/com/buyansong/im/conversation/ConversationRowLayoutTest.kt`（sniff assertion 由 `snapshotFlow` 改为 `derivedStateOf`）
- `docs/status/B3-conversation-list.md`（新增 "Post-Implementation Refinements (2026-06-06)" 章节）

## 验证

自动化测试（2026-06-06）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.conversation.ConversationListLoadMorePolicyTest --tests com.buyansong.im.conversation.ConversationListViewModelTest --tests com.buyansong.im.conversation.ConversationRowLayoutTest --console=plain
```

- `BUILD SUCCESSFUL`。
- `ConversationListLoadMorePolicyTest` 覆盖阈值命中、未到阈值、加载中、已无更多、空列表、列表短于阈值等分支。
- `ConversationListViewModelTest` 新增 `loadMoreConversationsPrefetchesAndReusesCachedPage`：断言启动后 `start()` 已经预热好第 2 页（DAO 查询数 +1），首次 `loadMoreConversations` 命中缓存（DAO 总数仍为 4），第二次 `loadMoreConversations` 同样命中且预热下一批（DAO 总数 = 5），第三次因已无更多不预取（DAO 总数保持 5）。
- `ConversationListViewModelTest` 新增 `refreshClearsPendingPrefetchedPage`：断言收到新消息触发 `refresh` 后，原预取被清空，且 `refresh` 结束时会重建一份新的预取（DAO 总数 +3，列表项数 = 151）。
- `ConversationRowLayoutTest` 的 sniff assertion 已同步从 `snapshotFlow` 改为 `derivedStateOf`。

手动验证（与 `B3-conversation-list.md` 一致）：

- 在已注入 499 条会话的本地数据上快速从顶滑到底，会话列表应当无缝衔接每一页 50 条，没有出现 50 → 100 → 150 之间的明显停顿。
- 收到新消息后再快滑，新消息进顶部，不应出现"已加载的旧页被吞回"或"翻页触发不到"。
- 跨边界时不应在主线程上看到同步阻塞（开 StrictMode / Choreographer 帧监控时无掉帧告警）。

## 后续（暂未实现）

- 加载中指示器：`isLoadingMore` 已经暴露在 UI state，可后续接上底部小 spinner。
- `CONVERSATION_PAGE_SIZE = 50` 的取值仍以经验为主，待真机数据上观察后再决定是否调整。
