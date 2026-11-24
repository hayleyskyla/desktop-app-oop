package ph.agritourism.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.data.Database
import ph.agritourism.model.FarmTour
import ph.agritourism.model.FarmerProfile
import ph.agritourism.model.FruitPickingItem
import ph.agritourism.ui.*

/** Full picture and description of a catalog item, for visitors who haven't logged in. */
@Composable
internal fun CatalogPreviewModal(s: AppState, p: CatalogPreview) {
    val close = { s.catalogPreview = null }
    Modal(close, 720.dp) {
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            AsyncImage(p.image, Modifier.fillMaxSize(), contentDescription = p.title)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0B2512)))))
            CloseAction(Modifier.align(Alignment.TopEnd).padding(10.dp), color = Palette.Deep, showLabel = false) { close() }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Pill(p.tag, Color.White.copy(alpha = 0.92f), Palette.Deep)
                Spacer(Modifier.height(6.dp))
                Text(p.title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
        }
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(p.description, fontSize = 14.sp, color = Color(0xFF334155))
            p.details.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (l, v) -> InfoTile(l, v, modifier = Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (!s.isLoggedIn) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Mint).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Log in or sign up to book, buy and explore more.", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Palette.Deep)
                    PrimaryButton("Explore More", icon = Icons.AutoMirrored.Outlined.Login) { close(); s.navigate(Page.LOGIN) }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { CloseAction(onClick = { close() }) }
        }
    }
}

/**
 * Lets a farmer list a new tour (or fruit-picking experience when [orchard]) on their own farm,
 * or edit an existing [tour] / [orchardItem].
 */
