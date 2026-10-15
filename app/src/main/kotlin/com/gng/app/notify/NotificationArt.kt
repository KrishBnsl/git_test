package com.gng.app.notify

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.core.app.Person
import androidx.core.content.FileProvider
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.gng.app.MainActivity
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.min

/**
 * The pictures that make notifications look like a real messenger's: people's faces, the chat's
 * photo, and photos sent in the chat.
 */
object NotificationArt {
    private const val SIZE = 192

    /** Same hues as the app's avatars, roughly; good on light and dark shades. */
    private val COLORS = intArrayOf(
        0xFF2F8CF0.toInt(), 0xFF00A884.toInt(), 0xFF7C5CFA.toInt(), 0xFFF07B2F.toInt(),
        0xFFE8506E.toInt(), 0xFF1E9BB0.toInt(), 0xFFB5651D.toInt(), 0xFF5B8C2A.toInt(),
    )

    /** Someone's photo as a circle, or their initials on a colour. Never waits more than a moment for the photo. */
    suspend fun avatar(context: Context, name: String, url: String?, key: String): Bitmap {
        url?.let { u -> withTimeoutOrNull(900) { load(context, u) }?.let { return circle(it) } }
        return initials(name, key)
    }

    private suspend fun load(context: Context, url: String): Bitmap? {
        val request = ImageRequest.Builder(context).data(url).size(SIZE).allowHardware(false).build()
        return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    private fun circle(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val side = min(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        Canvas(out).drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        })
        return out
    }

    private fun initials(name: String, key: String): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLORS[Math.floorMod(key.hashCode(), COLORS.size)]
        })
        val letters = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = SIZE * 0.4f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(letters, SIZE / 2f, SIZE / 2f - (text.ascent() + text.descent()) / 2, text)
        return out
    }

    /**
     * A decrypted photo the notification can show inline (through the FileProvider; Android's
     * notification shade is allowed to read it).
     */
    fun imageUri(context: Context, decrypted: File): Uri {
        val dir = File(context.cacheDir, "shared/notify").apply { mkdirs() }
        // Keep only the last few around.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        val copy = File(dir, decrypted.name).apply { if (!exists()) decrypted.copyTo(this) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
        context.grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri
    }

    /**
     * Registers the chat as a conversation shortcut, so Android shows its notifications in the
     * Conversations section (with the chat's photo), and lets you make it a priority conversation.
     */
    fun pushConversation(context: Context, groupId: String, title: String, icon: Bitmap, person: Person?) {
        runCatching {
            val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_OPEN_GROUP, groupId)
            val shortcut = ShortcutInfoCompat.Builder(context, groupId)
                .setShortLabel(title)
                .setIcon(IconCompat.createWithBitmap(icon))
                .setIntent(intent)
                .setLongLived(true)
                .setLocusId(LocusIdCompat(groupId))
                .apply { if (person != null) setPerson(person) }
                .apply { if (Build.VERSION.SDK_INT >= 30) setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION)) }
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    fun removeConversation(context: Context, groupId: String) {
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(groupId)) }
    }
}
