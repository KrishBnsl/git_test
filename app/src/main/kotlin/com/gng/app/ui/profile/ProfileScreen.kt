package com.gng.app.ui.profile

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.gng.app.data.Profile
import com.gng.app.data.ProfileRepository
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.Choice
import com.gng.app.ui.components.GngDatePickerDialog
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.SegmentedChoice
import com.gng.app.ui.components.TonalIcon
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
import com.gng.app.util.AvatarImage
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** What the form hands back when the person taps save. */
data class ProfileDraft(
    val name: String,
    val branch: String,
    val year: Int?,
    val birthday: String?,
    val status: String,
    val newPhoto: Uri?,
    val removePhoto: Boolean,
)

class ProfileViewModel(
    private val repo: ProfileRepository,
    private val userId: String,
    private val existing: Profile?,
) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun dismissError() { error = null }

    fun save(context: Context, draft: ProfileDraft, onSaved: (Profile) -> Unit) {
        if (busy) return
        busy = true
        error = null
        val appContext = context.applicationContext
        viewModelScope.launch {
            runCatching {
                var avatarUrl = if (draft.removePhoto) null else existing?.avatarUrl
                draft.newPhoto?.let { uri ->
                    avatarUrl = repo.uploadAvatar(userId, AvatarImage.squareJpeg(appContext, uri))
                }
                val profile = Profile(
                    id = userId,
                    displayName = draft.name.trim(),
                    avatarUrl = avatarUrl,
                    branch = draft.branch.trim().ifBlank { null },
                    year = draft.year,
                    birthday = draft.birthday,
                    statusText = draft.status.trim().ifBlank { null },
                )
                repo.save(profile)
                profile
            }.onSuccess(onSaved).onFailure { error = it.friendly() }
            busy = false
        }
    }
}

@Composable
fun ProfileScreen(userId: String, existing: Profile?, onSaved: (Profile) -> Unit, onBack: (() -> Unit)? = null) {
    val vm = gngViewModel(key = "profile-$userId") { ProfileViewModel(it.profiles, userId, existing) }
    val context = LocalContext.current
    ProfileForm(
        existing = existing, busy = vm.busy, error = vm.error, onDismissError = vm::dismissError, onBack = onBack,
        onSave = { draft -> vm.save(context, draft, onSaved) },
    )
}

private val BIRTHDAY_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileForm(
    existing: Profile?,
    busy: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onSave: (ProfileDraft) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var name by rememberSaveable { mutableStateOf(existing?.displayName ?: "") }
    var branch by rememberSaveable { mutableStateOf(existing?.branch ?: "") }
    var year by rememberSaveable { mutableStateOf(existing?.year) }
    var birthday by rememberSaveable { mutableStateOf(existing?.birthday) }
    var status by rememberSaveable { mutableStateOf(existing?.statusText ?: "") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var removePhoto by rememberSaveable { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            photo = uri
            removePhoto = false
        }
    }
    LaunchedEffect(error) {
        if (error != null) {
            snackbar.showSnackbar(error)
            onDismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text("Edit profile") },
                    navigationIcon = { IconButton(onClick = onBack) { GngIcon(GngIcons.ArrowBack, "Back") } },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .let { if (onBack == null) it.systemBarsPadding() else it }
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            if (onBack == null) {
                Text("Let's set you up", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "This is how the gang will see you.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clickable {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    val picked = photo
                    if (picked != null) {
                        AsyncImage(
                            model = picked, contentDescription = "Your photo", contentScale = ContentScale.Crop,
                            modifier = Modifier.size(112.dp).clip(CircleShape),
                        )
                    } else if (name.isBlank() && (removePhoto || existing?.avatarUrl == null)) {
                        // Nothing to show yet: a neutral placeholder instead of an initial.
                        TonalIcon(
                            GngIcons.PersonFill, size = 112.dp,
                            container = scheme.primaryContainer, content = scheme.onPrimaryContainer,
                        )
                    } else {
                        Avatar(
                            name.ifBlank { existing?.displayName ?: "?" },
                            if (removePhoto) null else existing?.avatarUrl,
                            size = 112.dp, colorKey = existing?.id ?: name,
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(40.dp)
                            .background(scheme.surface, CircleShape)
                            .padding(3.dp)
                            .background(scheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { GngIcon(GngIcons.AddAPhoto, "Change photo", tint = scheme.onPrimary, size = 20.dp) }
                }
                if (photo != null || (!removePhoto && existing?.avatarUrl != null)) {
                    TextButton(onClick = { photo = null; removePhoto = true }) { Text("Remove photo") }
                } else {
                    Spacer(Modifier.height(16.dp))
                }
            }

            OutlinedTextField(
                name, { name = it.take(40) }, label = { Text("What the gang calls you") }, singleLine = true,
                leadingIcon = { GngIcon(GngIcons.Person, null) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                branch, { branch = it.take(40) }, label = { Text("Branch") }, placeholder = { Text("CSE, ECE, ME…") },
                singleLine = true,
                leadingIcon = { GngIcon(GngIcons.School, null) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text("Year", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            SegmentedChoice(
                choices = listOf(Choice(1, "1st"), Choice(2, "2nd"), Choice(3, "3rd"), Choice(4, "4th")),
                selected = year ?: 0, onSelect = { year = it }, checkIcon = GngIcons.Check,
            )
            Spacer(Modifier.height(16.dp))
            Box {
                OutlinedTextField(
                    value = birthday?.let { LocalDate.parse(it).format(BIRTHDAY_FORMAT) } ?: "",
                    onValueChange = {}, readOnly = true,
                    label = { Text("Birthday") },
                    placeholder = { Text("So the gang can celebrate") },
                    leadingIcon = { GngIcon(GngIcons.Cake, null) },
                    trailingIcon = { GngIcon(GngIcons.CalendarMonth, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable { pickDate = true })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                status, { status = it.take(80) }, label = { Text("Status (optional)") },
                placeholder = { Text("surviving on maggi") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    onSave(ProfileDraft(name, branch, year, birthday, status, photo, removePhoto))
                },
                enabled = name.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = scheme.onPrimary)
                else Text(if (existing == null) "Continue" else "Save", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (pickDate) {
        GngDatePickerDialog(
            initial = birthday?.let(LocalDate::parse) ?: LocalDate.now().minusYears(19),
            onDismiss = { pickDate = false },
        ) { birthday = it.toString(); pickDate = false }
    }
}
