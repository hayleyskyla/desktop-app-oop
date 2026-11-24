package ph.agritourism.ui.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.model.Booking
import ph.agritourism.model.TourPriceStatus
import ph.agritourism.model.TourPriceType
import ph.agritourism.model.bookingTotal
import ph.agritourism.model.slotTime
import ph.agritourism.model.Role
import ph.agritourism.model.NO_BOOKING_SCHEDULE_MESSAGE
import ph.agritourism.ui.*

@Composable
internal fun Gallery(images: List<String>) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    images.forEach { AsyncImage(it, Modifier.weight(1f).height(80.dp).clip(RoundedCornerShape(12.dp))) }
}

@Composable
private fun HeroImage(src: String, tag: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(240.dp)) {
        AsyncImage(src, Modifier.fillMaxSize())
        CloseAction(Modifier.align(Alignment.TopEnd).padding(10.dp), color = Palette.Deep, showLabel = false, onClick = onClose)
        Pill(tag, Color.White.copy(alpha = 0.92f), Palette.Deep, Modifier.align(Alignment.BottomStart).padding(14.dp))
    }
}

@Composable
private fun TotalBox(left: String, payment: String, total: Int) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Mint).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(left, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(payment, fontSize = 11.sp, color = Palette.Muted) }
        Column(horizontalAlignment = Alignment.End) { Label("Automatic Total Calculation:"); Text("$PESO${money(total)}", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest) }
    }
}

