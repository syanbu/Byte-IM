package com.codex.im.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "SelfHostedIM", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = if (isRegisterMode) "Create an account with a mainland China phone number" else "Sign in with a mainland China phone number",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Phone number") },
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                localErrorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
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
                label = { Text("Confirm password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (isRegisterMode) {
            Button(
                enabled = canRegister,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val validationError = RegistrationInputValidator.validate(password, confirmPassword)
                    if (validationError != null) {
                        localErrorMessage = validationError
                    } else {
                        scope.launch { onRegister(phone, password) }
                    }
                }
            ) {
                Text("Register")
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
                Text("Back to login")
            }
        } else {
            Button(
                enabled = canLogin,
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { onLogin(phone, password) } }
            ) {
                Text("Login")
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
                Text("Create account")
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
            Text(text = "Logged in as ${it.username}", color = MaterialTheme.colorScheme.primary)
        }
    }
}
