package ph.agritourism.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.model.FarmTour
import ph.agritourism.model.TourPriceStatus
import ph.agritourism.model.TourPriceType
import ph.agritourism.model.User
import ph.agritourism.model.Role
import ph.agritourism.ui.*

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StatCard(label: String, value: String, sub: String, bg: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    var hovered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (hovered && onClick != null) 1.03f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "stat-scale",
    )
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clip(RoundedCornerShape(18.dp)).background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp)
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFF334155), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
        Text(sub, fontSize = 11.sp, color = Palette.Muted)
    }
}

private fun niceCeil(v: Double): Double {
    if (v <= 0) return 4.0
    val mag = Math.pow(10.0, Math.floor(Math.log10(v)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= v }
}

/** Smooth area chart drawn from real monthly values. */
@Composable
fun DataChart(points: List<MonthPoint>, line: Color, fill: Color, modifier: Modifier, formatY: (Double) -> String) {
    val tm = rememberTextMeasurer()
    val top = niceCeil(points.maxOfOrNull { it.value } ?: 0.0)
    androidx.compose.foundation.Canvas(modifier) {
        val ts = TextStyle(fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.SemiBold)
        val left = 56.dp.toPx(); val right = size.width - 16.dp.toPx()
        val topY = 10.dp.toPx(); val baseY = size.height - 24.dp.toPx()
        val ticks = 4
        (0..ticks).forEach { i ->
            val y = baseY - (baseY - topY) * i / ticks
            drawLine(Color(0xFFD8E2DA), Offset(left, y), Offset(right, y))
            val r = tm.measure(formatY(top * i / ticks), ts)
            drawText(r, topLeft = Offset(left - r.size.width - 8f, y - r.size.height / 2))
        }
        if (points.isEmpty()) return@Canvas
        val step = if (points.size > 1) (right - left) / (points.size - 1) else 0f
        val pts = points.mapIndexed { i, p -> Offset(left + step * i, (baseY - (baseY - topY) * (p.value / top)).toFloat()) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val p0 = pts[maxOf(i - 2, 0)]; val p1 = pts[i - 1]; val p2 = pts[i]; val p3 = pts[minOf(i + 1, pts.lastIndex)]
                // Catmull-Rom to Bezier, clamped so the curve never dips below zero.
                cubicTo(p1.x + (p2.x - p0.x) / 6, minOf(baseY, p1.y + (p2.y - p0.y) / 6),
                    p2.x - (p3.x - p1.x) / 6, minOf(baseY, p2.y - (p3.y - p1.y) / 6), p2.x, p2.y)
            }
        }
        val area = Path().apply { addPath(path); lineTo(pts.last().x, baseY); lineTo(pts.first().x, baseY); close() }
        drawPath(area, Brush.verticalGradient(listOf(fill.copy(alpha = 0.45f), fill.copy(alpha = 0f))))
        drawPath(path, line, style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        pts.forEachIndexed { i, o ->
            drawCircle(Color.White, 5f, o); drawCircle(line, 5f, o, style = Stroke(2.5f))
            val r = tm.measure(points[i].label, ts)
            drawText(r, topLeft = Offset(o.x - r.size.width / 2, size.height - r.size.height))
        }
    }
}

@Composable
fun AdminDashboard(s: AppState) {
    // Refresh on entry so the graph and user table reflect the current database contents.
    LaunchedEffect(s.activePage) { s.refreshFromDb() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { H1("Platform Overview"); Sub("Read-only monitoring of live agritourism records") }
        Pill("● Live records", Color(0xFFDCFCE7), Color(0xFF166534))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatCard("Total Users", money(s.db.userCount()), "${s.userAccounts.count { it.isActive }} active accounts", Color(0xFFE0F2FE), Modifier.weight(1f))
        StatCard("Total Farms", money(s.farmers.size), "${s.tours.size} tours · ${s.fruitPicking.size} orchards", Color(0xFFE3F1E7), Modifier.weight(1f))
        StatCard("Total Bookings", money(s.bookings.size), "%.1f%% fulfillment rate".format(s.fulfillmentRate), Color(0xFFFEF3E2), Modifier.weight(1f))
        StatCard("Total Products", money(s.products.size), "${s.products.count { it.stock > 0 }} in stock", Color(0xFFEDE9FE), Modifier.weight(1f))
    }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader("Bookings Overview", "Monthly reservations by tour date, calculated live from the bookings table")
        DataChart(s.monthlyBookings, Palette.Teal, Color(0xFF14B8A6), Modifier.fillMaxWidth().height(260.dp)) { v ->
            if (v == Math.floor(v)) money(v) else "%.1f".format(v)
        }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader("Registered Users Over Time", "Cumulative account counts from users.created_at; missing legacy dates are excluded")
        DataChart(s.registeredUsersOverTime, Palette.Forest, Color(0xFF16A34A), Modifier.fillMaxWidth().height(220.dp)) { money(it) }
        val missingDates = s.userAccounts.count { it.createdAt.isBlank() }
        if (missingDates > 0) Sub("$missingDates existing account(s) have no recorded creation date; total users above still includes them.")
    }

    AdminAccountManagement(s)
    DashboardAccountFooter(s)
}