@Composable
internal fun TourModal(s: AppState) {
    val t = s.selectedTour
    val close = { s.isTourModalOpen = false }
    val remaining = s.availablePaxFor(t)
    val capacity = s.capacityFor(t.farmerId)
    val status = s.availabilityFor(t)
    LaunchedEffect(t.id, remaining) { if (s.tourGuests > remaining.coerceAtLeast(1)) s.tourGuests = remaining.coerceAtLeast(1) }
    val total = t.bookingTotal(s.tourGuests)
    val priceDescription = when (t.priceType) {
        TourPriceType.PER_PERSON -> "$PESO${t.pricePerPerson} per person"
        TourPriceType.PER_GROUP -> "$PESO${t.pricePerPerson} per group"
        TourPriceType.PER_PACKAGE -> "$PESO${t.pricePerPerson} per package"
    }
    Modal(close, 760.dp) {
        HeroImage(t.imageUrl, t.location, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Sub("${t.location} • ")
                        if (t.reviewsCount > 0) RatingText(t.rating, t.reviewsCount) else Sub("New listing")
                        Sub(" • "); Pill(t.highlightTag)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("$PESO${t.pricePerPerson}", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest)
                    Text(t.priceType.label, fontSize = 11.sp, color = Palette.Muted)
                    Pill(t.priceStatus.label, if (t.priceStatus == TourPriceStatus.ESTIMATED) Color(0xFFFFF3D1) else Palette.Mint,
                        if (t.priceStatus == TourPriceStatus.ESTIMATED) Color(0xFF92400E) else Palette.Forest)
                }
            }
            Text(t.description, fontSize = 13.sp, color = Color(0xFF334155))
            if (s.canEditTour(t)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Edit Tour", icon = Icons.Outlined.Edit) { close(); s.editingTour = t }
                SecondaryButton("Delete Tour", icon = Icons.Outlined.Delete, color = Palette.Red) { s.askDeleteTour(t) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("Operating Hours", t.operatingHours.ifBlank { "Pending admin configuration" }, Icons.Outlined.Schedule, Modifier.weight(1f))
                InfoTile("Amenities", t.amenities.joinToString().ifBlank { "Not confirmed" }, Icons.Outlined.VerifiedUser, Modifier.weight(1f))
                InfoTile("Activities", t.activities.joinToString(), Icons.Outlined.AutoAwesome, Modifier.weight(1f))
            }
            if (t.estimatedDuration.isNotBlank()) InfoTile("Estimated Duration", t.estimatedDuration, Icons.Outlined.Schedule)
            Row { Label("Tour Gallery Photo Highlights"); Spacer(Modifier.weight(1f)); Label("${t.thumbnails.size} Photos") }
            Gallery(t.thumbnails)
            Label("Tourist Reviews (${s.reviewsFor("tour", t.id).size})")
            ReviewList(s.reviewsFor("tour", t.id).take(5), "No reviews for this tour yet.")
            HorizontalDivider(color = Palette.Border)
            if (!t.bookingEnabled) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFFBEB)).padding(14.dp)) {
                    Text("Bookings are currently unavailable for this tour.", fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
                    Text("Listed price: $PESO${money(t.pricePerPerson)} ${t.priceType.label} · ${t.priceStatus.label}", fontSize = 12.sp)
                    if (t.priceSuggestionBasis.isNotBlank()) Sub(t.priceSuggestionBasis)
                    if (t.priceSuggestionSource.isNotBlank()) Text("Source: ${t.priceSuggestionSource}", fontSize = 11.sp, color = Palette.Forest)
                    Sub("The farm must have a valid tour schedule before an administrator can enable bookings.")
                    AvailabilityPill(status, remaining, capacity)
                }
            } else {
                Text("Customize Your Tour Booking", fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Number of Persons")
                        Sub("Farm capacity: $capacity · Remaining: $remaining")
                    }
                    Stepper(s.tourGuests, "Pax", max = remaining.coerceAtLeast(1)) { s.tourGuests = it }
                    Spacer(Modifier.width(10.dp))
                    AvailabilityPill(status, remaining, capacity)
                }
                if (remaining <= 0) Text("This farm is fully booked. Choose another date or listing.", fontSize = 12.sp, color = Palette.Red)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateField("Tour Schedule Date", s.tourDate, { s.tourDate = it }, Modifier.weight(1f))
                    val slots = s.availableSlotsFor("tour", t.id)
                    LaunchedEffect(t.id, slots) { if (s.tourTime !in slots) s.tourTime = slots.firstOrNull().orEmpty() }
                    Column(Modifier.weight(1f)) {
                        Dropdown("Select Time Slot", slots, s.tourTime, { it.ifBlank { "No time slots available" } }) { s.tourTime = it }
                        if (slots.isEmpty()) Sub(NO_BOOKING_SCHEDULE_MESSAGE)
                    }
                }
                Label("Select Tour Payment Method")
                ChoiceRow(TOUR_PAYMENT_METHODS, s.tourPaymentMethod) { s.tourPaymentMethod = it }
                TotalBox("$priceDescription × ${s.tourGuests}", "Payment via ${s.tourPaymentMethod}", total)
                PrimaryButton("Confirm", Modifier.fillMaxWidth(), Icons.Outlined.EventAvailable,
                    enabled = isValidDate(s.tourDate) && s.tourTime in s.availableSlotsFor("tour", t.id) &&
                        s.tourGuests in 1..remaining.coerceAtLeast(0)) { s.bookSelectedTour() }
            }
        }
    }
}

