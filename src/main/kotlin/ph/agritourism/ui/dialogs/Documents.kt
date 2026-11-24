package ph.agritourism.ui.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.model.Booking
import ph.agritourism.model.FarmerProfile
import ph.agritourism.model.PAY_AT_FARM_PAYMENT_METHOD
import ph.agritourism.model.Receipt
import ph.agritourism.ui.*

/** Every bundled reference screen (PNG + JPG) from the original project. */
val MOCKUPS = listOf(
    "Admin Platform Overview (Screenshot 124705)" to "Screenshot_2026-11-05_124705_4d6a6d5e.png",
    "Sunnyvale Farm Tour Detail (Screenshot 124629)" to "Screenshot_2026-11-05_124629_d476e790.png",
    "Fruit Picking Experience (Screenshot 124640)" to "Screenshot_2026-11-05_124640_9b8d2d65.png",
    "User Dashboard (Screenshot 122601)" to "Screenshot_2026-11-05_122601_8a2486ad.png",
    "Opening / Landing Page (Screenshot 122536)" to "Screenshot_2026-11-05_122536_7411613e.png",
    "Sign Up Page (Screenshot 122529)" to "Screenshot_2026-11-05_122529_f638abde.png",
    "Explore Tours Card Grid" to "image_8057368a.png",
    "Tourist Sidebar Dock" to "image_1d3609f4.png",
    "Farmer Sidebar Dock" to "image_3765534a.png",
    "Top Header Bar" to "image_bc598aa8.png",
    "Image Registry Setup" to "image_0ebe07da.png",
    "Login Reference" to "830196540_1307778604702028_7616129147759755217_n_313267af.jpg",
    "Checkout Reference" to "830562405_1446908120780595_5049575899897324060_n_02518302.jpg",
    "Reference Screen 831436058" to "831436058_1675507177335727_7084173276363854381_n_49e0f97d.jpg",
    "Reference Screen 831566027" to "831566027_2463426264189344_3479540221659567185_n_e645b596.jpg",
    "Reference Screen 831614959" to "831614959_961886953108002_5284972476278439062_n__1__38de9e34.jpg",
    "Add Product Reference" to "833763230_1855287129250533_7787133682644372210_n_a7522d87.jpg",
    "Reference Screen 834676957" to "834676957_1639408210889122_3387635337901283178_n_f5bd3c52.jpg",
    "My Bookings Reference" to "836110931_2523123378516901_2066910804554620551_n_c0a3f1b8.jpg",
    "Farmer Dashboard Reference" to "836405251_2140279460217612_307228198746127473_n_490c3dbb.jpg",
    "Reference Screen 836917376" to "836917376_1088247587325666_1488724219261467445_n_31782a82.jpg",
    "Reference Screen 838500799" to "838500799_2684298072026078_8578891515280129110_n_ac082235.jpg",
)

