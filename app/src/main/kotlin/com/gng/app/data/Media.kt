package com.gng.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.gng.app.crypto.Aead
import com.gng.app.crypto.b64
import com.gng.app.crypto.hex
import com.gng.app.crypto.randomBytes
import com.gng.app.crypto.sha256
import com.gng.app.crypto.unb64
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

class PickedFile(val name: String, val mime: String, val bytes: ByteArray)

class PreparedImage(val bytes: ByteArray, val width: Int, val height: Int, val thumb: String)

/**
 * Attachments are encrypted on the phone with a fresh key per file, uploaded as opaque blobs,
 * and the key travels inside the (encrypted) message. Decrypted copies are cached on disk.
 */
class MediaRepository(
    private val context: Context,
    private val client: SupabaseClient,
    private val cache: ChatCache,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val inFlight = ConcurrentHashMap<String, Mutex>()
    private val dir get() = File(context.cacheDir, "media").apply { mkdirs() }

    // --- picking & preparing ---------------------------------------------------------

    suspend fun read(uri: Uri): PickedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0) ?: name
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        require(size <= MAX_ATTACHMENT_BYTES) { "That's over 25 MB, share a Drive link instead" }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            // Size can be unknown, so stop reading as soon as we pass the limit.
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_ATTACHMENT_BYTES) { "That's over 25 MB, share a Drive link instead" }
            }
            out.toByteArray()
        } ?: error("Couldn't read that file")
        val mime = resolver.getType(uri) ?: mimeFromName(name)
        PickedFile(name, mime, bytes)
    }

    /** Scales a photo to at most 2048px, fixes rotation, and makes a tiny blurred-preview thumbnail. */
    suspend fun prepareImage(bytes: ByteArray): PreparedImage = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0) { "That doesn't look like an image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Couldn't open that image")
        bitmap = bitmap.rotated(exifRotation(bytes)).scaledToFit(MAX_IMAGE_SIDE)
        val out = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        PreparedImage(out, bitmap.width, bitmap.height, thumbnail(bitmap))
    }

    /** Stickers are kept as 512px WebP, like WhatsApp's. Already-small WebPs pass through untouched. */
    suspend fun prepareSticker(bytes: ByteArray, mime: String): ByteArray = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0) { "Not a sticker image" }
        if (mime == "image/webp" && bounds.outWidth <= 512 && bounds.outHeight <= 512 && bytes.size <= 500_000) {
            return@withContext bytes
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!.scaledToFit(512)
        ByteArrayOutputStream().also {
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bitmap.compress(format, 85, it)
        }.toByteArray()
    }

    // --- upload / download ----------------------------------------------------------

    suspend fun upload(
        groupId: String,
        bytes: ByteArray,
        mime: String,
        name: String? = null,
        width: Int? = null,
        height: Int? = null,
        thumb: String? = null,
    ): MediaRef = withContext(Dispatchers.IO) {
        val key = randomBytes(32)
        val path = "$groupId/${UUID.randomUUID()}"
        val sealed = Aead.seal(key, bytes, mediaAad(path))
        client.storage.from(BUCKET).upload(path, sealed)
        val sha = bytes.sha256().hex()
        // Keep our own decrypted copy so it shows instantly without a round trip.
        File(dir, sha + extension(mime)).writeBytes(bytes)
        MediaRef(path, key.b64(), sha, mime, name, bytes.size.toLong(), width, height, thumb)
    }

    /** Uploads a sticker once per conversation and reuses the blob after that. */
    suspend fun uploadSticker(groupId: String, bytes: ByteArray): MediaRef {
        val cacheKey = "sticker:$groupId:${bytes.sha256().hex()}"
        cache.getRaw(cacheKey)?.let { raw -> runCatching { json.decodeFromString<MediaRef>(raw) }.getOrNull()?.let { return it } }
        val (w, h) = withContext(Dispatchers.Default) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            o.outWidth to o.outHeight
        }
        return upload(groupId, bytes, "image/webp", "sticker.webp", w, h)
            .also { cache.putRaw(cacheKey, json.encodeToString(MediaRef.serializer(), it)) }
    }

    /** The decrypted file, downloading it first if needed. */
    suspend fun file(ref: MediaRef): File {
        val target = File(dir, ref.sha256 + extension(ref.mime))
        if (target.exists()) return target
        return inFlight.getOrPut(ref.sha256) { Mutex() }.withLock {
            if (target.exists()) return@withLock target
            withContext(Dispatchers.IO) {
                val sealed = client.storage.from(BUCKET).downloadAuthenticated(ref.path)
                val plain = Aead.open(ref.key.unb64(), sealed, mediaAad(ref.path))
                check(plain.sha256().hex() == ref.sha256) { "Attachment was tampered with" }
                val tmp = File(dir, "${ref.sha256}.part")
                tmp.writeBytes(plain)
                tmp.renameTo(target)
                target
            }
        }
    }

    fun cachedFile(ref: MediaRef): File? = File(dir, ref.sha256 + extension(ref.mime)).takeIf { it.exists() }

    // --- sharing out ---------------------------------------------------------------

    suspend fun saveToGallery(ref: MediaRef) = withContext(Dispatchers.IO) {
        val file = file(ref)
        val name = ref.name ?: "gng_${System.currentTimeMillis()}${extension(ref.mime)}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, ref.mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/gng")
        }
        val collection = if (ref.mime.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
        val uri = context.contentResolver.insert(collection, values) ?: error("Couldn't save")
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /** Intent that opens the attachment in another app (PDF viewer, etc.). */
    suspend fun openIntent(ref: MediaRef): Intent {
        val file = file(ref)
        // Give it its real name so the other app shows something sensible.
        val shared = File(File(context.cacheDir, "shared").apply { mkdirs() }, ref.name ?: file.name)
        withContext(Dispatchers.IO) { file.copyTo(shared, overwrite = true) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shared)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, ref.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // --- helpers -----------------------------------------------------------------

    private fun mediaAad(path: String) = "gng-media-v1|$path"

    private fun exifRotation(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun Bitmap.rotated(degrees: Int): Bitmap =
        if (degrees == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private fun Bitmap.scaledToFit(max: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= max) return this
        val scale = max.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun thumbnail(bitmap: Bitmap): String {
        val small = bitmap.scaledToFit(24)
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 50, it) }.toByteArray().b64()
    }

    companion object {
        const val BUCKET = "chat-media"
        private const val MAX_IMAGE_SIDE = 2048

        fun extension(mime: String): String =
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ".$it" } ?: ""

        fun mimeFromName(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
    }
}

/** Decodes a base64 thumbnail for the blurred placeholder. */
fun decodeThumb(b64: String?): Bitmap? = b64?.let {
    runCatching { it.unb64().let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } }.getOrNull()
}

fun Uri.displayName(context: Context): String? =
    context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