@Composable
internal fun FruitModal(s: AppState) {
    val f = s.selectedFruit
    val close = { s.isFruitModalOpen = false }
    val remaining = s.availablePaxFor(f)
    val capacity = s.capacityFor(f.farmerId)
    val status = s.availabilityFor(f)
    LaunchedEffect(f.id, remaining) { if (s.fruitGuests > remaining.coerceAtLeast(1)) s.fruitGuests = remaining.coerceAtLeast(1) }
    Modal(close, 760.dp) {
        HeroImage(f.imageUrl, f.location, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(f.title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
            Text("${f.farmName} · ${f.seasonAvailability}", fontSize = 13.sp, color = Palette.Forest, fontWeight = FontWeight.Bold)
            Text(f.description, fontSize = 13.sp, color = Color(0xFF334155))
            if (s.canEditOrchard(f)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Edit", icon = Icons.Outlined.Edit) { close(); s.editingOrchard = f }
                SecondaryButton("Delete", icon = Icons.Outlined.Delete, color = Palette.Red) { s.askDeleteOrchard(f) }
            }
            Row { Label("Fruit Picking Gallery Photos"); Spacer(Modifier.weight(1f)); Label("${f.thumbnails.size} Photos") }
            Gallery(f.thumbnails)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Includes harvest basket and dedicated farm guide", fontSize = 13.sp)
                Text("✓ Duration: ${f.duration}", fontSize = 13.sp)
                Text("✓ Available seasonal fruits: ${f.seasonalFruits}", fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Label("Tourist Reviews (${s.reviewsFor("orchard", f.id).size}) "); RatingText(f.rating, f.reviewsCount) }
            ReviewList(s.reviewsFor("orchard", f.id).take(5), "No reviews for this orchard yet.")
            HorizontalDivider(color = Palette.Border)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("Select Date", s.fruitDate, { s.fruitDate = it }, Modifier.weight(1f))
                val slots = s.availableSlotsFor("orchard", f.id)
                LaunchedEffect(f.id, slots) { if (s.fruitTime !in slots) s.fruitTime = slots.firstOrNull().orEmpty() }
                Column(Modifier.weight(1f)) {
                    Dropdown("Select Time Slot", slots, s.fruitTime, { it.ifBlank { "No time slots available" } }) { s.fruitTime = it }
                    if (slots.isEmpty()) Sub(NO_BOOKING_SCHEDULE_MESSAGE)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Number of Fruit Pickers")
                    Sub("Includes individual harvesting basket · Remaining: $remaining of $capacity")
                }
                Stepper(s.fruitGuests, "Pax", max = remaining.coerceAtLeast(1)) { s.fruitGuests = it }
                Spacer(Modifier.width(10.dp))
                AvailabilityPill(status, remaining, capacity)
            }
            if (remaining <= 0) Text("This orchard is fully booked. Choose another listing.", fontSize = 12.sp, color = Palette.Red)
            Label("Select Fruit Picking Payment Method")
            ChoiceRow(TOUR_PAYMENT_METHODS, s.fruitPaymentMethod) { s.fruitPaymentMethod = it }
            TotalBox("$PESO${f.pricePerPerson} × ${s.fruitGuests} ${if (s.fruitGuests == 1) "picker" else "pickers"}", "Payment via ${s.fruitPaymentMethod}", f.pricePerPerson * s.fruitGuests)
            PrimaryButton("Confirm", Modifier.fillMaxWidth(), Icons.Outlined.AutoAwesome,
                enabled = isValidDate(s.fruitDate) && s.fruitTime in s.availableSlotsFor("orchard", f.id) &&
                    s.fruitGuests in 1..remaining.coerceAtLeast(0)) { s.bookSelectedFruit() }
        }
    }
}

