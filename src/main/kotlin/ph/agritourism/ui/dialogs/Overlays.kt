package ph.agritourism.ui.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.model.BookingDateWindow
import ph.agritourism.model.Product
import ph.agritourism.model.Role
import ph.agritourism.model.bookingTotal
import ph.agritourism.model.NO_BOOKING_SCHEDULE_MESSAGE
import ph.agritourism.ui.*
import java.time.Instant
import java.time.ZoneOffset

@Composable
fun Overlays(s: AppState) {
    if (s.isNotificationsOpen && s.isLoggedIn) NotificationsDrawer(s)
    s.selectedNotification?.let { NotificationDetail(s, it.id) }
    if (s.isProfileModalOpen) ProfileModal(s)
    if (s.isAddProductModalOpen) AddProductModal(s)
    if (s.isAddBookingModalOpen) AddBookingModal(s)
    if (s.isTourModalOpen) TourModal(s)
    if (s.isFruitModalOpen) FruitModal(s)
    s.selectedBookingForView?.let { BookingDossier(s, it) }
    s.selectedOrderForView?.let { OrderDossier(s, it.id) }
    s.selectedProductForView?.let { ProductDetailModal(s, it) }
    s.editingBooking?.let { EditBookingModal(s, it) }
    s.reviewingBooking?.let { ReviewModal(s, it) }
    s.deletingBookingId?.let { DeleteConfirm(s, it) }
    if (s.isMockupDrawerOpen) MockupGallery(s)
    s.selectedFarmerForModal?.let { FarmerStoryModal(s, it) }
    s.selectedReceipt?.let { ReceiptModal(s, it) }
    s.selectedBookingForTicket?.let { TicketModal(s, it) }
    s.catalogPreview?.let { CatalogPreviewModal(s, it) }
    if (s.isAddTourModalOpen) AddActivityModal(s, orchard = false)
    if (s.isAddOrchardModalOpen) AddActivityModal(s, orchard = true)
    if (s.isEditStoryOpen) (s.editingFarmerStory ?: s.myFarm)?.let { EditStoryModal(s, it) }
    s.editingTour?.let { t -> key(t.id) { AddActivityModal(s, orchard = false, tour = t) } }
    s.editingOrchard?.let { o -> key(o.id) { AddActivityModal(s, orchard = true, orchardItem = o) } }
    s.pendingConfirm?.let { c ->
        AlertDialog({ s.pendingConfirm = null },
            icon = { Icon(Icons.Outlined.Delete, null, tint = Palette.Red) },
            title = { Text(c.title) }, text = { Text(c.message) },
            confirmButton = { PrimaryButton(c.yesLabel, color = Palette.Red) { s.pendingConfirm = null; c.action() } },
            dismissButton = { SecondaryButton(c.cancelLabel) { s.pendingConfirm = null } })
    }
}

// ------------------------------------------------------------------ notifications
@Composable
private fun NotificationsDrawer(s: AppState) {
    Box(Modifier.fillMaxSize().background(Color(0x33000000)).clickable(remember { MutableInteractionSource() }, null) { s.isNotificationsOpen = false }) {
        Column(Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 16.dp).width(420.dp).heightIn(max = 640.dp).shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)).background(Color.White).clickable(remember { MutableInteractionSource() }, null) {}) {
            Row(Modifier.fillMaxWidth().background(Palette.Deep).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Notifications, null, tint = Color(0xFF86EFAC)); Spacer(Modifier.width(8.dp))
                Text("Notification", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.ExtraBold)
                if (s.unreadCount > 0) Pill("${s.unreadCount} New", Color(0xFF86EFAC), Palette.Deep)
                TextAction("Close", color = Color.White) { s.isNotificationsOpen = false }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (s.roleFilteredNotifications.isEmpty()) Sub("No new items for this account.", Modifier.padding(24.dp))
                s.roleFilteredNotifications.forEach { n ->
                    Column(Modifier.fillMaxWidth().background(if (n.read) Color.White else Color(0xFFF0FDF4)).clickable { s.openNotification(n) }.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(when (n.type) { "attendance_reminder" -> "1-Day Pre-Tour Reminder"; "farmer_attendance_alert" -> "Visitor Arrival"; "booking_update" -> "Booking Update"; "farmer_order" -> "Produce Order"; "order" -> "Marketplace Order"; "farmer_booking_alert" -> "New Tour Booking"; else -> "Harvest Update" })
                            Spacer(Modifier.weight(1f)); if (!n.read) Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Amber))
                        }
                        Text(n.title, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Palette.Deep)
                        Sub(n.desc)
                        val bid = n.bookingId
                        if (n.type == "attendance_reminder" && n.attendanceStatus == "Pending Confirmation" && bid != null) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryButton("YES, I WILL ATTEND") { s.attendanceConfirmation(bid, true) }
                                SecondaryButton("NO, CANCEL TOUR", color = Palette.Red) { s.attendanceConfirmation(bid, false) }
                            }
                        }
                        Row { Text(n.time, fontSize = 11.sp, color = Palette.Muted); Spacer(Modifier.weight(1f)); Text("Click to view full detail →", fontSize = 11.sp, color = Palette.Forest) }
                    }
                    HorizontalDivider(color = Palette.Border)
                }
            }
        }
    }
}