private fun farmLocationText(farm: ph.agritourism.model.FarmerProfile?): String =
    farm?.let { listOf(it.location, it.province).filter { part -> part.isNotBlank() }.joinToString(", ") }.orEmpty().ifBlank { "—" }

private fun farmerNameFor(s: AppState, farmerId: Int?, fallback: String): String {
    val farm = s.farmers.firstOrNull { it.id == farmerId }
    return fallback.ifBlank {
        s.userAccounts.firstOrNull { it.farmerId == farmerId && it.role == Role.FARMER }?.fullName
            ?: farm?.name.orEmpty()
    }.ifBlank { "—" }
}

private fun ratingText(rating: Double, reviews: Int): String =
    if (reviews > 0 && rating > 0) "%.1f (%d reviews)".format(rating, reviews) else "No Ratings"

private fun statusText(archived: Boolean): String = if (archived) "Archived" else "Active"

@Composable
fun AdminToursPage(s: AppState) {
    LaunchedEffect(s.activePage) { s.refreshFromDb() }
    H1("Tour")
    Sub("Read-only text table of farm tours and activities")
    AdminReadOnlyTable(
        title = "Tours",
        subtitle = "Farm tours and activities with live ratings and status",
        headers = listOf("Tour Name", "Farmer Name", "Farm Name", "Farm Location", "Description", "Availability", "Ratings", "Status"),
        rows = s.allTours.map { tour ->
            val farm = s.farmers.firstOrNull { it.id == tour.farmerId }
            listOf(
                tour.name,
                farmerNameFor(s, tour.farmerId, tour.farmerName),
                farm?.farmName ?: "Platform-managed",
                farmLocationText(farm).takeIf { it != "—" } ?: tour.location,
                tour.description,
                if (tour.bookingEnabled) "Available" else "Unavailable",
                ratingText(tour.rating, tour.reviewsCount),
                statusText(tour.archived),
            )
        },
    )
    DashboardAccountFooter(s)
}

@Composable
fun AdminFruitPickingPage(s: AppState) {
    LaunchedEffect(s.activePage) { s.refreshFromDb() }
    H1("Fruit Picking")
    Sub("Read-only text table of fruit-picking activities")
    AdminReadOnlyTable(
        title = "Fruit Picking Activities",
        subtitle = "Orchard harvest experiences with live ratings and status",
        headers = listOf("Activity Name", "Farmer Name", "Farm Name", "Farm Location", "Description", "Availability", "Ratings", "Status"),
        rows = s.allOrchards.map { item ->
            val farm = s.farmers.firstOrNull { it.id == item.farmerId }
            listOf(
                item.title,
                farmerNameFor(s, item.farmerId, ""),
                item.farmName.ifBlank { farm?.farmName ?: "Platform-managed" },
                farmLocationText(farm).takeIf { it != "—" } ?: item.location,
                item.description,
                item.seasonAvailability.ifBlank { "Not specified" },
                ratingText(item.rating, item.reviewsCount),
                statusText(item.archived),
            )
        },
    )
    DashboardAccountFooter(s)
}

