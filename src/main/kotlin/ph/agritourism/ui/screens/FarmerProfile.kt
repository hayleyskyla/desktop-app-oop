package ph.agritourism.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.ui.*

@Composable
fun FarmerProfilePage(s: AppState) {
    val f = s.myFarm
    if (f == null) {
        GlassCard(Modifier.fillMaxWidth(), padding = 32.dp) {
            H2("No farm linked to this account"); Sub("Ask an administrator to link your account to a farm profile.")
        }
        return
    }
    val tourReviews = s.reviews.filter { r -> r.targetId != null && ((r.targetType == "tour" && s.myTours.any { it.id == r.targetId }) ||
        (r.targetType == "orchard" && s.myOrchards.any { it.id == r.targetId })) }.sortedByDescending { it.id }
    Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(24.dp))) {
        AsyncImage(f.farmBannerUrl.ifBlank { s.images.farmerProfileBanner }, Modifier.fillMaxSize(), contentDescription = f.farmName)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60B2512)))))
        Row(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (f.certifications.isNotEmpty()) Pill(f.certifications.first(), Color.White.copy(alpha = 0.92f), Palette.Deep)
            SecondaryButton("Change Cover Photo", icon = Icons.Outlined.PhotoCamera) { pickImageFile("Choose farm cover photo")?.let { s.changeFarmBanner(f.id, it) } }
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.Bottom) {
            EditableAvatar(f.photoUrl.ifBlank { s.avatar }, 96.dp) { pickImageFile("Choose your profile photo")?.let(s::changeAvatar) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.displayName, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White); Spacer(Modifier.width(10.dp))
                    Pill(f.roleTitle, Color(0xFFFEF3C7), Color(0xFF92400E))
                }
                Text("${f.farmName} · ${f.location}", color = Color(0xFFBBF7D0), fontSize = 13.sp)
                Text(f.storyQuote.ifBlank { "Add a short story so tourists get to know you." }, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 2)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Add Farm Product with Photo", icon = Icons.Filled.Add, color = Color(0xFF22C55E)) { s.openAddProduct() }
                PrimaryButton("New Tour Reservation", icon = Icons.Outlined.CalendarMonth, color = Color(0xFF2D7A45)) { s.isAddBookingModalOpen = true }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(
            listOf("Tourist Rating", if (tourReviews.isEmpty()) "No ratings" else "%.1f / 5.0".format(tourReviews.map { it.rating }.average()), "${tourReviews.size} verified reviews"),
            listOf("Listed Products", "${s.farmProducts.size} Items", "${s.farmProducts.count { it.stock > 0 }} in stock"),
            listOf("Active Tours", "${s.myTours.size + s.myOrchards.size} Tours", "Agro-trail & Fruit Picking"),
            listOf("Tour Bookings", "${s.visibleBookings.size} Bookings", "$PESO${money(s.activeBookings.sumOf { it.totalAmount })} revenue"),
        ).zip(listOf(Icons.Outlined.Star, Icons.Outlined.Spa, Icons.Outlined.Explore, Icons.Outlined.EventAvailable)).forEach { (st, ic) ->
            GlassCard(Modifier.weight(1f)) {
                Icon(ic, null, tint = Palette.Forest); Spacer(Modifier.height(6.dp))
                Label(st[0]); Text(st[1], fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep); Sub(st[2])
            }
        }
    }
    SectionHeader("Farmer's Tour & Fruit Picking Experiences", "Booking schedules operated at ${f.farmName}") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Add Tour", icon = Icons.Outlined.Explore) { s.isAddTourModalOpen = true }
            PrimaryButton("Add Fruit Picking", icon = Icons.Outlined.AutoAwesome, color = Color(0xFF15803D)) { s.isAddOrchardModalOpen = true }
            SecondaryButton("Walk-in Booking", icon = Icons.Outlined.CalendarMonth) { s.isAddBookingModalOpen = true }
        }
    }
    if (s.myTours.isEmpty() && s.myOrchards.isEmpty()) Sub("No tours or orchards listed for your farm yet.")
    Grid(s.myTours, 3) { t ->
        GlassCard(padding = 0.dp) {
            Box(Modifier.fillMaxWidth().height(170.dp)) {
                AsyncImage(t.imageUrl, Modifier.fillMaxSize())
                Pill(t.location, Color.White.copy(alpha = 0.92f), Palette.Deep, Modifier.align(Alignment.TopStart).padding(10.dp))
                Pill(t.highlightTag, Color(0xFFFEF3C7), Color(0xFF92400E), Modifier.align(Alignment.TopEnd).padding(10.dp))
            }
            Column(Modifier.padding(14.dp)) {
                Row { Text(t.name, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, color = Palette.Deep, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("★ ${t.rating} (${t.reviewsCount})", fontSize = 12.sp, color = Palette.Amber) }
                Sub(t.description, maxLines = 3)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$PESO${t.pricePerPerson}", fontWeight = FontWeight.ExtraBold, color = Palette.Forest); Text(" /guest", fontSize = 11.sp, color = Palette.Muted)
                    Spacer(Modifier.weight(1f)); SecondaryButton("Walk-in Booking") { s.newBookingActivity = t.name; s.isAddBookingModalOpen = true }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("Edit Tour", Modifier.weight(1f), icon = Icons.Outlined.Edit) { s.editingTour = t }
                    IconButton({ s.askDeleteTour(t) }) { Icon(Icons.Outlined.Delete, "Delete tour", tint = Palette.Red) }
                }
            }
        }
    }
    if (s.myOrchards.isNotEmpty()) Grid(s.myOrchards, 3) { o ->
        GlassCard(padding = 0.dp) {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                AsyncImage(o.imageUrl, Modifier.fillMaxSize())
                Pill("Fruit Picking", Color(0xFFFEF3C7), Color(0xFF92400E), Modifier.align(Alignment.TopEnd).padding(10.dp))
            }
            Column(Modifier.padding(14.dp)) {
                Text(o.title, fontWeight = FontWeight.ExtraBold, color = Palette.Deep, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Sub("${o.fruitName} · ${o.seasonAvailability}"); Sub(o.description, maxLines = 2)
                Text("★ ${o.rating} (${o.reviewsCount}) · $PESO${o.pricePerPerson}/picker", fontSize = 12.sp, color = Palette.Forest)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("Edit", Modifier.weight(1f), icon = Icons.Outlined.Edit) { s.editingOrchard = o }
                    IconButton({ s.askDeleteOrchard(o) }) { Icon(Icons.Outlined.Delete, "Delete experience", tint = Palette.Red) }
                }
            }
        }
    }
    SectionHeader("Tourist Reviews", "What visitors say about your tours and orchards") { Pill("${tourReviews.size} reviews") }
    ReviewList(tourReviews.take(6), "No reviews for your farm yet.")
    SectionHeader("Harvest Catalog & Available Produce", "Fresh vegetables, fruits, and processed farm goods") {
        PrimaryButton("Add New Farm Product", icon = Icons.Filled.Add) { s.openAddProduct() }
    }
    if (s.farmProducts.isEmpty()) Sub("No products listed yet.")
    Grid(s.farmProducts.take(8), 4) { p ->
        GlassCard(padding = 0.dp, onClick = { s.openEditProduct(p) }) {
            Box(Modifier.fillMaxWidth().height(130.dp)) {
                AsyncImage(p.imageUrl, Modifier.fillMaxSize())
                Pill(p.category, Color.White.copy(alpha = 0.92f), Palette.Deep, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            Column(Modifier.padding(12.dp)) {
                Text(p.name, fontWeight = FontWeight.ExtraBold, color = Palette.Deep, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.farmOrigin, fontSize = 11.sp, color = Palette.Forest, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$PESO${money(p.price)} ", fontWeight = FontWeight.ExtraBold); Text(p.unit, fontSize = 11.sp, color = Palette.Muted)
                    Spacer(Modifier.weight(1f)); Text(if (p.stock > 0) "${p.stock} in stock" else "Sold out", fontSize = 11.sp, color = if (p.stock > 0) Palette.Forest else Palette.Red)
                }
            }
        }
    }
    GlassCard {
        SectionHeader("About ${f.farmName}", "Your short story and farm description") {
            PrimaryButton("Edit Story & Description", icon = Icons.Outlined.Edit) { s.isEditStoryOpen = true }
        }
        Spacer(Modifier.height(6.dp))
        Label("Short Story")
        Text(f.storyQuote.ifBlank { "No short story yet." }, fontSize = 13.sp, color = Color(0xFF334155), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        Spacer(Modifier.height(8.dp))
        Label("Description")
        Text(f.fullBiography.ifBlank { "Tell tourists about your farm — its history, what you grow and how you farm." }, fontSize = 13.sp, color = Color(0xFF334155))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoTile("Certification", f.certifications.joinToString().ifBlank { "None listed" }, Icons.Outlined.VerifiedUser, Modifier.weight(1f))
            InfoTile("Farm Size", "${f.hectares} · ${f.generation}", Icons.Outlined.Agriculture, Modifier.weight(1f))
            InfoTile("Contact & Inquiries", listOf(s.userPhone, s.userEmail).filter { it.isNotBlank() }.joinToString(" · "), Icons.Outlined.Call, Modifier.weight(1f))
        }
    }
}