@Composable
private fun NotificationDetail(s: AppState, id: Long) {
    val n = s.notifications.firstOrNull { it.id == id } ?: s.selectedNotification ?: return
    val close = { s.selectedNotification = null }
    Modal(close, 520.dp) {
        ModalHeader(n.title, when (n.type) { "attendance_reminder" -> "Tour Attendance Confirmation"; "farmer_attendance_alert" -> "Host Reservation"; "booking_update" -> "Booking Update"; else -> "Farm Update" },
            Icons.Outlined.Notifications, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(n.fullDetail, fontSize = 14.sp, color = Color(0xFF334155))
            Row { Text("Status: Read", fontSize = 11.sp, color = Palette.Forest); Spacer(Modifier.weight(1f)); Text(n.time, fontSize = 11.sp, color = Palette.Muted) }
            val bid = n.bookingId
            if (n.type == "attendance_reminder" && bid != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFFBEB)).padding(14.dp)) {
                    Text("Will you proceed with your scheduled farm tour tomorrow?", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("YES, I WILL ATTEND", icon = Icons.Outlined.CheckBox) { s.attendanceConfirmation(bid, true); close() }
                        SecondaryButton("NO, CANCEL TOUR", icon = Icons.Outlined.Cancel, color = Palette.Red) { s.attendanceConfirmation(bid, false); close() }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(when {
                    n.type == "farmer_order" || s.isAdmin && n.type == "order" -> "View Marketplace Orders →"
                    n.type == "order" -> "View My Purchases →"
                    n.type == "market" -> "Explore Farm Produce →"
                    else -> "View Bookings Page →"
                }) {
                    close(); s.isNotificationsOpen = false
                    s.navigate(when {
                        n.type == "farmer_order" || s.isAdmin && n.type == "order" -> Page.FARMER_ORDERS
                        n.type == "order" -> Page.MY_PURCHASES
                        n.type == "market" -> Page.MARKET
                        else -> Page.MY_BOOKINGS
                    })
                }
                TextAction("Close") { close() }
            }
        }
    }
}

