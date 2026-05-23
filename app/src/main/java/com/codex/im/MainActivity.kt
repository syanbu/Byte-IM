package com.codex.im

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codex.im.app.AppInfo
import com.codex.im.auth.AuthRepository
import com.codex.im.auth.LoginScreen
import com.codex.im.auth.LoginViewModel
import com.codex.im.auth.OkHttpAuthApi
import com.codex.im.auth.SharedPreferencesTokenStore
import com.codex.im.chat.ChatScreen
import com.codex.im.chat.ChatViewModel
import com.codex.im.connection.OkHttpImConnection
import com.codex.im.message.MessageIdGenerator
import com.codex.im.message.MessageRepository
import com.codex.im.message.SeqGenerator
import com.codex.im.storage.AndroidConversationDao
import com.codex.im.storage.AndroidMessageDao
import com.codex.im.storage.AndroidPendingMessageDao
import com.codex.im.storage.ImDatabaseHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = ImDatabaseHelper(this).writableDatabase
        val connection = OkHttpImConnection(DEFAULT_MOCK_SERVER_WS_URL)
        val repository = AuthRepository(
            authApi = OkHttpAuthApi(baseUrl = DEFAULT_MOCK_SERVER_URL),
            tokenStore = SharedPreferencesTokenStore(this)
        )
        val messageRepository = MessageRepository(
            messageDao = AndroidMessageDao(database),
            conversationDao = AndroidConversationDao(database),
            pendingMessageDao = AndroidPendingMessageDao(database),
            connection = connection,
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )
        setContent {
            val loginViewModel = remember { LoginViewModel(repository) }
            SelfHostedImApp(
                loginViewModel = loginViewModel,
                messageRepository = messageRepository,
                connection = connection
            )
        }
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
    connection: OkHttpImConnection? = null
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
            if (state.isAuthenticated && session != null && messageRepository != null && connection != null) {
                val defaultPeer = DefaultPeerResolver.resolve(session.userId)
                val chatViewModel = remember(session.userId) {
                    ChatViewModel(
                        session = session,
                        repository = messageRepository,
                        connection = connection,
                        initialPeerId = defaultPeer
                    )
                }
                val chatState by chatViewModel.state.collectAsState()
                ChatScreen(
                    session = session,
                    viewModel = chatViewModel,
                    state = chatState,
                    onLogout = {
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
