package com.buyansong.im

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.buyansong.im.alert.MessageAlertController
import com.buyansong.im.alert.MessageAlertHost
import com.buyansong.im.app.AppInfo
import com.buyansong.im.app.MockServerConfig
import com.buyansong.im.auth.AuthRepository
import com.buyansong.im.auth.AuthSession
import com.buyansong.im.auth.LoginScreen
import com.buyansong.im.auth.LoginViewModel
import com.buyansong.im.auth.OkHttpAuthApi
import com.buyansong.im.auth.SharedPreferencesTokenStore
import com.buyansong.im.auth.ValidSessionProvider
import com.buyansong.im.chat.ChatInitialImagePrewarmer
import com.buyansong.im.chat.ChatScreen
import com.buyansong.im.chat.ChatViewModel
import com.buyansong.im.connection.OkHttpImConnection
import com.buyansong.im.connection.ConnectionLifecycleManager
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.contacts.ContactListScreen
import com.buyansong.im.contacts.ContactListViewModel
import com.buyansong.im.contacts.ContactProfileScreen
import com.buyansong.im.contacts.ContactProfileViewModel
import com.buyansong.im.contacts.ContactRepository
import com.buyansong.im.contacts.OkHttpContactApi
import com.buyansong.im.conversation.ConversationListScreen
import com.buyansong.im.conversation.ConversationListViewModel
import com.buyansong.im.group.GroupCreateScreen
import com.buyansong.im.group.GroupCreateNavigationPolicy
import com.buyansong.im.group.GroupCreateViewModel
import com.buyansong.im.group.DefaultGroupRepository
import com.buyansong.im.group.GroupInfoScreen
import com.buyansong.im.group.GroupInfoViewModel
import com.buyansong.im.group.JoinedGroupsScreen
import com.buyansong.im.group.JoinedGroupsViewModel
import com.buyansong.im.group.OkHttpGroupApi
import com.buyansong.im.group.DefaultGroupReadCursorRepository
import com.buyansong.im.message.AndroidChatThumbnailCache
import com.buyansong.im.message.CoroutineThumbnailDownloadScheduler
import com.buyansong.im.message.MessageIdGenerator
import com.buyansong.im.message.MessageOutboxWorker
import com.buyansong.im.message.MessagePacketProcessor
import com.buyansong.im.message.MessageRepository
import com.buyansong.im.message.ImageUploadApi
import com.buyansong.im.message.OkHttpImageUploadApi
import com.buyansong.im.message.SeqGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.buyansong.im.profile.MeScreen
import com.buyansong.im.profile.MeViewModel
import com.buyansong.im.profile.OkHttpAvatarUploadApi
import com.buyansong.im.profile.OkHttpProfileApi
import com.buyansong.im.profile.AvatarUploadApi
import com.buyansong.im.profile.ProfileRepository
import com.buyansong.im.push.MockPushTokenStore
import com.buyansong.im.push.OkHttpPushApi
import com.buyansong.im.push.PushDeepLink
import com.buyansong.im.push.PushPollScheduler
import com.buyansong.im.push.PushTokenRepository
import com.buyansong.im.storage.AndroidConversationDao
import com.buyansong.im.storage.AndroidFriendContactDao
import com.buyansong.im.storage.AndroidGroupDao
import com.buyansong.im.storage.AndroidGroupReadCursorDao
import com.buyansong.im.storage.AndroidMessageDao
import com.buyansong.im.storage.AndroidPendingMessageDao
import com.buyansong.im.storage.AndroidTransactionRunner
import com.buyansong.im.storage.AndroidUserProfileDao
import com.buyansong.im.storage.AccountScopedDatabaseName
import com.buyansong.im.storage.ImDatabaseHelper
import com.buyansong.im.ui.ByteImColors
import com.buyansong.im.ui.ByteImDimensions