@Composable
internal fun BookingDossier(s: AppState, b0: Booking) {
    val b = s.bookings.firstOrNull { it.id == b0.id } ?: b0
    val close = { s.selectedBookingForView = null }
    val farmer = s.isStaff
    Modal(close, 720.dp) {
        ModalHeader(if (farmer) "Tourist Reservation Dossier" else "Tourist E-Ticket & Pass", "Official AgriTourism Tour Voucher", Icons.Outlined.Eco, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Pill(b.bookingCode, Palette.Deep, Color.White)
                    Text(b.farmName, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep); Sub(b.activity)
                }
                Column(horizontalAlignment = Alignment.End) { StatusPill(b.status); Text("$PESO${money(b.totalAmount)}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("Tour Date", b.tourDate, Icons.Outlined.CalendarMonth, Modifier.weight(1f))
                InfoTile("Scheduled Time", b.tourTime, Icons.Outlined.Schedule, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("Tourist / Guest", b.customerName, Icons.Outlined.Person, Modifier.weight(1f))
                InfoTile("Group Size", "${b.participants} Persons", Icons.Outlined.Groups, Modifier.weight(1f))
            }
            InfoTile("Tourist Contact Information", "Phone: ${b.customerPhone ?: "not provided"} · Email: ${b.customerEmail ?: "walk-in guest"}", modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFFBEB)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Label("1-Day Attendance Confirmation"); Text(b.attendanceStatus ?: "Pending Confirmation", fontWeight = FontWeight.Bold) }
                if (b.attendanceStatus == "Pending Confirmation" && s.ownsBooking(b)) {
                    PrimaryButton("Confirm Yes") { s.attendanceConfirmation(b.id, true) }; Spacer(Modifier.width(6.dp))
                    SecondaryButton("No", color = Palette.Red) { s.attendanceConfirmation(b.id, false) }
                }
            }
            Row { Text("Tourist Uploaded Documents & Dossier", fontWeight = FontWeight.ExtraBold, color = Palette.Deep); Spacer(Modifier.weight(1f)); Pill(if (b.uploadedIdDoc != null) "ID on file" else "No ID on file") }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("Uploaded Valid ID", b.uploadedIdDoc ?: "Not uploaded", Icons.Outlined.VerifiedUser, Modifier.weight(1f))
                InfoTile("Payment", b.uploadedReceiptDoc ?: b.paymentMethod ?: "Payment method not set", Icons.Outlined.CreditCard, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("Special Requests & Notes", b.specialRequests ?: "No special dietary or accessibility requests.", modifier = Modifier.weight(1f))
                InfoTile("Assigned Tour Guide", b.assignedGuide ?: "Not yet assigned", Icons.Outlined.Explore, Modifier.weight(1f))
            }
            if (farmer && s.canManageBooking(b) && b.status != "Cancelled") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0FDF4)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row { Text("Farmer Host Gate Controls", Modifier.weight(1f), fontWeight = FontWeight.ExtraBold); Pill("Status: ${b.attendanceStatus ?: "Pending"}", Color.White) }
                    if (b.attendanceStatus == "Arrived & Checked In") Text("✓ Tourist has arrived & Gate Check-In is recorded! ${b.checkedInAt ?: ""}", color = Color(0xFF166534), fontWeight = FontWeight.Bold)
                    else PrimaryButton("Mark Tourist as Arrived (Gate Check-In)", icon = Icons.Outlined.CheckBox) { s.checkInBooking(b.id) }
                    val guide = b.assignedGuide.orEmpty()
                    Dropdown("Assign Guide:", (listOf(guide) + s.guidesFor(b)).distinct(), guide, { it.ifEmpty { "Choose a guide…" } }) { if (it.isNotEmpty()) s.assignGuide(b.id, it) }
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Palette.Border, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("AgriTourism QR Check-in"); Text("Show this code at the farm entrance", fontWeight = FontWeight.Bold)
                    Sub("Verified by Department of Tourism Accredited Hosts")
                }
                QrGlyph()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Save / Print Ticket", icon = Icons.Outlined.Download) { Printing.openHtml("voucher-${b.bookingCode}.html", Printing.bookingDossierHtml(b)) }
                CloseAction(onClick = { close() })
            }
        }
    }
}

@Composable
private fun QrGlyph() {
    val bits = listOf(1, 1, 0, 1, 1, 0, 1, 0, 0, 1, 1, 1, 1, 0, 1, 1)
    Column(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, Palette.Deep, RoundedCornerShape(8.dp)).padding(6.dp)) {
        bits.chunked(4).forEach { row ->
            Row(Modifier.weight(1f)) { row.forEach { Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp).background(if (it == 1) Palette.Deep else Color.White)) } }
        }
    }
}