@Composable
fun AdminProductsPage(s: AppState) {
    LaunchedEffect(s.activePage) { s.refreshFromDb() }
    H1("Products")
    Sub("Read-only text table of vegetables and harvest products")
    AdminReadOnlyTable(
        title = "Vegetables and Products",
        subtitle = "Marketplace produce with live price, stock, ratings and status",
        headers = listOf("Product Name", "Farmer Name", "Farm Name", "Farm Location", "Description", "Price", "Stock / Availability", "Ratings", "Status"),
        rows = s.allProducts.map { product ->
            val farm = s.farmers.firstOrNull { it.id == product.farmerId }
            listOf(
                product.name,
                farmerNameFor(s, product.farmerId, ""),
                farm?.farmName ?: product.farmOrigin,
                farmLocationText(farm),
                product.description,
                "$PESO${money(product.price)} / ${product.unit}",
                if (product.stock > 0) "${product.stock} in stock" else "Out of Stock",
                ratingText(product.rating, product.reviewsCount),
                statusText(product.archived),
            )
        },
    )
    DashboardAccountFooter(s)
}

@Composable
private fun AdminReadOnlyTable(title: String, subtitle: String, headers: List<String>, rows: List<List<String>>) {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader(title, subtitle) { Pill("${rows.size} records", Palette.Mint, Palette.Forest) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Mint).padding(horizontal = 10.dp, vertical = 12.dp)) {
            headers.forEach { Text(it, Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep) }
        }
        if (rows.isEmpty()) Sub("No active records found.")
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
                row.forEach { value ->
                    Text(value.ifBlank { "—" }, Modifier.weight(1f).padding(end = 8.dp), fontSize = 12.sp,
                        color = Palette.Ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = Palette.Border)
        }
    }
}

@Composable
fun FarmerDashboard(s: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        EditableAvatar(s.avatar, 64.dp) { pickImageFile("Choose your profile photo")?.let(s::changeAvatar) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { H1("Welcome, ${s.displayName}"); Sub(s.myFarm?.let { "${it.farmName} · ${it.location}" } ?: "No farm linked to this account") }
        if (s.myFarm != null) {
            SecondaryButton("Edit Story", icon = Icons.Outlined.Edit) { s.isEditStoryOpen = true }
            Spacer(Modifier.width(8.dp))
            SecondaryButton("Add Tour", icon = Icons.Outlined.Explore) { s.isAddTourModalOpen = true }
            Spacer(Modifier.width(8.dp))
            SecondaryButton("Add Fruit Picking", icon = Icons.Outlined.AutoAwesome) { s.isAddOrchardModalOpen = true }
            Spacer(Modifier.width(8.dp))
        }
        PrimaryButton("List New Produce", icon = Icons.Filled.Add) { s.openAddProduct() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatCard("Total Products", "${s.farmProducts.size}", "Active in marketplace", Color(0xFFDFF1E6), Modifier.weight(1f)) { s.navigate(Page.FARMER_PRODUCTS) }
        StatCard("New Orders", "${s.openOrders.size}", "Ready for packing & delivery", Color(0xFFDDEAF8), Modifier.weight(1f)) { s.navigate(Page.FARMER_ORDERS) }
        StatCard("Active Bookings", "${s.activeBookings.size}", "Upcoming farm visitors", Color(0xFFFCEBD6), Modifier.weight(1f)) { s.navigate(Page.MY_BOOKINGS) }
    }
    GlassCard {
        SectionHeader("Farm Sales & Revenue Graph", "Monthly produce orders and booking revenue (PHP, last 6 months)") { Pill("Total: $PESO${money(s.totalRevenue)}") }
        Spacer(Modifier.height(10.dp))
        DataChart(s.monthlyRevenue, Color(0xFF15803D), Color(0xFF16A34A), Modifier.fillMaxWidth().height(210.dp)) { v ->
            if (v >= 1000) "$PESO${"%.1f".format(v / 1000).removeSuffix(".0")}k" else "$PESO${money(v)}"
        }
    }
    FarmerBookingTable(s, "Tour Bookings", s.visibleBookings.filter { it.activityType == "tour" })
    FarmerBookingTable(s, "Fruit Picking Bookings", s.visibleBookings.filter { it.activityType == "orchard" })
    DashboardAccountFooter(s)
}

@Composable
private fun FarmerBookingTable(s: AppState, title: String, bookings: List<ph.agritourism.model.Booking>) {
    GlassCard {
        SectionHeader(title, "Reservations for ${s.myFarmLabel}; select a row to open its dossier") {
            Pill("${bookings.count { it.status == "Pending" }} Pending", Color(0xFFFEF3C7), Color(0xFF92400E))
        }
        Spacer(Modifier.height(12.dp))
        val headers = listOf("Date", "Time", "Guest", "Pax", "Amount", "Status")
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Mint).padding(10.dp)) {
            headers.forEach { Text(it, Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep) }
        }
        if (bookings.isEmpty()) Sub("No $title records yet.")
        bookings.forEach { b ->
            Row(Modifier.fillMaxWidth().handCursor().clickable { s.selectedBookingForView = b }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(b.tourDate, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(b.tourTime, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2)
                Text(b.customerName, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2)
                Text("${b.participants}", Modifier.weight(1f), fontSize = 12.sp)
                Text("$PESO${money(b.totalAmount)}", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.weight(1f)) { StatusPill(b.status) }
            }
            HorizontalDivider(color = Palette.Border)
        }
    }
}