class MainActivity : ComponentActivity() {
    private var connectionLifecycleManager: ConnectionLifecycleManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val pendingPushDeepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePushIntent(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val mockServerConfig = MockServerConfig.load(this)
        val rawConnection = OkHttpImConnection(mockServerConfig.webSocketUrl)
        val repository = AuthRepository(
            authApi = OkHttpAuthApi(baseUrl = mockServerConfig.httpBaseUrl),
            tokenStore = SharedPreferencesTokenStore(this)
        )
        val connection = ConnectionLifecycleManager(
            connection = rawConnection,
            tokenProvider = { _ -> repository.ensureValidSession()?.accessToken }
        )
        connectionLifecycleManager = connection
        registerNetworkRecoveryCallback(connection)
        val thumbnailCache = AndroidChatThumbnailCache(this)
        setContent {
            val loginViewModel = remember { LoginViewModel(repository) }
            SelfHostedImApp(
                loginViewModel = loginViewModel,
                validSessionProvider = repository::ensureValidSession,
                connection = connection,
                httpBaseUrl = mockServerConfig.httpBaseUrl,
                thumbnailCache = thumbnailCache,
                pendingPushDeepLink = pendingPushDeepLink,
                onPushDeepLinkConsumed = { pendingPushDeepLink.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePushIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        connectionLifecycleManager?.setForeground(true)
    }

    override fun onStop() {
        connectionLifecycleManager?.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        unregisterNetworkRecoveryCallback()
        super.onDestroy()
    }

    private fun handlePushIntent(intent: Intent?) {
        pendingPushDeepLink.value = PushDeepLink.extractConversationId(intent)
    }

    private fun registerNetworkRecoveryCallback(connection: ConnectionLifecycleManager) {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connection.notifyNetworkAvailable()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    connection.notifyNetworkAvailable()
                }
            }

            override fun onLost(network: Network) {
                if (!manager.hasInternetCapableNetwork()) {
                    connection.notifyNetworkUnavailable()
                }
            }

            override fun onUnavailable() {
                connection.notifyNetworkUnavailable()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(request, callback)
        connectivityManager = manager
        networkCallback = callback
    }

    private fun unregisterNetworkRecoveryCallback() {
        val callback = networkCallback ?: return
        connectivityManager?.unregisterNetworkCallback(callback)
        networkCallback = null
        connectivityManager = null
    }

}

@Suppress("DEPRECATION")
private fun ConnectivityManager.hasInternetCapableNetwork(): Boolean {
    return allNetworks.any { network ->
        getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}

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

@Composable
fun SelfHostedImApp(
    loginViewModel: LoginViewModel? = null,
    validSessionProvider: ValidSessionProvider? = null,
    connection: ImConnection? = null,
    httpBaseUrl: String? = null,
    thumbnailCache: AndroidChatThumbnailCache? = null,
    pendingPushDeepLink: StateFlow<String?>? = null,
    onPushDeepLinkConsumed: () -> Unit = {}
) {
    MaterialTheme(colorScheme = ByteImColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ByteImColors.AppBackground)
                .systemBarsPadding()
        ) {
            if (loginViewModel == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = AppInfo.name, style = MaterialTheme.typography.headlineMedium)
                Text(text = "项目骨架已就绪", style = MaterialTheme.typography.bodyLarge)
            }
            } else {
            val state by loginViewModel.state.collectAsState()
            LaunchedEffect(loginViewModel) {
                loginViewModel.restoreSession()
            }
            val session = state.session
            if (state.isAuthenticated && session != null && validSessionProvider != null && connection != null && httpBaseUrl != null && thumbnailCache != null) {
                val context = LocalContext.current
                val accountRepositories = remember(session.userId) {
                    AccountScopedRepositories.create(
                        context = context.applicationContext,
                        userId = session.userId,
                        httpBaseUrl = httpBaseUrl,
                        connection = connection,
                        thumbnailCache = thumbnailCache
                    )
                }
                DisposableEffect(accountRepositories) {
                    onDispose {
                        accountRepositories.close()
                    }
                }
                AuthenticatedImNavHost(
                    session = session,
                    validSessionProvider = validSessionProvider,
                    messageRepository = accountRepositories.messageRepository,
                    connection = connection,
                    profileRepository = accountRepositories.profileRepository,
                    contactRepository = accountRepositories.contactRepository,
                    groupRepository = accountRepositories.groupRepository,
                    pushTokenRepository = accountRepositories.pushTokenRepository,
                    avatarUploadApi = accountRepositories.avatarUploadApi,
                    imageUploadApi = accountRepositories.imageUploadApi,
                    pendingPushDeepLink = pendingPushDeepLink,
                    onPushDeepLinkConsumed = onPushDeepLinkConsumed,
                    onLogout = {
                        accountRepositories.messageRepository.closeConversation()
                        val validSession = validSessionProvider()
                        accountRepositories.pushTokenRepository.unregister(
                            accessToken = validSession?.accessToken ?: session.accessToken,
                            userId = session.userId
                        )
                        PushPollScheduler.cancel(context.applicationContext, session.userId)
                        connection.disconnect()
                        loginViewModel.logout()
                    }
                )
            } else {
                LoginScreen(
                    state = state,
                    onLogin = loginViewModel::login,
                    onRegister = loginViewModel::register
                )
            }
        }
        }
    }
}