@Composable
internal fun MockupGallery(s: AppState) {
    val close = { s.isMockupDrawerOpen = false }
    var zoom by remember { mutableStateOf<Pair<String, String>?>(null) }
    Modal(close, 1100.dp) {
        ModalHeader("Uploaded Design Mockups", "${MOCKUPS.size} reference screens", Icons.Outlined.Image, close)
        Column(Modifier.padding(20.dp)) {
            Grid(MOCKUPS, 3) { m ->
                Column(Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, Palette.Border, RoundedCornerShape(14.dp)).clickable { zoom = m }) {
                    AsyncImage("res:mockups/${m.second}", Modifier.fillMaxWidth().height(180.dp).background(Color(0xFFF1F5F9)), ContentScale.Fit, m.first)
                    Text(m.first, Modifier.padding(10.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    zoom?.let { m ->
        Modal({ zoom = null }, 1300.dp) {
            ModalHeader(m.first, onClose = { zoom = null })
            AsyncImage("res:mockups/${m.second}", Modifier.fillMaxWidth().heightIn(min = 400.dp, max = 680.dp).padding(12.dp), ContentScale.Fit, m.first)
        }
    }
}

@Composable
internal fun FarmerStoryModal(s: AppState, f: FarmerProfile) {
    val close = { s.selectedFarmerForModal = null }
    Modal(close, 860.dp) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            AsyncImage(f.farmBannerUrl, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60B2512)))))
            CloseAction(Modifier.align(Alignment.TopEnd).padding(10.dp), color = Palette.Deep, showLabel = false) { close() }
            Row(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(f.photoUrl, 90.dp); Spacer(Modifier.width(14.dp))
                Column {
                    Pill("${f.generation} · ${f.hectares}", Color(0xFFFEF3C7), Color(0xFF92400E))
                    Text(f.name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("${f.farmName} · ${f.location}", color = Color(0xFFBBF7D0), fontSize = 13.sp)
                }
            }
        }
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0FDF4)).padding(16.dp)) {
                Label("Farmer Philosophy & Words")
                Text("\"${f.storyQuote}\"", fontStyle = FontStyle.Italic, fontSize = 14.sp, color = Palette.Deep)
                Spacer(Modifier.height(6.dp))
                Text("${f.yearsExperience} Years Dedicated to Agriculture • ${f.roleTitle}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = Palette.Forest); Spacer(Modifier.width(6.dp)); H2("The Farmer's Journey & Heritage") }
            Text(f.fullBiography, fontSize = 13.sp, color = Color(0xFF334155))
            Text(f.farmingPhilosophy, fontSize = 13.sp, fontStyle = FontStyle.Italic, color = Palette.Forest)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) { Label("Official Certifications"); f.certifications.forEach { Text(it, fontSize = 12.sp) } }
                Column(Modifier.weight(1f)) { Label("Eco-Friendly Practices"); f.sustainablePractices.forEach { Text(it, fontSize = 12.sp) } }
            }
            Label("Farm Landscape & Harvest Photos")
            Gallery(f.gallery)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Harvested Produce Available Now", fontWeight = FontWeight.ExtraBold, color = Palette.Deep); Spacer(Modifier.weight(1f)); Sub("Direct from ${f.name}'s fields")
            }
            Grid(s.products.filter { it.id in f.productIds }, 3, 10.dp) { p ->
                val inCart = s.cart.firstOrNull { it.product.id == p.id }?.quantity ?: 0
                val soldOut = p.stock <= 0; val limit = inCart >= p.stock
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF8FAFC)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(p.imageUrl, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$PESO${money(p.price)} ${p.unit}", fontSize = 11.sp, color = Palette.Forest)
                        Text(if (soldOut) "Sold Out" else if (limit) "Max limit in cart" else "Stock: ${p.stock} left", fontSize = 10.sp, color = Palette.Muted)
                    }
                    TextButton({ s.addToCart(p) }, enabled = !soldOut && !limit) { Text(if (soldOut) "Out" else if (limit) "Max" else "+ Add", fontSize = 11.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Book Tour at ${f.farmName}", Modifier.weight(1f), Icons.Outlined.Explore) {
                    s.selectedTour = s.tours.firstOrNull { it.id == f.tourId } ?: s.tours.first(); s.selectedFarmerForModal = null; s.isTourModalOpen = true
                }
                CloseAction(onClick = { close() })
            }
        }
    }
}