@Composable
internal fun OrderDossier(s: AppState, id: String) {
    val o = s.farmerOrders.firstOrNull { it.id == id } ?: return
    if (!s.canViewOrder(o)) return
    val close = { s.selectedOrderForView = null }
    var address by remember(o.id, o.customerAddress) { mutableStateOf(o.customerAddress) }
    Modal(close, 680.dp) {
        ModalHeader("Produce Order Dossier #${o.orderNumber}", "Tourist Order & Payment", Icons.Outlined.Inventory2, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Label("Customer")
                    Text(o.customerName, fontWeight = FontWeight.ExtraBold)
                    Sub(o.customerPhone)
                    if (!s.isTourist || !s.canUpdateOrderAddress(o)) Sub(o.customerAddress)
                }
                Column(horizontalAlignment = Alignment.End) { Label("Fulfillment Status"); StatusPill(o.fulfillmentStatus); Text("$PESO${money(o.totalAmount)}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest) }
            }
            if (s.canUpdateOrderAddress(o)) {
                Field("Delivery Address", address, { address = it }, singleLine = false, minLines = 2, maxLength = 300)
            }
            o.specialDeliveryNotes?.let { Text("Delivery Note: $it", fontSize = 12.sp, color = Color(0xFF92400E)) }
            Label("Ordered Items:")
            o.items.forEach { Row { Text("${it.productName} × ${it.qty}", Modifier.weight(1f), fontSize = 13.sp); Text("$PESO${money(it.qty * it.unitPrice)}", fontWeight = FontWeight.Bold) } }
            Row { Label("Payment"); Spacer(Modifier.weight(1f)); Pill(o.paymentStatus, Color(0xFFDCFCE7), Color(0xFF166534)) }
            if (s.isTourist) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.canUpdateOrderAddress(o)) {
                        PrimaryButton("Update Address", enabled = address.trim().length in 5..300) { s.updateOrderAddress(o.id, address) }
                    }
                    if (s.canCancelOrder(o)) {
                        SecondaryButton("Cancel Order", color = Palette.Red) {
                            s.confirm("Cancel order #${o.orderNumber}?", "The items will be returned to stock and the farm will be notified.", "Confirm") {
                                s.cancelOrder(o.id)
                            }
                        }
                    }
                    CloseAction(onClick = { close() })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (o.fulfillmentStatus == "Pending" && s.canActOnOrder(o)) {
                        PrimaryButton("Confirm", icon = Icons.Outlined.CheckCircle) { s.orderStatusUpdate(o.id, "Confirmed") }
                    }
                    if (s.canCancelOrder(o)) {
                        SecondaryButton("Cancel Order", icon = Icons.Outlined.Cancel, color = Palette.Red) { s.cancelOrder(o.id) }
                    }
                    if (s.canActOnOrder(o)) {
                        PrimaryButton("Print Order Details") { Printing.openHtml("order-${o.orderNumber}.html", Printing.orderHtml(listOf(o), "Produce Order Details #${o.orderNumber}")) }
                    }
                    CloseAction(onClick = { close() })
                }
            }
        }
    }
}

@Composable
internal fun EditBookingModal(s: AppState, e: Booking) {
    val close = { s.editingBooking = null }
    Modal(close, 520.dp) {
        ModalHeader("Reschedule Tour", "Update Reservation #${e.bookingCode}", Icons.Outlined.Edit, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Farm Destination", e.farmName, {}, enabled = false)
            // Seeded bookings use "Nov 20, 2026" display dates; editing normalises them to ISO.
            val dateValue = parseTourDate(e.tourDate)?.toString() ?: e.tourDate
            DateField("Tour Date", dateValue, { s.editingBooking = e.copy(tourDate = it) })
            // Keep the current slot selectable even if the farm has since changed its slots.
            val slots = s.slotsFor(e)
            val cur = slots.firstOrNull { slotTime(it) == slotTime(e.tourTime) } ?: e.tourTime
            Dropdown("Time Slot", (listOf(cur) + slots).distinct(), cur, { it }) { s.editingBooking = e.copy(tourTime = it) }
            Text("Total: $PESO${money(e.totalAmount)}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Save Updated Schedule", Modifier.weight(1f), enabled = isValidDate(dateValue)) {
                    s.editingBooking = e.copy(tourDate = dateValue)
                    s.saveEditedBooking()
                }
                SecondaryButton("Cancel") { close() }
            }
        }
    }
}

@Composable
internal fun DeleteConfirm(s: AppState, id: Long) {
    AlertDialog({ s.deletingBookingId = null },
        icon = { Icon(Icons.Outlined.Delete, null, tint = Palette.Red) },
        title = { Text("Delete this reservation?") },
        text = { Text("This will permanently delete this booking from your list. Are you sure you want to proceed?") },
        confirmButton = { PrimaryButton("Yes, Delete", color = Palette.Red) { s.deleteBooking(id) } },
        dismissButton = { SecondaryButton("Keep Booking") { s.deletingBookingId = null } })
}