private class AccountScopedRepositories private constructor(
    private val helper: ImDatabaseHelper,
    private val database: SQLiteDatabase,
    private val thumbnailDownloadScope: CoroutineScope,
    val messageRepository: MessageRepository,
    val profileRepository: ProfileRepository,
    val contactRepository: ContactRepository,
    val groupRepository: com.buyansong.im.group.GroupRepository,
    val pushTokenRepository: PushTokenRepository,
    val avatarUploadApi: AvatarUploadApi,
    val imageUploadApi: ImageUploadApi
) {
    fun close() {
        thumbnailDownloadScope.cancel()
        database.close()
        helper.close()
    }

    companion object {
        fun create(
            context: Context,
            userId: String,
            httpBaseUrl: String,
            connection: ImConnection,
            thumbnailCache: AndroidChatThumbnailCache
        ): AccountScopedRepositories {
            val helper = ImDatabaseHelper(
                context = context,
                databaseName = AccountScopedDatabaseName.forUser(userId)
            )
            val database = helper.writableDatabase
            val conversationDao = AndroidConversationDao(database)
            val thumbnailDownloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val profileRepository = ProfileRepository(
                userProfileDao = AndroidUserProfileDao(database),
                profileApi = OkHttpProfileApi(baseUrl = httpBaseUrl)
            )
            val contactRepository = ContactRepository(
                contactApi = OkHttpContactApi(baseUrl = httpBaseUrl),
                friendContactDao = AndroidFriendContactDao(database)
            )
            val groupReadCursorRepository = DefaultGroupReadCursorRepository(
                dao = AndroidGroupReadCursorDao(database)
            )
            val messageRepository = MessageRepository(
                messageDao = AndroidMessageDao(database),
                conversationDao = conversationDao,
                pendingMessageDao = AndroidPendingMessageDao(database),
                connection = connection,
                messageIdGenerator = MessageIdGenerator(),
                seqGenerator = SeqGenerator(),
                transactionRunner = AndroidTransactionRunner(database),
                profileRepository = profileRepository,
                thumbnailCache = thumbnailCache,
                thumbnailDownloadScheduler = CoroutineThumbnailDownloadScheduler(
                    thumbnailCache = thumbnailCache,
                    scope = thumbnailDownloadScope,
                    prewarmLocalThumbnail = { localPath ->
                        ChatInitialImagePrewarmer.prewarmLocalThumbnail(context, localPath)
                    }
                ),
                groupReadCursorRepository = groupReadCursorRepository
            )
            val groupRepository = DefaultGroupRepository(
                groupApi = OkHttpGroupApi(baseUrl = httpBaseUrl),
                groupDao = AndroidGroupDao(database),
                conversationDao = conversationDao
            )
            val pushTokenRepository = PushTokenRepository(
                api = OkHttpPushApi(baseUrl = httpBaseUrl),
                tokenStore = MockPushTokenStore(context),
                deviceIdProvider = {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
                }
            )
            return AccountScopedRepositories(
                helper = helper,
                database = database,
                thumbnailDownloadScope = thumbnailDownloadScope,
                messageRepository = messageRepository,
                profileRepository = profileRepository,
                contactRepository = contactRepository,
                groupRepository = groupRepository,
                pushTokenRepository = pushTokenRepository,
                avatarUploadApi = OkHttpAvatarUploadApi(baseUrl = httpBaseUrl),
                imageUploadApi = OkHttpImageUploadApi(baseUrl = httpBaseUrl)
            )
        }
    }
}

