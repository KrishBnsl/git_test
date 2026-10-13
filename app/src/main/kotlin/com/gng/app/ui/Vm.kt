package com.gng.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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

/** Turns exceptions from Supabase into something you'd say to a friend. */
fun Throwable.friendly(): String {
    val raw = message.orEmpty()
    return when {
        "Invalid login credentials" in raw -> "Wrong email or password"
        "User already registered" in raw -> "That email already has an account, try logging in"
        "no group with that code" in raw -> "No gang with that code. Typo?"
        "Unable to resolve host" in raw || "timeout" in raw.lowercase() -> "You seem offline"
        raw.isBlank() -> "Something broke"
        else -> raw.lineSequence().first().take(120)
    }
}