@Composable
internal fun AddActivityModal(s: AppState, orchard: Boolean, tour: FarmTour? = null, orchardItem: FruitPickingItem? = null) {
    val editing = tour != null || orchardItem != null
    val close = {
        if (orchard) { s.isAddOrchardModalOpen = false; s.editingOrchard = null } else { s.isAddTourModalOpen = false; s.editingTour = null }
    }
    var ownerId by remember(tour?.id, orchardItem?.id) {
        mutableStateOf(tour?.farmerId ?: orchardItem?.farmerId ?: s.adminListingFarmId)
    }
    val farm = if (s.isAdmin) s.farmers.firstOrNull { it.id == ownerId } else s.myFarm
    var name by remember { mutableStateOf(tour?.name ?: orchardItem?.title ?: "") }
    var price by remember { mutableStateOf((tour?.pricePerPerson ?: orchardItem?.pricePerPerson)?.toString() ?: "") }
    var desc by remember { mutableStateOf(tour?.description ?: orchardItem?.description ?: "") }
    var image by remember { mutableStateOf(tour?.imageUrl ?: orchardItem?.imageUrl ?: farm?.farmBannerUrl.orEmpty()) }
    var gallery by remember(tour?.id, orchardItem?.id) {
        mutableStateOf((tour?.thumbnails ?: orchardItem?.thumbnails ?: emptyList()).distinct().take(Database.MAX_IMAGES_PER_LISTING - 1))
    }
    var location by remember { mutableStateOf(tour?.location ?: orchardItem?.location ?: farm?.location.orEmpty()) }
    var slot by remember { mutableStateOf("08:00 AM") }
    var hours by remember { mutableStateOf(tour?.operatingHours ?: "8:00 AM - 5:00 PM") }
    var activities by remember { mutableStateOf(tour?.activities?.joinToString() ?: "") }
    var experienceType by remember { mutableStateOf(tour?.experienceType ?: "tour") }
    var amenities by remember { mutableStateOf(tour?.amenities?.joinToString() ?: "") }
    var estimatedDuration by remember { mutableStateOf(tour?.estimatedDuration ?: "") }
    var fruit by remember { mutableStateOf(orchardItem?.fruitName ?: "") }
    var duration by remember { mutableStateOf(orchardItem?.duration ?: "2 hours") }
    var season by remember { mutableStateOf(orchardItem?.seasonAvailability ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    Modal(close, 640.dp) {
        ModalHeader((if (editing) "Edit " else "Add ") + if (orchard) "Fruit Picking Experience" else "Farm Tour",
            if (editing) "Changes show in the catalog right away" else "Listed under ${farm?.farmName ?: "the selected farm"}",
            if (editing) Icons.Outlined.Edit else if (orchard) Icons.Outlined.AutoAwesome else Icons.Outlined.Explore, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.isAdmin && s.farmers.isNotEmpty()) {
                val farmIds = s.farmers.map { it.id }
                Dropdown("Farm Owner", listOf(0) + farmIds, ownerId?.takeIf { it == 0 || it in farmIds } ?: 0,
                    { id -> if (id == 0) "Select a farm" else s.farmers.first { it.id == id }.farmName }) {
                    ownerId = it
                    s.adminListingFarmId = it
                    s.farmers.firstOrNull { farm -> farm.id == it }?.let { selected ->
                        location = selected.location
                        if (image.isBlank()) image = selected.farmBannerUrl
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(image, Modifier.size(width = 160.dp, height = 110.dp).clip(RoundedCornerShape(14.dp)), contentDescription = "Cover photo")
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("Cover Photo")
                    SecondaryButton("Upload Picture", icon = Icons.Outlined.Upload) {
                        pickImageFile("Choose cover photo")?.let { f -> s.storeUpload(f)?.let { image = it } }
                    }
                    Sub("Defaults to your farm cover photo.")
                }
            }
            PhotoGalleryEditor(
                s = s,
                title = "Additional Photos",
                images = gallery,
                onAdd = { added -> gallery = (gallery + added).distinct().take(Database.MAX_IMAGES_PER_LISTING - 1) },
                onRemove = { url -> gallery = gallery.filterNot { it == url } },
                onMakeCover = { url ->
                    val oldCover = image
                    image = url
                    gallery = (gallery.filterNot { it == url } + oldCover).filter { it.isNotBlank() }
                        .distinct().take(Database.MAX_IMAGES_PER_LISTING - 1)
                },
            )
            Field(if (orchard) "Experience Name" else "Tour Name", name, { name = it }, Modifier.fillMaxWidth(),
                if (orchard) "e.g. Sweet Mango Picking" else "e.g. Sunrise Farm Walk")
            Field("Location", location, { location = it }, Modifier.fillMaxWidth(), "Barangay, city/municipality, province")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Price per Person (₱)", price, { v -> price = v.filter(Char::isDigit) }, Modifier.weight(1f), "e.g. 350", number = true)
                if (!editing) Field("First Time Slot", slot, { slot = it }, Modifier.weight(1f), "08:00 AM - Morning Walk")
            }
            if (orchard) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Fruit", fruit, { fruit = it }, Modifier.weight(1f), "e.g. Carabao Mango")
                    Field("Duration", duration, { duration = it }, Modifier.weight(1f), "e.g. 2 hours")
                }
                Field("Season", season, { season = it }, Modifier.fillMaxWidth(), "e.g. March - June")
            } else {
                Dropdown("Experience Type", listOf("tour", "other_activity"), experienceType,
                    { if (it == "other_activity") "Other Farm Activity" else "Farm Tour" }) {
                    experienceType = it
                }
                Field("Operating Hours", hours, { hours = it }, Modifier.fillMaxWidth(), "e.g. 8:00 AM - 5:00 PM")
                Field("Activities (comma separated)", activities, { activities = it }, Modifier.fillMaxWidth(), "e.g. Rice planting, Carabao ride")
                Field("Amenities (comma separated)", amenities, { amenities = it }, Modifier.fillMaxWidth(), "e.g. Parking, Restroom, Farm Cafe")
                Field("Estimated Duration", estimatedDuration, { estimatedDuration = it }, Modifier.fillMaxWidth(), "e.g. 3 hours")
            }
            Field("Description", desc, { desc = it }, Modifier.fillMaxWidth(), "What will visitors see and do?", singleLine = false, minLines = 3)
            Sub(if (editing) "Manage time slots under Guides & Time Slots." else "Add more time slots later under Guides & Time Slots.")
            ErrorBanner(error)
            PrimaryButton(if (editing) "Save Changes" else if (orchard) "Publish Fruit Picking" else "Publish Tour", Modifier.fillMaxWidth(),
                if (editing) Icons.Outlined.Save else Icons.Outlined.Publish) {
                val d = AppState.ActivityDraft(
                    name, price, desc, image, slot, hours, activities, fruit, duration, season,
                    experienceType, location, ownerId, amenities, estimatedDuration, gallery,
                )
                error = when {
                    tour != null -> s.updateTour(tour.id, d)
                    orchardItem != null -> s.updateOrchard(orchardItem.id, d)
                    orchard -> s.createOrchard(d)
                    else -> s.createTour(d)
                }
            }
            if (tour != null) SecondaryButton("Delete Tour", Modifier.fillMaxWidth(), Icons.Outlined.Delete, color = Palette.Red) { s.askDeleteTour(tour) }
            if (orchardItem != null) SecondaryButton("Delete Experience", Modifier.fillMaxWidth(), Icons.Outlined.Delete, color = Palette.Red) { s.askDeleteOrchard(orchardItem) }
        }
    }
}

/** Edits the farm's short story (quote) and description shown to tourists. */
@Composable
internal fun EditStoryModal(s: AppState, f: FarmerProfile) {
    val close = { s.isEditStoryOpen = false; s.editingFarmerStory = null }
    var story by remember { mutableStateOf(f.storyQuote) }
    var desc by remember { mutableStateOf(f.fullBiography) }
    var error by remember { mutableStateOf<String?>(null) }
    Modal(close, 620.dp) {
        ModalHeader("Edit Farm Story", "Show on Your Profile", Icons.Outlined.Edit, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Short Story", story, { story = it }, Modifier.fillMaxWidth(), "One or two sentences about why you farm", singleLine = false, minLines = 2)
            Text("${story.trim().length}/280", fontSize = 11.sp, color = if (story.trim().length > 280) Palette.Red else Palette.Muted)
            Field("Farm Description", desc, { desc = it }, Modifier.fillMaxWidth(), "Your farm's history, what you grow and how you farm", singleLine = false, minLines = 5)
            ErrorBanner(error)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Save Story", Modifier.weight(1f), Icons.Outlined.Save) {
                    error = s.saveFarmStory(f.id, story, desc)
                    if (error == null) close()
                }
                SecondaryButton("Cancel") { close() }
            }
        }
    }
}
