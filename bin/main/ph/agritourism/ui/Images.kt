package ph.agritourism.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SamplingMode
import ph.agritourism.data.Database
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Collections

/**
 * Loads images from: bundled resources ("res:path"), local files ("file:/abs/path"),
 * or http(s) URLs (downloaded once and cached under ~/.agritourism/cache for offline use).
 */
object ImageLoader {
    private const val MAX_DIM = 1400
    private val memory = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 120
    })
    private val cacheDir by lazy { File(Database.defaultFile().parentFile, "cache").apply { mkdirs() } }

    fun cached(src: String): ImageBitmap? = memory[src]

    suspend fun load(src: String): ImageBitmap? = memory[src] ?: withContext(Dispatchers.IO) {
        runCatching { bytes(src)?.let(::decode) }.getOrNull()?.also { memory[src] = it }
    }

    fun bytes(src: String): ByteArray? = when {
        src.startsWith("res:") -> ImageLoader::class.java.getResourceAsStream("/" + src.removePrefix("res:"))?.use { it.readBytes() }
        src.startsWith("file:") -> File(src.removePrefix("file:")).takeIf { it.exists() }?.readBytes()
        src.startsWith("http") -> {
            val key = MessageDigest.getInstance("SHA-1").digest(src.toByteArray()).joinToString("") { "%02x".format(it) }
            val f = File(cacheDir, key)
            if (f.exists() && f.length() > 0) f.readBytes() else {
                val c = URI(src).toURL().openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                c.setRequestProperty("User-Agent", "AgriTourismDesktop/1.0")
                c.instanceFollowRedirects = true
                c.inputStream.use { it.readBytes() }.also { if (c.responseCode == 200) f.writeBytes(it) }
            }
        }
        else -> null
    }

    private fun decode(data: ByteArray): ImageBitmap {
        val img = SkiaImage.makeFromEncoded(data)
        val scale = minOf(1f, MAX_DIM.toFloat() / maxOf(img.width, img.height))
        if (scale >= 1f) return img.toComposeImageBitmap()
        val w = (img.width * scale).toInt(); val h = (img.height * scale).toInt()
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
            SamplingMode.LINEAR, null, true)
        return surface.makeImageSnapshot().toComposeImageBitmap()
    }
}

/** Bundled photo shown when an image is missing or can't be loaded, so nothing renders blank. */
const val FALLBACK_IMAGE = "res:images/photo-1540420773420-3366772f4999-w600.jpg"

@Composable
fun AsyncImage(src: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, contentDescription: String? = null,
               fallback: String = FALLBACK_IMAGE) {
    val primary = src.ifBlank { fallback }
    var bmp by remember(primary) { mutableStateOf(ImageLoader.cached(primary)) }
    var failed by remember(primary) { mutableStateOf(false) }
    LaunchedEffect(primary) {
        if (bmp == null) {
            bmp = ImageLoader.load(primary) ?: fallback.takeIf { it != primary }?.let { ImageLoader.load(it) }
            if (bmp == null) failed = true
        }
    }
    val b = bmp
    val wasCached = remember(primary) { bmp != null }
    val fade by androidx.compose.animation.core.animateFloatAsState(
        if (b != null) 1f else 0f, androidx.compose.animation.core.tween(if (wasCached) 0 else 420), label = "img-fade")
    if (b != null) {
        Image(b, contentDescription, modifier.graphicsLayer { alpha = fade }, contentScale = contentScale)
    } else if (failed) {
        Box(modifier.background(Brush.linearGradient(listOf(Color(0xFFDCEBDF), Color(0xFFF1F5EE)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Image, null, tint = Color(0xFF8FB39A))
        }
    } else {
        // Loading shimmer
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
        val x by t.animateFloat(-1f, 2f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1300, easing = androidx.compose.animation.core.LinearEasing)), label = "shimmer-x")
        Box(modifier.drawBehind {
            val w = size.width
            drawRect(Brush.linearGradient(listOf(Color(0xFFE3EEE5), Color(0xFFF6FAF6), Color(0xFFE3EEE5)),
                start = androidx.compose.ui.geometry.Offset(w * x - w * 0.5f, 0f), end = androidx.compose.ui.geometry.Offset(w * x + w * 0.5f, size.height)))
        })
    }
}

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "jfif")

/** Opens a native file chooser for a photo; returns the picked file or null if cancelled. */
fun pickImageFile(title: String): File? {
    val fd = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    fd.setFilenameFilter { _, n -> n.substringAfterLast('.').lowercase() in IMAGE_EXTS }
    fd.isVisible = true
    return fd.file?.let { File(fd.directory, it) }
}
