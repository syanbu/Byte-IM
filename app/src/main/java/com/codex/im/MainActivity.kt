package com.codex.im

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.codex.im.app.AppInfo
import com.codex.im.app.MockServerConfig
import com.codex.im.auth.AuthRepository
import com.codex.im.auth.AuthSession
import com.codex.im.auth.LoginScreen
import com.codex.im.auth.LoginViewModel
import com.codex.im.auth.OkHttpAuthApi
import com.codex.im.auth.SharedPreferencesTokenStore
import com.codex.im.auth.ValidSessionProvider
import com.codex.im.chat.ChatScreen
import com.codex.im.chat.ChatViewModel
import com.codex.im.connection.OkHttpImConnection
import com.codex.im.connection.ConnectionLifecycleManager
import com.codex.im.connection.ImConnection
import com.codex.im.contacts.ContactListScreen
import com.codex.im.contacts.ContactListViewModel
import com.codex.im.contacts.DemoContactResolver
import com.codex.im.conversation.ConversationListScreen
import com.codex.im.conversation.ConversationListViewModel
import com.codex.im.group.GroupCreateScreen
import com.codex.im.group.GroupCreateNavigationPolicy
import com.codex.im.group.GroupCreateViewModel
import com.codex.im.group.DefaultGroupRepository
import com.codex.im.group.OkHttpGroupApi
import com.codex.im.message.AndroidChatThumbnailCache
import com.codex.im.message.CoilChatThumbnailPreloader
import com.codex.im.message.MessageIdGenerator
import com.codex.im.message.MessageOutboxWorker
import com.codex.im.message.MessagePacketProcessor
import com.codex.im.message.MessageRepository
import com.codex.im.message.ImageUploadApi
import com.codex.im.message.OkHttpImageUploadApi
import com.codex.im.message.SeqGenerator
import com.codex.im.profile.MeScreen
import com.codex.im.profile.MeViewModel
import com.codex.im.profile.OkHttpAvatarUploadApi
import com.codex.im.profile.OkHttpProfileApi
import com.codex.im.profile.AvatarUploadApi
import com.codex.im.profile.ProfileRepository
import com.codex.im.storage.AndroidConversationDao
import com.codex.im.storage.AndroidGroupDao
import com.codex.im.storage.AndroidMessageDao
import com.codex.im.storage.AndroidPendingMessageDao
import com.codex.im.storage.AndroidTransactionRunner
import com.codex.im.storage.AndroidUserProfileDao
import com.codex.im.storage.ImDatabaseHelper
import com.codex.im.ui.ByteImColors

