package com.gng.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.AuthRepository
import com.gng.app.ui.components.BouncyButton
import com.gng.app.ui.components.ErrorBanner
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import kotlinx.coroutines.launch

class AuthViewModel(private val auth: AuthRepository) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set

    fun submit(signUp: Boolean, email: String, password: String) {
        if (busy) return
        busy = true
        error = null
        info = null
        viewModelScope.launch {
            runCatching {
                if (signUp) auth.signUp(email.trim(), password) else auth.signIn(email.trim(), password)
            }.onSuccess {
                // With email confirmation on, sign-up doesn't log you in until you click the link.
                if (signUp && auth.userId == null) info = "📬 Check your inbox to confirm, then log in."
            }.onFailure { error = it.friendly() }
            busy = false
        }
    }
}

@Composable
fun AuthScreen() {
    val vm = gngViewModel { AuthViewModel(it.auth) }
    var signUp by rememberSaveable { mutableStateOf(true) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🫶", fontSize = 64.sp)
        Text("gng", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "the group chat that actually knows where lunch is",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        ErrorBanner(vm.error)
        vm.info?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        Spacer(Modifier.height(8.dp))
        BouncyButton(
            if (vm.busy) "…" else if (signUp) "Join the gang" else "Let me in",
            onClick = { vm.submit(signUp, email, password) },
            enabled = !vm.busy && email.contains('@') && password.length >= 6,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { signUp = !signUp }) {
            Text(if (signUp) "Already have an account? Log in" else "New here? Sign up")
        }
    }
}