@Composable
private fun AuthenticatedImNavHost(
    session: AuthSession,
    validSessionProvider: ValidSessionProvider,
    messageRepository: MessageRepository,
    connection: ImConnection,
    profileRepository: ProfileRepository,
    contactRepository: ContactRepository,
    groupRepository: com.buyansong.im.group.GroupRepository,
    pushTokenRepository: PushTokenRepository,
    avatarUploadApi: AvatarUploadApi,
    imageUploadApi: ImageUploadApi,
    pendingPushDeepLink: StateFlow<String?>?,
    onPushDeepLinkConsumed: () -> Unit,
    onLogout: suspend () -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val emptyPushDeepLink = remember { MutableStateFlow<String?>(null) }
    val pushDeepLink by (pendingPushDeepLink ?: emptyPushDeepLink).collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val activity = LocalContext.current.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiScope = rememberCoroutineScope()
    val messageAlertController = remember(session.userId) {
        MessageAlertController(scope = uiScope)
    }
    val unreadBadgeController = remember(session.userId) {
        MessagesTabUnreadBadgeController(
            repository = messageRepository,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        )
    }
    val unreadMessagesCount by unreadBadgeController.unreadCount.collectAsState()
    val messagePacketProcessor = remember(session.userId) {
        MessagePacketProcessor(
            repository = messageRepository,
            connection = connection
        )
    }
    val messageOutboxWorker = remember(session.userId) {
        MessageOutboxWorker(
            repository = messageRepository,
            connection = connection
        )
    }
    val openPreloadedChat: (String) -> Unit = remember(context, uiScope, messageRepository, navController) {
        { conversationId ->
            uiScope.launch {
                val messages = messageRepository.preloadInitialPageSync(conversationId)
                ChatInitialImagePrewarmer.prewarmBeforeNavigation(context, messages)
                SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
            }
        }
    }
    val contactListViewModel = remember(session.userId) {
        ContactListViewModel(
            session = session,
            profileRepository = profileRepository,
            contactRepository = contactRepository,
            validSessionProvider = validSessionProvider
        )
    }
    val conversationListViewModel = remember(session.userId) {
        ConversationListViewModel(
            session = session,
            repository = messageRepository,
            connection = connection,
            profileRepository = profileRepository,
            groupRepository = groupRepository,
            validSessionProvider = validSessionProvider
        )
    }

    DisposableEffect(messagePacketProcessor, messageOutboxWorker, unreadBadgeController) {
        unreadBadgeController.start()
        messagePacketProcessor.start()
        messageOutboxWorker.start()
        onDispose {
            unreadBadgeController.stop()
            messagePacketProcessor.stop()
            messageOutboxWorker.stop()
        }
    }

    LaunchedEffect(session.userId, pushTokenRepository) {
        validSessionProvider()?.let { validSession ->
            pushTokenRepository.register(validSession.accessToken, session.userId)
        }
    }

    DisposableEffect(context, session.userId) {
        PushPollScheduler.schedule(context.applicationContext, session.userId)
        onDispose {
            PushPollScheduler.cancel(context.applicationContext, session.userId)
        }
    }

    LaunchedEffect(pushDeepLink, session.userId) {
        val conversationId = pushDeepLink
        if (!conversationId.isNullOrBlank()) {
            openPreloadedChat(conversationId)
            onPushDeepLinkConsumed()
        }
    }

    LaunchedEffect(messageRepository, messageAlertController) {
        messageRepository.messageAlerts.collect(messageAlertController::show)
    }

    DisposableEffect(lifecycleOwner, messageAlertController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                messageAlertController.dismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = ByteImColors.AppBackground
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = SelfHostedImRoute.Conversations.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
            ) {
            composable(SelfHostedImRoute.Conversations.route) {
                val conversationState by conversationListViewModel.state.collectAsState()
                Column(modifier = Modifier.fillMaxSize()) {
                    ConversationListScreen(
                        viewModel = conversationListViewModel,
                        state = conversationState,
                        unreadCount = unreadMessagesCount,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onStartGroupChat = {
                            navController.navigate(SelfHostedImRoute.GroupCreate.route) {
                                launchSingleTop = true
                            }
                        },
                        onOpenConversation = { conversationId ->
                            openPreloadedChat(conversationId)
                        }
                    )
                    TopLevelBottomBar(
                        currentRoute = currentRoute,
                        unreadMessagesCount = unreadMessagesCount,
                        onNavigateToTab = { tabRoute ->
                            navController.navigateToTopLevelTab(tabRoute)
                        }
                    )
                }
                TopLevelRouteBackHandler(
                    route = SelfHostedImRoute.Conversations.route,
                    currentRoute = currentRoute,
                    activity = activity
                )
            }

            composable(SelfHostedImRoute.Contacts.route) {
                val contactState by contactListViewModel.state.collectAsState()
                Column(modifier = Modifier.fillMaxSize()) {
                    ContactListScreen(
                        viewModel = contactListViewModel,
                        state = contactState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onStartGroupChat = {
                            navController.navigate(SelfHostedImRoute.GroupCreate.route) {
                                launchSingleTop = true
                            }
                        },
                        onOpenContact = { peerUserId ->
                            SelfHostedImRoute.ContactProfile.createRoute(peerUserId)?.let { navController.navigate(it) }
                        },
                        onOpenJoinedGroups = {
                            navController.navigate(SelfHostedImRoute.JoinedGroups.route) {
                                launchSingleTop = true
                            }
                        }
                    )
                    TopLevelBottomBar(
                        currentRoute = currentRoute,
                        unreadMessagesCount = unreadMessagesCount,
                        onNavigateToTab = { tabRoute ->
                            navController.navigateToTopLevelTab(tabRoute)
                        }
                    )
                }
                TopLevelRouteBackHandler(
                    route = SelfHostedImRoute.Contacts.route,
                    currentRoute = currentRoute,
                    activity = activity
                )
            }

            composable(SelfHostedImRoute.GroupCreate.route) {
                val groupCreateViewModel = remember(session.userId) {
                    GroupCreateViewModel(
                        session = session,
                        profileRepository = profileRepository,
                        groupRepository = groupRepository,
                        contactRepository = contactRepository,
                        validSessionProvider = validSessionProvider
                    )
                }
                val groupCreateState by groupCreateViewModel.state.collectAsState()
                GroupCreateScreen(
                    viewModel = groupCreateViewModel,
                    state = groupCreateState,
                    onBack = { navController.popBackStack() },
                    onCreated = { conversationId ->
                        GroupCreateNavigationPolicy.destinationAfterCreated(conversationId)
                            ?.let(navController::navigateToChat)
                    }
                )
            }

            composable(SelfHostedImRoute.JoinedGroups.route) {
                val joinedGroupsViewModel = remember(session.userId) {
                    JoinedGroupsViewModel(
                        session = session,
                        groupRepository = groupRepository,
                        validSessionProvider = validSessionProvider
                    )
                }
                val joinedGroupsState by joinedGroupsViewModel.state.collectAsState()
                JoinedGroupsScreen(
                    viewModel = joinedGroupsViewModel,
                    state = joinedGroupsState,
                    onBack = { navController.popBackStack() },
                    onOpenGroup = { groupId ->
                        openPreloadedChat("group:$groupId")
                    }
                )
            }

            composable(SelfHostedImRoute.Me.route) {
                val meViewModel = remember(session.userId) {
                    MeViewModel(
                        session = session,
                        profileRepository = profileRepository,
                        avatarUploadApi = avatarUploadApi,
                        validSessionProvider = validSessionProvider
                    )
                }
                val meState by meViewModel.state.collectAsState()
                Column(modifier = Modifier.fillMaxSize()) {
                    MeScreen(
                        viewModel = meViewModel,
                        state = meState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onMoveTaskToBack = {
                            activity?.moveTaskToBack(true)
                        },
                        onLogout = onLogout
                    )
                    TopLevelBottomBar(
                        currentRoute = currentRoute,
                        unreadMessagesCount = unreadMessagesCount,
                        onNavigateToTab = { tabRoute ->
                            navController.navigateToTopLevelTab(tabRoute)
                        }
                    )
                }
            }

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
                        // self-to-self 是占位:目前不实现"自己给自己发消息",按钮先放着。
                        if (peerUserId != session.userId) {
                            openPreloadedChat(messageRepository.conversationIdFor(session.userId, peerUserId))
                        }
                    }
                )
            }

            composable(route = SelfHostedImRoute.Chat.pattern) { chatBackStackEntry ->
                val conversationId = chatBackStackEntry.arguments
                    ?.getString(SelfHostedImRoute.Chat.CONVERSATION_ID_ARG)
                    .orEmpty()
                val peerUserId = conversationId.peerIdForCurrentSession(session.userId)
                val chatViewModel = remember(session.userId, conversationId) {
                    ChatViewModel(
                        session = session,
                        repository = messageRepository,
                        connection = connection,
                        profileRepository = profileRepository,
                        groupRepository = groupRepository,
                        initialPeerId = peerUserId,
                        imageUploadApi = imageUploadApi,
                        validSessionProvider = validSessionProvider
                    )
                }
                val chatState by chatViewModel.state.collectAsState()
                ChatScreen(
                    viewModel = chatViewModel,
                    state = chatState,
                    onBack = {
                        ChatBackPolicy.run(navigateBack = { navController.popBackStack() })
                    },
                    onOpenUserProfile = { userId ->
                        SelfHostedImRoute.ContactProfile.createRoute(userId)
                            ?.let(navController::navigate)
                    },
                    onOpenGroupInfo = {
                        SelfHostedImRoute.GroupInfo.createRoute(
                            conversationId.removePrefix("group:")
                        )?.let(navController::navigate)
                    }
                )
            }

            composable(route = SelfHostedImRoute.GroupInfo.pattern) { entry ->
                val groupId = entry.arguments
                    ?.getString(SelfHostedImRoute.GroupInfo.GROUP_ID_ARG)
                    .orEmpty()
                if (groupId.isBlank()) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                val groupInfoViewModel = remember(session.userId, groupId) {
                    GroupInfoViewModel(
                        groupId = groupId,
                        session = session,
                        groupRepository = groupRepository,
                        profileRepository = profileRepository,
                        validSessionProvider = validSessionProvider
                    )
                }
                val groupInfoState by groupInfoViewModel.state.collectAsState()
                GroupInfoScreen(
                    viewModel = groupInfoViewModel,
                    state = groupInfoState,
                    onBack = { navController.popBackStack() },
                    onOpenUserProfile = { userId ->
                        SelfHostedImRoute.ContactProfile.createRoute(userId)
                            ?.let(navController::navigate)
                    }
                )
            }
            }
            MessageAlertHost(
                controller = messageAlertController,
                onOpenConversation = { conversationId ->
                    openPreloadedChat(conversationId)
                },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

private fun String.peerIdForCurrentSession(currentUserId: String): String {
    val parts = split(":")
    if (parts.size == 3 && parts[0] == "single") {
        return when (currentUserId) {
            parts[1] -> parts[2]
            parts[2] -> parts[1]
            else -> this
        }
    }
    return this
}

private fun NavHostController.navigateToTopLevelTab(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
    }
}

