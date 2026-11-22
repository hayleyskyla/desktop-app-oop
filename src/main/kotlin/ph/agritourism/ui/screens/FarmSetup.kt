package ph.agritourism.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.ui.*

/** Farmers set their own operating hours, generate time slots, and assign guides. */
@Composable
fun FarmSetupPage(s: AppState) {
    val id = s.myFarmId ?: run { Sub("No farm is linked to this account."); return }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { H1("Guides & Time Slots"); Sub("Set opening and closing times, generate bookable slots, and assign guides for your tours and fruit picking") }
    }
    GuidesCard(s, id)
    val tours = s.tours.filter { it.farmerId == id }
    val orchards = s.fruitPicking.filter { it.farmerId == id }
    if (tours.isEmpty() && orchards.isEmpty()) Sub("This farm has no tours or orchards yet.")
    tours.forEach { SlotsCard(s, "tour", it.id, it.name) }
    orchards.forEach { SlotsCard(s, "orchard", it.id, it.title) }
}

@Composable
private fun GuidesCard(s: AppState, farmId: Int) {
    var name by remember(farmId) { mutableStateOf("") }
    var error by remember(farmId) { mutableStateOf<String?>(null) }
    val add = { error = s.addGuide(farmId, name); if (error == null) name = "" }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader("Tour Guides", "Assigned to new bookings and selectable when managing a booking")
        Spacer(Modifier.height(10.dp))
        val list = s.guides.filter { it.farmerId == farmId }
        if (list.isEmpty()) Sub("No guides yet. New bookings will list the farm host as guide.")
        list.forEach { g -> ListRow(g.name, Icons.Outlined.Person) { s.removeGuide(g) } }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Add a guide", name, { name = it; error = null }, Modifier.weight(1f), "e.g. Ate Liza (Orchard Guide)", icon = Icons.Outlined.PersonAdd, onSubmit = add)
            PrimaryButton("Add Guide", icon = Icons.Outlined.Add, enabled = name.isNotBlank()) { add() }
        }
        ErrorBanner(error)
    }
}

@Composable
private fun SlotsCard(s: AppState, type: String, activityId: Int, title: String) {
    var label by remember(type, activityId) { mutableStateOf("") }
    var error by remember(type, activityId) { mutableStateOf<String?>(null) }
    val add = { error = s.addTimeSlot(type, activityId, label); if (error == null) label = "" }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader(title, if (type == "tour") "Farm tour operating hours and time slots" else "Fruit picking operating hours and time slots")
        Spacer(Modifier.height(10.dp))
        AutoScheduleEditor(s, type, activityId)
        val slots = s.timeSlots.filter { it.activityType == type && it.activityId == activityId }
        if (slots.isEmpty()) Sub("No time slots yet. Tourists can't book this until you add one.")
        slots.forEach { t -> ListRow(t.label, Icons.Outlined.Schedule) { s.removeTimeSlot(t) } }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Add a time slot", label, { label = it; error = null }, Modifier.weight(1f), "e.g. 08:00 AM - Morning Walk", icon = Icons.Outlined.Schedule, onSubmit = add)
            PrimaryButton("Add Slot", icon = Icons.Outlined.Add, enabled = label.isNotBlank()) { add() }
        }
        ErrorBanner(error)
    }
}

/** Farmer tool: set opening/closing time and slot length, then regenerate only unbooked slots. */
@Composable
private fun AutoScheduleEditor(s: AppState, type: String, activityId: Int) {
    val saved = s.operatingSchedule(type, activityId)
    val tour = if (type == "tour") s.tours.firstOrNull { it.id == activityId } else null
    var open by remember(type, activityId, saved?.open) {
        mutableStateOf(saved?.open ?: tour?.operatingHours?.substringBefore(" - ")?.trim()?.takeIf { it.isNotBlank() } ?: "08:00 AM")
    }
    var close by remember(type, activityId, saved?.close) {
        mutableStateOf(saved?.close ?: tour?.operatingHours?.substringAfter(" - ")?.trim()?.takeIf { it.isNotBlank() && it != tour?.operatingHours } ?: "05:00 PM")
    }
    var minutes by remember(type, activityId, saved?.slotMinutes) { mutableStateOf((saved?.slotMinutes ?: 60).toString()) }
    var genError by remember(type, activityId) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Opening hours & generated slots")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Field("Opening time", open, { open = it; genError = null }, Modifier.weight(1f), "08:00 AM", icon = Icons.Outlined.Schedule)
            Field("Closing time", close, { close = it; genError = null }, Modifier.weight(1f), "05:00 PM", icon = Icons.Outlined.Schedule)
            Field("Slot minutes", minutes, { minutes = it.filter(Char::isDigit); genError = null }, Modifier.width(120.dp), "60", number = true)
            PrimaryButton("Generate", icon = Icons.Outlined.Autorenew) {
                genError = s.saveOperatingSchedule(type, activityId, open, close, minutes.toIntOrNull() ?: 0)
            }
        }
        if (saved != null) Sub("Saved hours: ${saved.open}–${saved.close}, one slot every ${saved.slotMinutes} min. Slots attached to active bookings are preserved.")
        ErrorBanner(genError)
        HorizontalDivider(color = Palette.Border)
    }
}

@Composable
private fun ListRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = Palette.Forest)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        IconButton(onRemove, Modifier.handCursor()) { Icon(Icons.Outlined.Delete, "Remove $text", tint = Palette.Red) }
    }
    HorizontalDivider(color = Palette.Border)
}

private val SETTING_LABELS = listOf(
    "delivery_fee" to "Delivery fee (₱)",
    "product_categories" to "Product categories (comma-separated)",
)

/** Admin: checkout and catalog settings. */
@Composable
fun PlatformSettingsPage(s: AppState) {
    Column { H1("Platform Settings"); Sub("Marketplace checkout and product catalog configuration") }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader("Order & Catalog", "Shown at checkout and on new produce orders")
        Spacer(Modifier.height(10.dp))
        SETTING_LABELS.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { (key, label) -> SettingField(s, key, label, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SettingField(s: AppState, key: String, label: String, modifier: Modifier) {
    val saved = s.setting(key)
    var value by remember(saved) { mutableStateOf(saved) }
    var error by remember { mutableStateOf<String?>(null) }
    val save = { error = s.saveSetting(key, value); if (error == null) s.showToast("Saved: $label") }
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(label, value, { value = it; error = null }, Modifier.weight(1f), error = error, onSubmit = save)
        SecondaryButton("Save", enabled = value.trim() != saved) { save() }
    }
}