class MainActivity : ComponentActivity() {
    private var connectionLifecycleManager: ConnectionLifecycleManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val database = ImDatabaseHelper(this).writableDatabase
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
        val messageRepository = MessageRepository(
            messageDao = AndroidMessageDao(database),
            conversationDao = AndroidConversationDao(database),
            pendingMessageDao = AndroidPendingMessageDao(database),
            connection = connection,
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator(),
            transactionRunner = AndroidTransactionRunner(database),
            thumbnailCache = AndroidChatThumbnailCache(this)
        )
        val profileRepository = ProfileRepository(
            userProfileDao = AndroidUserProfileDao(database),
            profileApi = OkHttpProfileApi(baseUrl = mockServerConfig.httpBaseUrl)
        )
        val groupRepository = DefaultGroupRepository(
            groupApi = OkHttpGroupApi(baseUrl = mockServerConfig.httpBaseUrl),
            groupDao = AndroidGroupDao(database),
            conversationDao = AndroidConversationDao(database)
        )
        setContent {
            val loginViewModel = remember { LoginViewModel(repository) }
            SelfHostedImApp(
                loginViewModel = loginViewModel,
                validSessionProvider = repository::ensureValidSession,
                messageRepository = messageRepository,
                connection = connection,
                profileRepository = profileRepository,
                groupRepository = groupRepository,
                avatarUploadApi = OkHttpAvatarUploadApi(baseUrl = mockServerConfig.httpBaseUrl),
                imageUploadApi = OkHttpImageUploadApi(baseUrl = mockServerConfig.httpBaseUrl)
            )
        }
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
    messageRepository: MessageRepository? = null,
    connection: ImConnection? = null,
    profileRepository: ProfileRepository? = null,
    groupRepository: com.codex.im.group.GroupRepository? = null,
    avatarUploadApi: AvatarUploadApi? = null,
    imageUploadApi: ImageUploadApi? = null
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
                Text(text = "Project skeleton ready", style = MaterialTheme.typography.bodyLarge)
            }
            } else {
            val state by loginViewModel.state.collectAsState()
            LaunchedEffect(loginViewModel) {
                loginViewModel.restoreSession()
            }
            val session = state.session
            if (state.isAuthenticated && session != null && validSessionProvider != null && messageRepository != null && connection != null && profileRepository != null && groupRepository != null && avatarUploadApi != null && imageUploadApi != null) {
                AuthenticatedImNavHost(
                    session = session,
                    validSessionProvider = validSessionProvider,
                    messageRepository = messageRepository,
                    connection = connection,
                    profileRepository = profileRepository,
                    groupRepository = groupRepository,
                    avatarUploadApi = avatarUploadApi,
                    imageUploadApi = imageUploadApi,
                    onLogout = {
                        messageRepository.closeConversation()
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

@Composable
private fun AuthenticatedImNavHost(
    session: AuthSession,
    validSessionProvider: ValidSessionProvider,
    messageRepository: MessageRepository,
    connection: ImConnection,
    profileRepository: ProfileRepository,
    groupRepository: com.codex.im.group.GroupRepository,
    avatarUploadApi: AvatarUploadApi,
    imageUploadApi: ImageUploadApi,
    onLogout: suspend () -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val activity = LocalContext.current.findActivity()
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (BottomNavigationSpec.topLevelItems.any { it.route == currentRoute }) {
                NavigationBar {
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
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            composable(SelfHostedImRoute.Conversations.route) {
                val conversationListViewModel = remember(session.userId) {
                    ConversationListViewModel(
                        session = session,
                        repository = messageRepository,
                        connection = connection,
                        profileRepository = profileRepository,
                        groupRepository = groupRepository,
                        validSessionProvider = validSessionProvider,
                        thumbnailPreloader = CoilChatThumbnailPreloader(context)
                    )
                }
                val conversationState by conversationListViewModel.state.collectAsState()
                ConversationListScreen(
                    viewModel = conversationListViewModel,
                    state = conversationState,
                    unreadCount = unreadMessagesCount,
                    onStartGroupChat = {
                        navController.navigate(SelfHostedImRoute.GroupCreate.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenConversation = { conversationId ->
                        SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
                    }
                )
                TopLevelRouteBackHandler(
                    route = SelfHostedImRoute.Conversations.route,
                    currentRoute = currentRoute,
                    activity = activity
                )
            }

            composable(SelfHostedImRoute.Contacts.route) {
                val contactListViewModel = remember(session.userId) {
                    ContactListViewModel(
                        session = session,
                        profileRepository = profileRepository,
                        contactResolver = DemoContactResolver::contactsFor,
                        validSessionProvider = validSessionProvider
                    )
                }
                val contactState by contactListViewModel.state.collectAsState()
                ContactListScreen(
                    viewModel = contactListViewModel,
                    state = contactState,
                    onOpenContact = { peerUserId ->
                        SelfHostedImRoute.Chat.createSingleRoute(session.userId, peerUserId)?.let(navController::navigateToChat)
                    }
                )
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
                        contactResolver = DemoContactResolver::contactsFor,
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
                MeScreen(
                    viewModel = meViewModel,
                    state = meState,
                    onMoveTaskToBack = {
                        activity?.moveTaskToBack(true)
                    },
                    onLogout = onLogout
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
                    }
                )
            }
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
        restoreState = false
        popUpTo(graph.findStartDestination().id) {
            saveState = false
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
            Badge {
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
