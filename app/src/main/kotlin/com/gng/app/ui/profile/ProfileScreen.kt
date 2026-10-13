package com.gng.app.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.Profile
import com.gng.app.data.ProfileRepository
import com.gng.app.ui.components.AVATAR_EMOJIS
import com.gng.app.ui.components.BouncyButton
import com.gng.app.ui.components.ChipRow
import com.gng.app.ui.components.EmojiStrip
import com.gng.app.ui.components.ErrorBanner
import com.gng.app.ui.components.pickDate
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ProfileViewModel(private val repo: ProfileRepository) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun save(profile: Profile, onSaved: (Profile) -> Unit) {
        busy = true
        error = null
        viewModelScope.launch {
            runCatching { repo.save(profile) }
                .onSuccess { onSaved(profile) }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }
}

@Composable
fun ProfileScreen(userId: String, existing: Profile?, onSaved: (Profile) -> Unit) {
    val vm = gngViewModel { ProfileViewModel(it.profiles) }
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(existing?.displayName ?: "") }
    var emoji by rememberSaveable { mutableStateOf(existing?.emoji ?: AVATAR_EMOJIS.first()) }
    var branch by rememberSaveable { mutableStateOf(existing?.branch ?: "") }
    var year by rememberSaveable { mutableStateOf(existing?.year) }
    var birthday by rememberSaveable { mutableStateOf(existing?.birthday) }
    var status by rememberSaveable { mutableStateOf(existing?.statusText ?: "") }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text(if (existing == null) "Who dis? 👀" else "Edit your vibe", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("Pick your face", style = MaterialTheme.typography.labelLarge)
        EmojiStrip(AVATAR_EMOJIS, emoji) { emoji = it }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            name, { name = it.take(40) }, label = { Text("What the gang calls you") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            branch, { branch = it.take(40) }, label = { Text("Branch (CSE, ECE, ME…)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text("Year", style = MaterialTheme.typography.labelLarge)
        ChipRow {
            (1..4).forEach { y ->
                FilterChip(selected = year == y, onClick = { year = y }, label = { Text("${y}${suffix(y)}") })
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            OutlinedButton(onClick = {
                val initial = birthday?.let(LocalDate::parse) ?: LocalDate.now().minusYears(19)
                pickDate(context, initial) { birthday = it.toString() }
            }) {
                Text(
                    birthday?.let { "🎂 " + LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy")) }
                        ?: "🎂 Add your birthday",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            status, { status = it.take(80) }, label = { Text("Status (optional)") }, singleLine = true,
            placeholder = { Text("surviving on maggi") }, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        ErrorBanner(vm.error)
        Spacer(Modifier.height(8.dp))
        BouncyButton(
            if (existing == null) "Let's go 🚀" else "Save",
            enabled = name.isNotBlank() && !vm.busy,
            onClick = {
                vm.save(
                    Profile(
                        id = userId,
                        displayName = name.trim(),
                        emoji = emoji,
                        branch = branch.trim().ifBlank { null },
                        year = year,
                        birthday = birthday,
                        statusText = status.trim().ifBlank { null },
                    ),
                    onSaved,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun suffix(n: Int) = when (n) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
