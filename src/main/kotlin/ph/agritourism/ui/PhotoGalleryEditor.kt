package ph.agritourism.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.data.Database

/**
 * Reusable multi-image editor. The first image is the cover; every gallery is capped at
 * [Database.MAX_IMAGES_PER_LISTING] images and the native picker is guarded so choosing files
 * cannot dismiss or reset the form behind it.
 */
@Composable
fun PhotoGalleryEditor(
    s: AppState,
    title: String,
    images: List<String>,
    onAdd: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onMakeCover: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Total allowance for this editor; product forms reserve one slot for their main photo. */
    limit: Int = Database.MAX_IMAGES_PER_LISTING,
) {
    val room = limit - images.size
    // Product forms reserve one of the five total slots for their main photo.
    val usedSlots = Database.MAX_IMAGES_PER_LISTING - limit + images.size
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(title)
            Spacer(Modifier.weight(1f))
            Text("${images.size}/$limit photos", fontSize = 11.sp, color = Palette.Muted,
                modifier = Modifier.testTag("gallery-count"))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(images, key = { _, url -> url }) { index, url ->
                Box(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, if (index == 0) Palette.Forest else Palette.Border, RoundedCornerShape(12.dp))) {
                    AsyncImage(url, Modifier.fillMaxSize(), contentDescription = "$title image ${index + 1}")
                    if (index == 0) {
                        Pill("Cover", Color(0xFFDCFCE7), Color(0xFF166534), Modifier.align(Alignment.BottomStart).padding(4.dp))
                    } else {
                        Text("Set cover", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                                .background(Palette.Forest.copy(alpha = 0.78f), RoundedCornerShape(50))
                                .clickable { onMakeCover(url) }.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Box(Modifier.align(Alignment.TopEnd).size(22.dp).clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.9f)).clickable { onRemove(url) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, "Remove photo", Modifier.size(14.dp), tint = Palette.Red)
                    }
                }
            }
        }
        SecondaryButton(
            if (room > 0) "Add photos (up to $room)" else "Photo limit reached",
            icon = Icons.Filled.Add,
            enabled = room > 0,
            modifier = Modifier.testTag("add-gallery-photos"),
        ) {
            val files = pickImageFiles("Choose photos", room)
            val added = s.storeUploads(files, usedSlots)
            if (added.isNotEmpty()) onAdd(added)
        }
    }
}
