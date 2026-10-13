package com.gng.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.R
import com.gng.app.data.AuthRepository
import com.gng.app.ui.components.Choice
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.SegmentedChoice
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
import kotlinx.coroutines.launch

class AuthViewModel(private val auth: AuthRepository) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set

    fun clearMessages() {
        error = null
        info = null
    }

    fun submit(signUp: Boolean, email: String, password: String) {
        if (busy) return
        busy = true
        clearMessages()
        viewModelScope.launch {
            runCatching {
                if (signUp) auth.signUp(email.trim(), password) else auth.signIn(email.trim(), password)
            }.onSuccess {
                // With email confirmation on, sign-up doesn't log you in until you click the link.
                if (signUp && auth.userId == null) info = "Check your inbox to confirm your email, then log in."
            }.onFailure { error = it.friendly() }
            busy = false
        }
    }
}

@Composable
fun AuthScreen() {
    val vm = gngViewModel { AuthViewModel(it.auth) }
    var signUp by rememberSaveable { mutableStateOf(true) }
    AuthContent(
        signUp = signUp, onModeChange = { signUp = it; vm.clearMessages() },
        busy = vm.busy, error = vm.error, info = vm.info,
        onSubmit = { email, password -> vm.submit(signUp, email, password) },
    )
}

@Composable
fun AuthContent(
    signUp: Boolean,
    onModeChange: (Boolean) -> Unit,
    busy: Boolean,
    error: String?,
    info: String?,
    onSubmit: (email: String, password: String) -> Unit,
    initialEmail: String = "",
) {
    val scheme = MaterialTheme.colorScheme
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            Modifier.size(80.dp).background(scheme.primary, MaterialTheme.shapes.extraLarge),
            contentAlignment = Alignment.Center,
        ) {
            // The mark has generous padding inside its 108 unit canvas, so draw it larger than the tile.
            Icon(painterResource(R.drawable.ic_logo_mark), null, Modifier.requiredSize(112.dp), tint = scheme.onPrimary)
        }
        Spacer(Modifier.height(24.dp))
        Text("gng", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Your college gang, in one place.",
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        SegmentedChoice(
            choices = listOf(Choice(true, "Sign up"), Choice(false, "Log in")),
            selected = signUp, onSelect = onModeChange, checkIcon = GngIcons.Check,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") }, singleLine = true,
            leadingIcon = { GngIcon(GngIcons.Mail, null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Password") }, singleLine = true,
            leadingIcon = { GngIcon(GngIcons.Lock, null) },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    GngIcon(if (showPassword) GngIcons.VisibilityOff else GngIcons.Visibility, if (showPassword) "Hide password" else "Show password")
                }
            },
            supportingText = if (signUp) ({ Text("At least 6 characters") }) else null,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().background(scheme.errorContainer, MaterialTheme.shapes.medium).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GngIcon(GngIcons.Info, null, tint = scheme.onErrorContainer, size = 20.dp)
                Text(error, color = scheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
            }
        }
        if (info != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().background(scheme.tertiaryContainer, MaterialTheme.shapes.medium).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GngIcon(GngIcons.Mail, null, tint = scheme.onTertiaryContainer, size = 20.dp)
                Text(info, color = scheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onSubmit(email, password) },
            enabled = !busy && email.contains('@') && password.length >= 6,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = scheme.onPrimary)
            else Text(if (signUp) "Create account" else "Log in", style = MaterialTheme.typography.labelLarge)
        }
    }
}