@Composable
internal fun ReceiptModal(s: AppState, r: Receipt) {
    val close = { s.selectedReceipt = null }
    Modal(close, 640.dp) {
        ModalHeader("AgriTourism Philippines", "Official Electronic Sales Invoice & Receipt", Icons.Outlined.Eco, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Sub("Accredited Philippine Agri-Tourism E-Commerce Platform · BIR Registered Receipt")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoTile("Receipt Reference", "${r.receiptNumber}\n${r.issueDate}\nAuth: ${r.authReference}", modifier = Modifier.weight(1f))
                InfoTile("Customer Details", "${r.customerName}\n${r.customerPhone}\n${r.customerAddress}", modifier = Modifier.weight(1f))
            }
            Row { Label("Accredited Farm Host"); Spacer(Modifier.width(8.dp)); Text(r.farmName, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text("Host: ${r.hostFarmerName}", fontSize = 11.sp, color = Palette.Muted) }
            Row(Modifier.fillMaxWidth().background(Palette.Mint).padding(8.dp)) {
                listOf("Item Description" to 3f, "Qty" to 0.6f, "Price" to 1f, "Amount" to 1f).forEach { (h, w) -> Text(h, Modifier.weight(w), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
            }
            r.items.forEach {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    Text(it.name, Modifier.weight(3f), fontSize = 12.sp); Text("${it.quantity}", Modifier.weight(0.6f), fontSize = 12.sp)
                    Text("$PESO${money(it.unitPrice)}", Modifier.weight(1f), fontSize = 12.sp); Text("$PESO${money(it.total)}", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = Palette.Border)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Text("Subtotal: $PESO${money(r.subtotal)}", fontSize = 13.sp)
                if (r.deliveryFee > 0) Text("Logistics & Direct Delivery: $PESO${money(r.deliveryFee)}", fontSize = 13.sp)
                Text("Total Amount Paid: $PESO${money(r.totalAmount)}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest)
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(2.dp, Color(0xFF16A34A), RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PAID & VERIFIED · AGRITOURISM PH", Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, color = Color(0xFF16A34A))
                Column(horizontalAlignment = Alignment.End) { Text("||| | ||||| || |||| ||| ||||", fontFamily = FontFamily.Monospace); Text("E-RECEIPT AUTHENTICATED", fontSize = 9.sp, color = Palette.Muted) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Print Official Receipt (PDF / Paper)", Modifier.weight(1f), Icons.Outlined.Print) { Printing.openHtml("${r.receiptNumber}.html", Printing.receiptHtml(r)) }
                CloseAction(onClick = { close() })
            }
        }
    }
}

@Composable
internal fun TicketModal(s: AppState, b: Booking) {
    val close = { s.selectedBookingForTicket = null }
    Modal(close, 700.dp) {
        Row(Modifier.fillMaxWidth().background(Palette.Deep).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ConfirmationNumber, null, tint = Color(0xFF86EFAC)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("PHILIPPINE AGRITOURISM", color = Color(0xFF86EFAC), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text("Official Digital Tour Pass & E-Ticket", color = Color.White, fontWeight = FontWeight.ExtraBold)
            }
        }
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Pill("Pass Reference: ${b.bookingCode}", Palette.Deep, Color.White)
                    Text(b.farmName, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
                    Text(b.activity, fontSize = 13.sp, color = Palette.Forest)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Label("Total Admission Paid"); Text("$PESO${money(b.totalAmount)}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest)
                    Sub("Payment method: ${b.paymentMethod ?: PAY_AT_FARM_PAYMENT_METHOD}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("Tour Date", b.tourDate, Icons.Outlined.CalendarMonth, Modifier.weight(1f))
                InfoTile("Scheduled Slot", b.tourTime.split("-").first().trim(), Icons.Outlined.Schedule, Modifier.weight(1f))
                InfoTile("Lead Visitor", b.customerName, Icons.Outlined.Person, Modifier.weight(1f))
                InfoTile("Party Size", "${b.participants} Guests", Icons.Outlined.Groups, Modifier.weight(1f))
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0FDF4)).padding(14.dp)) {
                Row { Text("Assigned Agro-Guide: ${b.assignedGuide ?: "To be assigned"}", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold); Pill("Gate 1 Welcome Pavilion", Color.White) }
                Spacer(Modifier.height(6.dp))
                Text("Pass Inclusions: Welcome native honey-lemonade drink, guided educational trail walk, sanitized harvesting basket, farm safety sun hat, and free parking access.", fontSize = 12.sp)
            }
            HorizontalDivider(color = Palette.Border, thickness = 2.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Gate Turnstile Instructions")
                    Text("Present this digital ticket on your screen or printed paper at the farm gate scanner. Arrive 15 minutes prior to your time slot.", fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Save Ticket") {
                    if (Printing.saveHtmlAs("AgriTourism-${b.bookingCode}.html", Printing.ticketHtml(b)) != null) {
                        s.showToast("Ticket saved.")
                    }
                }
                PrimaryButton("Print Ticket") {
                    Printing.openHtml("AgriTourism-${b.bookingCode}.html", Printing.ticketHtml(b, autoPrint = true))
                }
                CloseAction(onClick = { close() })
            }
        }
    }
}