private const val FARMER_DELETE_MESSAGE =
    "Deleting this Farmer account will also permanently remove the associated farm information, products, produce, tours, fruit-picking listings, schedules, and other related active data. Historical transaction records may be retained when necessary."

@Composable
private fun AdminAccountManagement(s: AppState) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val accounts = s.userAccounts.filter { account ->
        val roleMatches = account.role != Role.ADMIN && (filter == "All" ||
            (filter == "Farmers" && account.role == Role.FARMER) ||
            (filter == "Tourists" && account.role == Role.USER))
        roleMatches && (query.isBlank() || account.fullName.contains(query, true) || account.email.contains(query, true))
    }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader(
            "User Information",
            "Farmer and Tourist accounts only. Administrator records are read-only and cannot be deleted here.",
        ) {
            SecondaryButton("Refresh List", icon = Icons.Outlined.Refresh) { s.refreshFromDb() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchField(query, { query = it }, "Search accounts…", Modifier.weight(1f))
            FilterChipRow(listOf("All", "Farmers", "Tourists"), filter, { it }) { filter = it }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Mint).padding(horizontal = 10.dp, vertical = 12.dp)) {
            listOf("Name", "Email", "Phone Number", "Location", "Role", "Status").forEach {
                Text(it, Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
            }
            Spacer(Modifier.width(92.dp))
        }
        if (accounts.isEmpty()) Sub("No Farmer or Tourist accounts match this filter.")
        accounts.forEach { account -> AdminUserRow(s, account) }
    }
}

@Composable
private fun AdminUserRow(s: AppState, account: User) {
    var error by remember(account.id) { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp).testTag("account-row-${account.id}"), verticalAlignment = Alignment.CenterVertically) {
        Text(account.fullName, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Deep, maxLines = 2)
        Text(account.email, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2)
        Text(account.phone.ifBlank { "Not Provided" }, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2)
        Text(account.address.ifBlank { "—" }, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2)
        Text(if (account.role == Role.FARMER) "Farmer" else "Tourist", Modifier.weight(1f), fontSize = 12.sp)
        Text(if (account.isActive) "Active" else "Inactive", Modifier.weight(1f), fontSize = 12.sp)
        Box(Modifier.width(92.dp), contentAlignment = Alignment.CenterEnd) {
            SecondaryButton("Delete", color = Palette.Red) {
                if (account.role == Role.FARMER) {
                    s.confirm("Delete Farmer Account?", FARMER_DELETE_MESSAGE, "Delete Account", "Cancel") {
                        error = s.deleteFarmerAccount(account.id)
                    }
                } else {
                    s.confirm("Delete Tourist Account?", "This permanently removes this tourist account. Historical bookings and orders are retained when required.", "Delete Account", "Cancel") {
                        error = s.deleteTouristAccount(account.id)
                    }
                }
            }
        }
    }
    ErrorBanner(error)
    HorizontalDivider(color = Palette.Border)
}

