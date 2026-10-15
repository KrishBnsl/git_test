package com.gng.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

/** What an import did, so the panel can say something useful. */
data class ImportResult(val found: Int, val added: Int, val duplicates: Int, val unreadable: Int) {
    fun describe(): String = when {
        found == 0 -> "No stickers found there. Pick Android › media (or WhatsApp › Media › WhatsApp Stickers inside it)"
        added == 0 && duplicates > 0 -> "All $duplicates stickers are already in your library"
        else -> buildString {
            append("Added $added sticker${if (added == 1) "" else "s"}")
            if (duplicates > 0) append(" · $duplicates already there")
            if (unreadable > 0) append(" · $unreadable couldn't be read")
        }
    }
}

/**
 * Your personal sticker library, kept on this phone.
 *
 * WhatsApp doesn't let other apps read its stickers directly, so there are three ways in:
 * - pick WhatsApp's own stickers folder (Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers),
 *   which holds every sticker you've received or sent there. Picking a folder higher up also works
 *   (Android/media picks up WhatsApp Business too, and old phones keep it in WhatsApp/Media);
 *   stickers only ever installed from a pack, never sent or received, live in WhatsApp's private
 *   storage and can't be reached;
 * - open a `.wastickers` pack (what sticker-maker apps export), or any images;
 * - long-press a sticker someone sent in gng and save it.
 */