// ------------------------------------------------------------------ profile
@Composable
private fun ProfileModal(s: AppState) {
    val close = { s.isProfileModalOpen = false }
    Modal(close, 440.dp) {
        ModalHeader("Profile Information", onClose = close)
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Avatar(s.avatar, 96.dp)
                IconButton({ pickImageFile("Choose profile photo")?.let(s::changeAvatar) },
                    Modifier.size(32.dp).clip(CircleShape).background(Palette.Forest).handCursor()) {
                    Icon(Icons.Outlined.PhotoCamera, "Change photo", Modifier.size(16.dp), tint = Color.White)
                }
            }
            TextButton({ pickImageFile("Choose profile photo")?.let(s::changeAvatar) }, Modifier.handCursor()) { Text("Change Photo", fontWeight = FontWeight.Bold) }
            var name by remember { mutableStateOf(s.displayName) }
            var email by remember { mutableStateOf(s.userEmail) }
            var phone by remember { mutableStateOf(s.currentUser?.phone.orEmpty()) }
            var location by remember { mutableStateOf(s.currentUser?.address.orEmpty()) }
            var error by remember { mutableStateOf<String?>(null) }
            H2(s.displayName); Pill("${s.userRole.key} Account".uppercase())
            Field("Email Address", email, { email = it.trim() }, Modifier.fillMaxWidth(), "you@email.com", icon = Icons.Outlined.Mail, testTag = "profile-email")
            Field("Full Name", name, { name = it }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Person, testTag = "profile-name")
            Field("Contact Phone", phone, { v -> phone = v.filter { it.isDigit() || it in "+ -()" } }, Modifier.fillMaxWidth(), "+63 912 345 6789", icon = Icons.Outlined.Phone, testTag = "profile-phone")
            Field("Location", location, { location = it }, Modifier.fillMaxWidth(), "City or address", icon = Icons.Outlined.Place, testTag = "profile-location")
            ErrorBanner(error)
            PrimaryButton("Save Changes", Modifier.fillMaxWidth()) {
                val changed = name.trim() != s.displayName || !email.trim().equals(s.userEmail, true) ||
                    phone.trim() != s.currentUser?.phone.orEmpty() || location.trim() != s.currentUser?.address.orEmpty()
                error = when {
                    !changed -> null
                    email.isBlank() -> "Email cannot be empty."
                    else -> s.updateProfile(name, phone, location, email)
                }
                if (error == null) close()
            }
        }
    }
}

@Composable
private fun ProductDetailModal(s: AppState, product: Product) {
    val close = { s.selectedProductForView = null }
    val quantity = s.cart.firstOrNull { it.product.id == product.id }?.quantity ?: 0
    val farm = s.farmers.firstOrNull { it.id == product.farmerId }
    val farmerName = farm?.name
        ?: s.userAccounts.firstOrNull { it.farmerId == product.farmerId && it.role == Role.FARMER }?.fullName
        ?: "—"
    val farmName = farm?.farmName ?: product.farmOrigin
    val farmLocation = farm?.let { listOf(it.location, it.province).filter { value -> value.isNotBlank() }.joinToString(", ") }.orEmpty().ifBlank { "—" }
    Modal(close, 560.dp) {
        AsyncImage(product.imageUrl, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)), contentDescription = "Picture of ${product.name}")
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    H1(product.name)
                    Sub(product.category)
                }
                Text("$PESO${money(product.discountedPrice)} ${product.unit}", fontWeight = FontWeight.ExtraBold, color = Palette.Forest)
            }
            HorizontalDivider(color = Palette.Border)
            Text("Product Details", fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
            Text("Product Name: ${product.name}", fontSize = 13.sp, color = Palette.Ink)
            Text("Farmer Name: $farmerName", fontSize = 13.sp, color = Palette.Ink)
            Text("Farm Name: $farmName", fontSize = 13.sp, color = Palette.Ink)
            Text("Farm Location: $farmLocation", fontSize = 13.sp, color = Palette.Ink)
            Text("Description: ${product.description}", fontSize = 13.sp, color = Palette.Ink)
            Text(if (product.stock > 0) "${product.stock} available · $quantity in your cart" else "Currently sold out",
                fontSize = 12.sp, color = if (product.stock > 0) Palette.Forest else Palette.Red, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Add to Cart", Modifier.weight(1f), Icons.Filled.Add, enabled = product.stock > quantity) {
                    s.addToCart(product)
                }
                TextAction("Back") { close() }
            }
        }
    }
}

