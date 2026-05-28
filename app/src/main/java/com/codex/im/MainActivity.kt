package com.codex.im

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.codex.im.app.AppInfo
import com.codex.im.auth.AuthRepository
import com.codex.im.auth.AuthSession
import com.codex.im.auth.LoginScreen
import com.codex.im.auth.LoginViewModel
import com.codex.im.auth.OkHttpAuthApi
import com.codex.im.auth.SharedPreferencesTokenStore
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
import com.codex.im.message.MessageIdGenerator
import com.codex.im.message.MessageOutboxWorker
import com.codex.im.message.MessagePacketProcessor
import com.codex.im.message.MessageRepository
import com.codex.im.message.SeqGenerator
import com.codex.im.profile.MeScreen
import com.codex.im.profile.MeViewModel
import com.codex.im.profile.OkHttpAvatarUploadApi
import com.codex.im.profile.OkHttpProfileApi
import com.codex.im.profile.AvatarUploadApi
import com.codex.im.profile.ProfileRepository
import com.codex.im.storage.AndroidConversationDao
import com.codex.im.storage.AndroidMessageDao
import com.codex.im.storage.AndroidPendingMessageDao
import com.codex.im.storage.AndroidTransactionRunner
import com.codex.im.storage.AndroidUserProfileDao
import com.codex.im.storage.ImDatabaseHelper

class MainActivity : ComponentActivity() {
    private var connectionLifecycleManager: ConnectionLifecycleManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = ImDatabaseHelper(this).writableDatabase
        val rawConnection = OkHttpImConnection(DEFAULT_MOCK_SERVER_WS_URL)
        val repository = AuthRepository(
            authApi = OkHttpAuthApi(baseUrl = DEFAULT_MOCK_SERVER_URL),
            tokenStore = SharedPreferencesTokenStore(this)
        )
        val connection = ConnectionLifecycleManager(
            connection = rawConnection,
            tokenProvider = { _ -> repository.ensureValidSession()?.accessToken }
        )
        connectionLifecycleManager = connection
        val messageRepository = MessageRepository(
            messageDao = AndroidMessageDao(database),
            conversationDao = AndroidConversationDao(database),
            pendingMessageDao = AndroidPendingMessageDao(database),
            connection = connection,
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator(),
            transactionRunner = AndroidTransactionRunner(database)
        )
        val profileRepository = ProfileRepository(
            userProfileDao = AndroidUserProfileDao(database),
            profileApi = OkHttpProfileApi(baseUrl = DEFAULT_MOCK_SERVER_URL)
        )
        setContent {
            val loginViewModel = remember { LoginViewModel(repository) }
            SelfHostedImApp(
                loginViewModel = loginViewModel,
                messageRepository = messageRepository,
                connection = connection,
                profileRepository = profileRepository,
                avatarUploadApi = OkHttpAvatarUploadApi(baseUrl = DEFAULT_MOCK_SERVER_URL)
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

    private companion object {
        const val DEFAULT_MOCK_SERVER_URL = "http://10.0.2.2:8080"
        const val DEFAULT_MOCK_SERVER_WS_URL = "ws://10.0.2.2:8080/ws"
    }
}

@Composable
fun SelfHostedImApp(
    loginViewModel: LoginViewModel? = null,
    messageRepository: MessageRepository? = null,
    connection: ImConnection? = null,
    profileRepository: ProfileRepository? = null,
    avatarUploadApi: AvatarUploadApi? = null
) {
    MaterialTheme {
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
            if (state.isAuthenticated && session != null && messageRepository != null && connection != null && profileRepository != null && avatarUploadApi != null) {
                AuthenticatedImNavHost(
                    session = session,
                    messageRepository = messageRepository,
                    connection = connection,
                    profileRepository = profileRepository,
                    avatarUploadApi = avatarUploadApi,
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

@Composable
private fun AuthenticatedImNavHost(
    session: AuthSession,
    messageRepository: MessageRepository,
    connection: ImConnection,
    profileRepository: ProfileRepository,
    avatarUploadApi: AvatarUploadApi,
    onLogout: suspend () -> Unit
) {
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
                        profileRepository = profileRepository
                    )
                }
                val conversationState by conversationListViewModel.state.collectAsState()
                ConversationListScreen(
                    viewModel = conversationListViewModel,
                    state = conversationState,
                    onOpenConversation = { peerUserId ->
                        SelfHostedImRoute.Chat.createRoute(peerUserId)?.let(navController::navigateToChat)
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
                        contactResolver = DemoContactResolver::contactsFor
                    )
                }
                val contactState by contactListViewModel.state.collectAsState()
                ContactListScreen(
                    viewModel = contactListViewModel,
                    state = contactState,
                    onOpenContact = { peerUserId ->
                        SelfHostedImRoute.Chat.createRoute(peerUserId)?.let(navController::navigateToChat)
                    }
                )
                TopLevelRouteBackHandler(
                    route = SelfHostedImRoute.Contacts.route,
                    currentRoute = currentRoute,
                    activity = activity
                )
            }

            composable(SelfHostedImRoute.Me.route) {
                val meViewModel = remember(session.userId) {
                    MeViewModel(
                        session = session,
                        profileRepository = profileRepository,
                        avatarUploadApi = avatarUploadApi
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
                val peerUserId = chatBackStackEntry.arguments
                    ?.getString(SelfHostedImRoute.Chat.PEER_USER_ID_ARG)
                    .orEmpty()
                val chatViewModel = remember(session.userId, peerUserId) {
                    ChatViewModel(
                        session = session,
                        repository = messageRepository,
                        connection = connection,
                        profileRepository = profileRepository,
                        initialPeerId = peerUserId
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