class StickerStore(private val context: Context, private val media: MediaRepository) {
    private val root get() = File(context.filesDir, "stickers").apply { mkdirs() }
    private val _packs = MutableStateFlow<List<StickerPack>>(emptyList())
    val packs: StateFlow<List<StickerPack>> = _packs.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) { _packs.value = scan() }

    /**
     * Imports WhatsApp's stickers from a folder you picked. Works whether you pick the
     * "WhatsApp Stickers" folder itself or a parent (WhatsApp, Media, com.whatsapp): it searches
     * subfolders, and recognises WebP files by their contents since some have no extension.
     */
    suspend fun importWhatsAppFolder(tree: Uri): ImportResult = withContext(Dispatchers.IO) {
        // Keep access, so new WhatsApp stickers come in by themselves from now on (see syncWhatsApp).
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            sourceFile.writeText(tree.toString())
        }
        importFrom(tree)
    }

    /** Whether WhatsApp's folder has been picked once, so syncing needs no more taps. */
    val hasWhatsAppSource: Boolean get() = savedSource() != null

    /**
     * Brings in stickers that showed up in WhatsApp since last time. Quick when there's nothing new:
     * only files it hasn't seen before are read. Null if WhatsApp's folder was never picked.
     */
    suspend fun syncWhatsApp(): ImportResult? = withContext(Dispatchers.IO) {
        val tree = savedSource() ?: return@withContext null
        runCatching { importFrom(tree) }.getOrNull()
    }

    private val sourceFile get() = File(root, ".source")
    private val seenFile get() = File(root, ".seen")

    private fun savedSource(): Uri? {
        val uri = sourceFile.takeIf { it.exists() }?.readText()?.trim()?.let(Uri::parse) ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return uri.takeIf { granted }
    }

    private suspend fun importFrom(tree: Uri): ImportResult {
        val seen = seenFile.takeIf { it.exists() }?.readLines()?.toMutableSet() ?: mutableSetOf()
        val candidates = mutableListOf<Found>()
        walk(tree, DocumentsContract.getTreeDocumentId(tree), "", depth = 0, candidates)
        // Picked a parent folder: only take what's inside a stickers folder, not every image WhatsApp has.
        val inStickerFolders = candidates.filter { it.path.contains("sticker", ignoreCase = true) }
        // Newest first, so if there are more than we keep, it's the old ones that are left out.
        val all = inStickerFolders.ifEmpty { candidates }
        val files = all.filter { it.key !in seen }.sortedByDescending { it.modified }.take(MAX_IMPORT)
        val known = existingNames()
        var added = 0
        var duplicates = 0
        var unreadable = 0
        files.forEach { f ->
            val bytes = runCatching { context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } }.getOrNull()
            when (bytes?.let { save("whatsapp", "WhatsApp", it, known, f.modified) }) {
                SaveResult.Added -> added++
                SaveResult.Duplicate -> duplicates++
                else -> unreadable++
            }
            if (bytes != null) seen += f.key
        }
        runCatching { seenFile.writeText(seen.joinToString("\n")) }
        load()
        // Found counts everything there, so a sync with nothing new reads "already in your library".
        return ImportResult(all.size, added, duplicates + (all.size - files.size).coerceAtMost(all.size), unreadable)
    }

    private class Found(val path: String, val uri: Uri, val modified: Long) {
        /** Same file, same date: already imported. */
        val key get() = "$uri@$modified"
    }

    /** Lists sticker-looking files under [docId], a few levels deep, skipping folders that can't hold stickers. */
    private fun walk(tree: Uri, docId: String, path: String, depth: Int, out: MutableList<Found>) {
        if (depth > 6 || out.size >= MAX_SCAN) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val dirs = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1).orEmpty()
                val mime = c.getString(2).orEmpty()
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> if (worthSearching(name)) dirs += id to name
                    name.startsWith(".") || size > MAX_STICKER_BYTES -> Unit
                    name.endsWith(".webp", true) || mime == "image/webp" ->
                        out += Found(path, DocumentsContract.buildDocumentUriUsingTree(tree, id), modified)
                    '.' !in name -> {
                        // No extension: peek at the header.
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        if (isWebp(uri)) out += Found(path, uri, modified)
                    }
                }
            }
        }
        // Sticker folders first, so they're never crowded out by the cap.
        dirs.sortedBy { !it.second.contains("sticker", ignoreCase = true) }
            .forEach { (id, name) -> walk(tree, id, "$path/$name", depth + 1, out) }
    }

    /**
     * Whether a folder might hold stickers. Skips WhatsApp's big media folders (thousands of photos
     * and videos) and other apps' folders, so picking Android/media or WhatsApp/Media stays quick.
     */
    private fun worthSearching(name: String): Boolean {
        if (name.startsWith(".")) return false
        if (name.contains("sticker", ignoreCase = true)) return true
        if (name.startsWith("com.") || name.startsWith("org.")) return name.startsWith("com.whatsapp")
        return SKIP_FOLDERS.none { name.contains(it, ignoreCase = true) }
    }

    private fun existingNames(): MutableSet<String> =
        root.listFiles { f -> f.isDirectory }.orEmpty().flatMap { dir -> dir.list().orEmpty().toList() }.toMutableSet()

    private fun isWebp(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(12)
            input.read(head) == 12 && isWebp(head)
        } ?: false
    }.getOrDefault(false)

    /** Imports `.wastickers` / `.zip` packs and loose images. Returns how many stickers were added. */
    suspend fun importFiles(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        var found = 0
        var added = 0
        var duplicates = 0
        var unreadable = 0
        fun count(r: SaveResult?) {
            found++
            when (r) {
                SaveResult.Added -> added++
                SaveResult.Duplicate -> duplicates++
                else -> unreadable++
            }
        }
        val known = existingNames()
        uris.forEach { uri ->
            val name = uri.displayName(context).orEmpty()
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (name.endsWith(".wastickers", true) || name.endsWith(".zip", true) || mime.contains("zip")) {
                importPack(uri, name.substringBeforeLast('.'), known).forEach(::count)
            } else {
                count(runCatching { save("imported", "Imported", media.read(uri).bytes, known) }.getOrNull())
            }
        }
        load()
        ImportResult(found, added, duplicates, unreadable)
    }

    /** Saves a sticker that someone sent in a chat. */
    suspend fun saveReceived(file: File): Boolean = withContext(Dispatchers.IO) {
        (save("saved", "Saved", file.readBytes(), existingNames()) == SaveResult.Added).also { load() }
    }

    /** Saves a sticker you made (already a 512px WebP) to "My stickers" and returns its file. */
    suspend fun saveMade(webp: ByteArray): File = withContext(Dispatchers.IO) {
        save(MADE_PACK, "My stickers", webp, existingNames())
        load()
        File(File(root, MADE_PACK), webp.sha256().hex().take(24) + ".webp")
    }

    suspend fun delete(file: File) = withContext(Dispatchers.IO) {
        file.delete()
        file.parentFile?.takeIf { it.listFiles { f -> f.extension == "webp" }.isNullOrEmpty() }?.deleteRecursively()
        load()
    }

    private suspend fun importPack(uri: Uri, fallbackName: String, known: MutableSet<String>): List<SaveResult> {
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
                        entryName.endsWith(".webp", true) || entryName.endsWith(".png", true) && !entryName.contains("tray", true) ->
                            images += entryName to zip.readBytes()
                    }
                    if (images.size >= MAX_IMPORT) break
                }
            }
        } ?: error("Couldn't open that pack")
        require(images.isNotEmpty()) { "No .webp stickers in that pack" }
        val packId = "pack_" + title.toByteArray().sha256().hex().take(8)
        return images.map { (_, bytes) -> save(packId, title, bytes, known) }
    }

    private enum class SaveResult { Added, Duplicate, Unreadable }

    /** [known]: file names already in the library (any pack); updated as stickers are added. [modified]: keep the original date for ordering. */
    private suspend fun save(packId: String, packName: String, bytes: ByteArray, known: MutableSet<String>, modified: Long = 0): SaveResult {
        val sticker = runCatching { media.prepareSticker(bytes) }.getOrNull() ?: return SaveResult.Unreadable
        val name = sticker.sha256().hex().take(24) + ".webp"
        // Already saved, in this pack or any other.
        if (!known.add(name)) return SaveResult.Duplicate
        val dir = File(root, packId).apply { mkdirs() }
        File(dir, "name.txt").takeIf { !it.exists() }?.writeText(packName)
        File(dir, name).apply {
            writeBytes(sticker)
            if (modified > 0) setLastModified(modified)
        }
        return SaveResult.Added
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
            // Your own first, then ones you saved from chats, then the rest by name.
            .sortedBy { when (it.id) { MADE_PACK -> "0"; "saved" -> "1"; else -> "2" + it.name.lowercase() } }

    companion object {
        private const val MADE_PACK = "mine"
        private const val MAX_IMPORT = 3000
        private const val MAX_SCAN = 6000

        /** WhatsApp folders that never hold stickers. */
        private val SKIP_FOLDERS = listOf(
            "Images", "Video", "Audio", "Voice Notes", "Documents", "Animated Gifs", "Profile Photos",
            "Backups", "Databases", "Wallpaper", "Statuses", "Calls", "Bug Report",
        )
        private const val MAX_STICKER_BYTES = 1_500_000L

        /** "RIFF....WEBP" */
        fun isWebp(head: ByteArray): Boolean =
            head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP"

        /**
         * Where the folder picker opens: Android/media, which holds WhatsApp's and WhatsApp Business's
         * sticker folders. One "Use this folder" there covers both.
         */
        val WHATSAPP_STICKERS_URI: Uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Android/media",
        )
    }
}