// ------------------------------------------------------------------ add product
@Composable
private fun AddProductModal(s: AppState) {
    val close = { s.isAddProductModalOpen = false; s.editingProductId = null }
    val editing = s.editingProductId != null
    Modal(close, 620.dp) {
        if (editing) ModalHeader("Edit Farm Product", "Update the photo, details and description shown to customers", Icons.Outlined.Edit, close)
        else ModalHeader("Add Farm Product with Photo", "Publish organic harvest with picture to the local marketplace", Icons.Outlined.Spa, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(s.newProductImage, Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)), contentDescription = "Product preview")
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Product Photo · JPG, PNG or presets")
                    SecondaryButton("Upload Picture from Device", icon = Icons.Outlined.Upload) {
                        pickImageFile("Choose product photo")?.let(s::uploadProductImage)
                    }
                    Sub("Or choose fresh harvest preset:")
                    val presets = listOf("Carabao Mango" to s.images.presetMangoImage, "Organic Tomatoes" to s.images.presetTomatoImage,
                        "Sweet Papaya" to s.images.presetPapayaImage, "Strawberries" to s.images.presetStrawberryImage,
                        "Wild Honey" to s.images.presetHoneyImage, "Fresh Eggplant" to s.images.presetEggplantImage)
                    presets.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (l, u) ->
                                val sel = s.newProductImage == u
                                Text(l, Modifier.clip(RoundedCornerShape(50)).background(if (sel) Palette.Forest else Palette.Mint)
                                    .clickable { s.newProductImage = u; s.showToast("Selected \"$l\" photo!") }.padding(horizontal = 10.dp, vertical = 5.dp),
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (sel) Color.White else Palette.Deep)
                            }
                        }
                    }
                }
            }
            if (s.isAdmin) {
                val farmOptions = listOf(0) + s.farmers.map { it.id }
                Dropdown("Farm Seller", farmOptions, s.adminListingFarmId ?: 0, { id ->
                    if (id == 0) "Unassigned / Platform" else s.farmers.first { it.id == id }.farmName
                }) { s.adminListingFarmId = it }
            }
            Field("Product Name", s.newProductName, { s.newProductName = it }, placeholder = "e.g. Fresh Carabao Mangoes")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Dropdown("Category", s.productCategories, s.newProductCategory, { it }, Modifier.weight(1f)) { s.newProductCategory = it }
                Field("Price (₱)", s.newProductPrice, { v -> s.newProductPrice = v.filter { it.isDigit() || it == '.' } }, Modifier.weight(1f), "e.g. 120", number = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Packaging Unit", s.newProductUnit, { s.newProductUnit = it }, Modifier.weight(1f), "e.g. /kg, /bundle, /tray")
                Field("Stock Limit (Max Inventory)", s.newProductStock, { v -> s.newProductStock = v.filter(Char::isDigit) }, Modifier.weight(1f), "e.g. 25", number = true)
                Field("Discount (%)", s.newProductDiscount, { v -> s.newProductDiscount = v.filter(Char::isDigit).take(2) }, Modifier.weight(0.7f), "0 - 90", number = true)
            }
            Field("Description", s.newProductDesc, { s.newProductDesc = it }, placeholder = "Describe freshness, harvest method, origin...", singleLine = false, minLines = 2)
            PrimaryButton(if (editing) "Save Changes" else "Save Product & Publish with Photo", Modifier.fillMaxWidth(),
                enabled = s.newProductName.isNotBlank() && (s.newProductStock.toIntOrNull() ?: 0) >= (if (editing) 0 else 1)) { s.saveProduct() }
            s.editingProductId?.let { id ->
                SecondaryButton("Delete Product", Modifier.fillMaxWidth(), Icons.Outlined.Delete, color = Palette.Red) {
                    s.confirm("Delete \"${s.newProductName}\"?", "It will be removed from the market and from customers' carts. Past orders keep it.") { s.deleteProduct(id) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ walk-in booking
@Composable
private fun AddBookingModal(s: AppState) {
    val close = { s.isAddBookingModalOpen = false }
    val close0 = { s.isAddBookingModalOpen = false }
    if (s.myTours.isEmpty()) {
        Modal(close0, 480.dp) {
            ModalHeader("Create Tour Booking", onClose = close0)
            Column(Modifier.padding(22.dp)) {
                Sub("Your farm has no tours yet, so walk-in tour bookings aren't available.")
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { TextAction("Close") { close0() } }
            }
        }
        return
    }
    val tour = s.myTours.firstOrNull { it.name == s.newBookingActivity } ?: s.myTours.first()
    val slots = s.availableSlotsFor("tour", tour.id)
    LaunchedEffect(tour.id, slots) { if (s.newBookingTime !in slots) s.newBookingTime = slots.firstOrNull().orEmpty() }
    Modal(close, 600.dp) {
        ModalHeader("Create Tour Booking", "Farmer manual booking for walk-in tourists or phone reservations", Icons.Outlined.EventAvailable, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Guest / Customer Full Name", s.newBookingCustomer, { s.newBookingCustomer = it }, Modifier.weight(1f), "e.g. Anna Addyson")
                Field("Mobile Phone", s.newBookingPhone, { s.newBookingPhone = it }, Modifier.weight(1f), "+63 918 333 4455")
            }
            Dropdown("Select Tour", s.myTours.map { it.name }, tour.name, { n ->
                val selected = s.myTours.first { it.name == n }
                "$n (₱${selected.pricePerPerson} ${selected.priceType.label})"
            }) { s.newBookingActivity = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("Tour Schedule Date", s.newBookingDate, { s.newBookingDate = it }, Modifier.weight(1f))
                Column(Modifier.weight(1f)) {
                    Dropdown("Time Slot", slots, s.newBookingTime, { it.ifBlank { "No time slots available" } }) { s.newBookingTime = it }
                    if (slots.isEmpty()) Sub(NO_BOOKING_SCHEDULE_MESSAGE)
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                Label("Calculated Total Payment")
                Text("$PESO${money(tour.bookingTotal(1))}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest)
            }
            Label("Payment Method")
            ChoiceRow(WALK_IN_PAYMENT_METHODS, s.newBookingPayment) { s.newBookingPayment = it }
            PrimaryButton("Confirm", Modifier.fillMaxWidth(),
                enabled = s.newBookingCustomer.isNotBlank() && isValidDate(s.newBookingDate) && s.newBookingTime in slots) { s.saveFarmerBooking() }
        }
    }
}

fun isValidDate(v: String) = parseTourDate(v)?.let(BookingDateWindow.current()::contains) == true

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val window = BookingDateWindow.current()
    var open by remember { mutableStateOf(false) }
    val selectedDate = parseTourDate(value)?.takeIf(window::contains)
    val error = if (value.isNotBlank() && selectedDate == null) {
        "Choose a date from today through December 31 of next year."
    } else null
    Column(modifier) {
        if (label.isNotEmpty()) { Label(label); Spacer(Modifier.height(5.dp)) }
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                .background(Color.White).border(1.dp, if (error == null) Palette.Border else Palette.Red, RoundedCornerShape(12.dp))
                .handCursor().clickable { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(17.dp), tint = Palette.Muted)
            Spacer(Modifier.width(9.dp))
            Text(
                selectedDate?.toString() ?: "Select a date",
                Modifier.weight(1f),
                fontSize = 13.sp,
                color = if (selectedDate == null) Palette.Muted else Color(0xFF1E1E1E),
            )
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(17.dp), tint = Palette.Forest)
        }
        if (error != null) Sub(error, Modifier.padding(start = 4.dp, top = 5.dp))
    }
    if (open) {
        val initialDate = selectedDate ?: window.first
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = window.first.year..window.last.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return window.contains(date)
                }

                override fun isSelectableYear(year: Int) = year in window.first.year..window.last.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    open = false
                }, enabled = state.selectedDateMillis != null) { Text("Select") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }
}