@Composable
fun TouristDashboard(s: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        EditableAvatar(s.avatar, 64.dp) { pickImageFile("Choose your profile photo")?.let(s::changeAvatar) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { H1("Hello, ${s.displayName.substringBefore(" ")}!"); Sub("Where do you want to go today?") }
        PrimaryButton("Book a Tour", icon = Icons.Outlined.Explore) { s.navigate(Page.FARM_DETAIL) }
    }
    s.pendingAttendanceBooking?.let { t ->
        val confirmed = t.attendanceStatus == "Confirmed Attending"
        val cancelled = t.attendanceStatus == "Cancelled by Tourist" || t.status == "Cancelled"
        val (bg, accent) = when { confirmed -> Color(0xFFF0FDF4) to Color(0xFF15803D); cancelled -> Color(0xFFFEF2F2) to Palette.Red; else -> Color(0xFFFFFBEB) to Color(0xFFB45309) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(20.dp)).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (confirmed) Icons.Outlined.CheckBox else if (cancelled) Icons.Outlined.Block else Icons.Outlined.Notifications, null, tint = accent)
                Spacer(Modifier.width(8.dp))
                Text("1-DAY BEFORE TOUR CHECK", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = accent)
                Spacer(Modifier.width(10.dp))
                Pill("Tomorrow at ${t.tourTime}", Color.White, accent)
            }
            Spacer(Modifier.height(8.dp))
            Text(when { confirmed -> "Attendance Confirmed: See You Tomorrow!"; cancelled -> "Tour Visit Cancelled"; else -> "Will you proceed with your scheduled Farm Tour tomorrow?" },
                fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
            Spacer(Modifier.height(4.dp))
            Text(when {
                confirmed -> "Your tour reservation at ${t.farmName} is confirmed. The farm host and your guide are preparing for your arrival at ${t.tourTime}."
                cancelled -> "You have cancelled your tour reservation at ${t.farmName}. The farm host has been notified."
                else -> "Your tour to ${t.farmName} is scheduled for tomorrow, ${t.tourDate} at ${t.tourTime} (${t.participants} guests). Please confirm if you will proceed so the farm can prepare fresh refreshments and assign your guide."
            }, fontSize = 13.sp, color = Color(0xFF334155))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!confirmed && !cancelled) {
                    PrimaryButton("YES, I WILL ATTEND", icon = Icons.Outlined.CheckBox) { s.attendanceConfirmation(t.id, true) }
                    SecondaryButton("NO, CANCEL TOUR", icon = Icons.Outlined.Cancel, color = Palette.Red) { s.attendanceConfirmation(t.id, false) }
                } else SecondaryButton("View Booking Voucher →") { s.navigate(Page.MY_BOOKINGS) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val reviewCount = s.myReviews.size
        val favs = s.favoriteNames
        MiniStat(Icons.Outlined.CalendarMonth, "${s.activeBookings.size}", "Active Bookings", Modifier.weight(1f)) { s.navigate(Page.MY_BOOKINGS) }
        MiniStat(Icons.Outlined.Star, "$reviewCount", "Submitted Reviews", Modifier.weight(1f)) {
            val pending = s.visibleBookings.count(s::canReview)
            s.showToast(when {
                pending > 0 -> "You have $pending visit(s) waiting for a review. Open My Bookings and tap Write Review."
                reviewCount == 0 -> "You haven't posted any reviews yet. You can review a booking after your visit."
                else -> "You have $reviewCount posted review(s). Thank you for supporting local farmers!"
            })
            if (pending > 0) s.navigate(Page.MY_BOOKINGS)
        }
        MiniStat(Icons.Outlined.FavoriteBorder, "${favs.size}", "Wishlisted Farms", Modifier.weight(1f)) {
            s.navigate(Page.WISHLIST)
        }
        MiniStat(Icons.Outlined.ShoppingBag, "${s.visibleOrders.size}", "My Purchases", Modifier.weight(1f)) {
            s.navigate(Page.MY_PURCHASES)
        }
    }
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp))) {
        AsyncImage(s.images.farmLifeBannerPhoto, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE6144824), Color.Transparent))))
        Column(Modifier.align(Alignment.CenterStart).padding(32.dp).widthIn(max = 460.dp)) {
            Text("Experience Farm Life", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("Join farm tours, try fresh produce, meet local farmers, and create unforgettable memories.", color = Color.White.copy(alpha = 0.9f))
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Explore Tours Now", color = Color(0xFF22C55E)) { s.navigate(Page.FARM_DETAIL) }
        }
    }
    CatalogHome(s)
    DashboardAccountFooter(s)
}

@Composable
private fun DashboardAccountFooter(s: AppState) {
    HorizontalDivider(color = Palette.Border)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(s.avatar, 40.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Signed in as ${s.displayName}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Palette.Deep)
            Text(s.userEmail, fontSize = 11.sp, color = Palette.Muted)
        }
    }
}

@Composable
private fun MiniStat(icon: ImageVector, value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Mint), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Palette.Forest) }
            Spacer(Modifier.width(14.dp))
            Column { Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep); Sub(label) }
        }
    }
}
