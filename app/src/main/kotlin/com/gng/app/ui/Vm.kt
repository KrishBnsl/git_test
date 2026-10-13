package com.gng.app.ui

import androidx.compose.runtime.Composable
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gng.app.AppContainer
import com.gng.app.container

/** `val vm = gngViewModel { ChatViewModel(it.chat) }` */
@Composable
inline fun <reified VM : ViewModel> gngViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.container
    return viewModel(key = key) { create(container) }
}

/** A view model that outlives navigation, for state shared across screens (like the unread badge). */
@Composable
inline fun <reified VM : ViewModel> gngActivityViewModel(key: String, crossinline create: (AppContainer) -> VM): VM {
    val context = LocalContext.current
    val container = context.container
    val owner = LocalActivity.current as ViewModelStoreOwner
    return viewModel(viewModelStoreOwner = owner, key = key) { create(container) }
}

/** Turns exceptions from Supabase into something you'd say to a friend. */
fun Throwable.friendly(): String {
    val raw = message.orEmpty()
    return when {
        "Invalid login credentials" in raw -> "Wrong email or password"
        "User already registered" in raw -> "That email already has an account, try logging in"
        "no group with that code" in raw -> "No gang with that code. Typo?"
        "you can only message people in your gangs" in raw -> "You can only message people in your gangs"
        "row-level security" in raw -> "You're not allowed to do that here"
        "Unable to resolve host" in raw || "timeout" in raw.lowercase() -> "You seem offline"
        raw.isBlank() -> "Something broke"
        else -> raw.lineSequence().first().take(120)
    }
}