// ------------------------------------------------------------------ write review
@Composable
private fun ReviewModal(s: AppState, b: ph.agritourism.model.Booking) {
    val close = { s.reviewingBooking = null }
    val existing = s.reviewForBooking(b.id)
    var rating by remember { mutableStateOf(existing?.rating ?: 5) }
    var comment by remember { mutableStateOf(existing?.comment.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    Modal(close, 520.dp) {
        ModalHeader(if (existing != null) "Edit Your Review" else "Write a Review", "${b.activity} · ${b.tourDate}", Icons.Outlined.Star, close)
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("Your Rating")
            Row {
                (1..5).forEach { i ->
                    Icon(Icons.Filled.Star, "$i star${if (i > 1) "s" else ""}", Modifier.size(34.dp).clip(CircleShape).clickable { rating = i }.padding(2.dp),
                        tint = if (i <= rating) Palette.Amber else Color(0xFFE2E8F0))
                }
                Spacer(Modifier.width(10.dp)); Text("$rating / 5", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterVertically))
            }
            Field("Your Review", comment, { comment = it }, Modifier.fillMaxWidth(), "Tell other tourists about your visit…", singleLine = false, minLines = 4, maxLength = 1000)
            error?.let { Text(it, color = Palette.Red, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(if (existing != null) "Save Changes" else "Post Review", Modifier.weight(1f), Icons.Outlined.Send) {
                    error = if (existing != null) s.updateReview(existing.id, rating, comment) else s.submitReview(b.id, rating, comment)
                }
                SecondaryButton("Cancel") { close() }
            }
        }
    }
}
