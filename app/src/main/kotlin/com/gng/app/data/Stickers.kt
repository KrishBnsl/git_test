package com.gng.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.gng.app.crypto.hex
import com.gng.app.crypto.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

data class StickerPack(val id: String, val name: String, val stickers: List<File>)

/**
 * Your personal sticker library, kept on this phone.
 *
 * WhatsApp doesn't let other apps read its stickers directly, so there are three ways in:
 * - pick WhatsApp's own stickers folder (Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers),
 *   which holds every sticker you've received or sent there;
 * - open a `.wastickers` pack (what sticker-maker apps export), or any images;
 * - long-press a sticker someone sent in gng and save it.
 */
class StickerStore(private val context: Context, private val media: MediaRepository) {
    private val root get() = File(context.filesDir, "stickers").apply { mkdirs() }
    private val _packs = MutableStateFlow<List<StickerPack>>(emptyList())
    val packs: StateFlow<List<StickerPack>> = _packs.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) { _packs.value = scan() }

    /** Imports the stickers folder WhatsApp keeps in shared storage. Returns how many were new. */
    suspend fun importWhatsAppFolder(tree: Uri): Int = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: error("Can't open that folder")
        val files = dir.listFiles().filter { it.isFile && it.name.orEmpty().endsWith(".webp", ignoreCase = true) }
        require(files.isNotEmpty()) { "No stickers there. Pick the \"WhatsApp Stickers\" folder." }
        var added = 0
        files.take(MAX_IMPORT).forEach { f ->
            val bytes = context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } ?: return@forEach
            if (save("whatsapp", "WhatsApp", bytes, "image/webp")) added++
        }
        load()
        added
    }

    /** Imports `.wastickers` / `.zip` packs and loose images. Returns how many stickers were added. */
    suspend fun importFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var added = 0
        uris.forEach { uri ->
            val name = uri.displayName(context).orEmpty()
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (name.endsWith(".wastickers", true) || name.endsWith(".zip", true) || mime.contains("zip")) {
                added += importPack(uri, name.substringBeforeLast('.'))
            } else {
                val picked = media.read(uri)
                if (save("imported", "Imported", picked.bytes, picked.mime)) added++
            }
        }
        load()
        added
    }

    /** Saves a sticker that someone sent in a chat. */
    suspend fun saveReceived(file: File): Boolean = withContext(Dispatchers.IO) {
        save("saved", "Saved", file.readBytes(), "image/webp").also { load() }
    }

    suspend fun delete(file: File) = withContext(Dispatchers.IO) {
        file.delete()
        file.parentFile?.takeIf { it.listFiles { f -> f.extension == "webp" }.isNullOrEmpty() }?.deleteRecursively()
        load()
    }

    private suspend fun importPack(uri: Uri, fallbackName: String): Int {
        var title = fallbackName.ifBlank { "Pack" }
        val images = mutableListOf<Pair<String, ByteArray>>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name.substringAfterLast('/')
                    when {
                        entry.isDirectory -> Unit
                        entryName.equals("title.txt", true) -> title = zip.readBytes().decodeToString().trim().take(30).ifBlank { title }
                        entryName.endsWith(".webp", true) -> images += entryName to zip.readBytes()
                    }
                    if (images.size >= MAX_IMPORT) break
                }
            }
        } ?: error("Couldn't open that pack")
        require(images.isNotEmpty()) { "No .webp stickers in that pack" }
        val packId = "pack_" + title.toByteArray().sha256().hex().take(8)
        return images.count { (_, bytes) -> save(packId, title, bytes, "image/webp") }
    }

    private suspend fun save(packId: String, packName: String, bytes: ByteArray, mime: String): Boolean {
        val sticker = runCatching { media.prepareSticker(bytes, mime) }.getOrNull() ?: return false
        val dir = File(root, packId).apply { mkdirs() }
        File(dir, "name.txt").takeIf { !it.exists() }?.writeText(packName)
        val file = File(dir, sticker.sha256().hex().take(24) + ".webp")
        if (file.exists()) return false
        file.writeBytes(sticker)
        return true
    }

    private fun scan(): List<StickerPack> =
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .map { dir ->
                StickerPack(
                    id = dir.name,
                    name = File(dir, "name.txt").takeIf { it.exists() }?.readText() ?: dir.name,
                    stickers = dir.listFiles { f -> f.extension == "webp" }.orEmpty().sortedByDescending { it.lastModified() },
                )
            }
            .filter { it.stickers.isNotEmpty() }
            .sortedBy { if (it.id == "saved") "" else it.name.lowercase() }

    companion object {
        private const val MAX_IMPORT = 300

        /** Where WhatsApp keeps received stickers; used as the folder picker's starting point. */
        val WHATSAPP_STICKERS_URI: Uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers",
        )
    }
}
