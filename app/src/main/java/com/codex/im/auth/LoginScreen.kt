package com.codex.im.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.codex.im.app.AppInfo
import com.codex.im.ui.ByteImColors
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    state: LoginUiState,
    onLogin: suspend (String, String) -> Unit,
    onRegister: suspend (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var localErrorMessage by remember { mutableStateOf<String?>(null) }
    var isRegisterMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val canLogin = phone.isNotBlank() && password.isNotBlank() && !state.isLoading
    val canRegister = phone.isNotBlank() && password.isNotBlank() && confirmPassword.isNotBlank() && !state.isLoading

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
                    "使用中国大陆手机号注册"
                } else {
                    "使用中国大陆手机号登录"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ByteImColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手机号") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ByteImColors.PrimaryGreen,
                    focusedLabelColor = ByteImColors.PrimaryGreen,
                    cursorColor = ByteImColors.PrimaryGreen
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    localErrorMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ByteImColors.PrimaryGreen,
                    focusedLabelColor = ByteImColors.PrimaryGreen,
                    cursorColor = ByteImColors.PrimaryGreen
                )
            )
            if (isRegisterMode) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        localErrorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("确认密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ByteImColors.PrimaryGreen,
                        focusedLabelColor = ByteImColors.PrimaryGreen,
                        cursorColor = ByteImColors.PrimaryGreen
                    )
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (isRegisterMode) {
                Button(
                    enabled = canRegister,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ByteImColors.PrimaryGreen,
                        contentColor = Color.White
                    ),
                    onClick = {
                        val validationError = RegistrationInputValidator.validate(password, confirmPassword)
                        if (validationError != null) {
                            localErrorMessage = validationError
                        } else {
                            scope.launch { onRegister(phone, password) }
                        }
                    }
                ) {
                    Text("注册")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isRegisterMode = false
                        confirmPassword = ""
                        localErrorMessage = null
                    }
                ) {
                    Text("返回登录")
                }
            } else {
                Button(
                    enabled = canLogin,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ByteImColors.PrimaryGreen,
                        contentColor = Color.White
                    ),
                    onClick = { scope.launch { onLogin(phone, password) } }
                ) {
                    Text("登录")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isRegisterMode = true
                        localErrorMessage = null
                    }
                ) {
                    Text("创建账号")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (state.isLoading) {
                CircularProgressIndicator()
            }
            localErrorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            state.errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            state.session?.let {
                Text(text = "已登录：${it.username}", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