private fun NavHostController.navigateToChat(route: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo(graph.findStartDestination().id) {
            saveState = false
        }
    }
}

@Composable
private fun TopLevelRouteBackHandler(
    route: String,
    currentRoute: String?,
    activity: Activity?
) {
    BackHandler(enabled = currentRoute == route && TopLevelBackPolicy.shouldMoveTaskToBack(route)) {
        activity?.moveTaskToBack(true)
    }
}

@Composable
private fun TopLevelBottomBar(
    currentRoute: String?,
    unreadMessagesCount: Int,
    onNavigateToTab: (String) -> Unit
) {
    NavigationBar(
        containerColor = ByteImColors.Surface,
        tonalElevation = 0.dp,
        modifier = Modifier.height(ByteImDimensions.BottomBarHeight)
    ) {
        BottomNavigationSpec.topLevelItems.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onNavigateToTab(tab.route) },
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

@Composable
private fun BottomNavigationIcon(spec: BottomNavigationItemSpec, unreadCount: Int = 0) {
    val badgeText = MessagesTabUnreadBadgePolicy.badgeTextForCount(unreadCount)
    val iconContent: @Composable () -> Unit = {
        Image(
            painter = painterResource(id = spec.iconResId),
            contentDescription = spec.label,
            modifier = Modifier.size(24.dp)
        )
    }
    if (badgeText == null) {
        iconContent()
        return
    }
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
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
