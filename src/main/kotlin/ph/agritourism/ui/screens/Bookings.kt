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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.model.PAY_AT_FARM_PAYMENT_METHOD
import ph.agritourism.model.Role
import ph.agritourism.ui.*

@Composable
fun MyBookingsPage(s: AppState) {
    val farmer = s.isStaff
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Pill("Reservations & Schedule Management")
            Spacer(Modifier.height(6.dp))
            H1(if (farmer) "Tourist Bookings & Gate Check-In" else "My Bookings & Reservations")
            Sub(if (farmer) "Review incoming eco-tourists, inspect uploaded tourist IDs & receipts, assign farm guides, and check-in visitors at the farm gate."
                else "View your official e-tickets, update tour schedules, verify 1-day attendance, or cancel bookings.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (farmer) {
                SecondaryButton("Print Guest Manifest", icon = Icons.Outlined.Download) { Printing.openHtml("guest-manifest.html", Printing.manifestHtml(s.visibleBookings, s.myFarmLabel)) }
                PrimaryButton("Add Walk-in Tour", icon = Icons.Filled.Add) { s.isAddBookingModalOpen = true }
            } else if (!s.isAdmin) PrimaryButton("Book New Tour", icon = Icons.Filled.Add) { s.navigate(Page.FARM_DETAIL) }
        }
    }
    val tabs = listOf("All", "Confirmed", "Pending", "Cancelled")
    val mine = s.visibleBookings
    val visible = mine.filter { s.bookingsFilter == "All" || it.status == s.bookingsFilter }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChipRow(tabs, s.bookingsFilter, { t -> "$t (${if (t == "All") mine.size else mine.count { it.status == t }})" }) { s.bookingsFilter = it }
        Spacer(Modifier.weight(1f)); Sub("Showing ${visible.size} reservation(s)")
    }
    if (visible.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth(), padding = 40.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(42.dp), tint = Palette.Muted)
                H2("No bookings found"); Sub("There are no reservations matching this filter.")
            }
        }
    }
    visible.forEach { b ->
        GlassCard {
            Row {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(b.bookingCode, Palette.Deep, Color.White); StatusPill(b.status)
                        b.attendanceStatus?.let {
                            Pill(when (it) { "Arrived & Checked In" -> "Arrived & Checked In"; "Confirmed Attending" -> "Attending Confirmed"; "Cancelled by Tourist" -> "Tourist Cancelled"; else -> "1-Day Check Pending" },
                                Color(0xFFFEF3C7), Color(0xFF92400E))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(b.farmName, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
                    Text("Activity: ${b.activity}", fontSize = 13.sp, color = Color(0xFF334155))
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Meta(Icons.Outlined.CalendarMonth, b.tourDate); Meta(Icons.Outlined.Schedule, "Time: ${b.tourTime}")
                        Meta(Icons.Outlined.Groups, "${b.participants} ${if (b.participants == 1) "person" else "persons"}")
                        Meta(Icons.Outlined.Person, b.customerName); b.customerPhone?.let { Meta(Icons.Outlined.Call, it) }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Label("Total Payment")
                    Text("$PESO${money(b.totalAmount)}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest)
                    Sub("Payment method: ${b.paymentMethod ?: PAY_AT_FARM_PAYMENT_METHOD}")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (farmer && b.status != "Cancelled") {
                    if (b.attendanceStatus == "Arrived & Checked In") Pill("✓ Checked-in (${b.checkedInAt ?: "08:35 AM"})", Color(0xFFDCFCE7), Color(0xFF166534))
                    else PrimaryButton("Gate Check-In", icon = Icons.Outlined.CheckBox) { s.checkInBooking(b.id) }
                }
                if (s.canReview(b)) PrimaryButton("Write Review", icon = Icons.Outlined.RateReview, color = Color(0xFFB45309)) { s.reviewingBooking = b }
                s.reviewForBooking(b.id)?.let { r ->
                    Pill("★ ${r.rating} Reviewed", Color(0xFFFEF3C7), Color(0xFF92400E))
                    if (s.canEditReview(r)) SecondaryButton("Edit Review", icon = Icons.Outlined.RateReview) { s.reviewingBooking = b }
                    if (s.canDeleteReview(r)) IconButton({ s.confirm("Delete your review?", "Your rating will be removed from this experience.") { s.deleteReview(r.id) } }) {
                        Icon(Icons.Outlined.DeleteSweep, "Delete review", tint = Palette.Red)
                    }
                }
                if (s.ownsBooking(b)) PrimaryButton("Online Ticket", icon = Icons.Outlined.ConfirmationNumber) { s.selectedBookingForTicket = b }
                SecondaryButton("Receipt", icon = Icons.Outlined.Print) { s.printTourReceipt(b) }
                SecondaryButton(if (farmer) "View Dossier" else "View Voucher", icon = Icons.Outlined.Visibility) { s.selectedBookingForView = b }
                if (b.status != "Cancelled" && (s.ownsBooking(b) || s.canActOnBooking(b))) {
                    SecondaryButton("Update", icon = Icons.Outlined.Edit) { s.editingBooking = b.copy() }
                    SecondaryButton("Cancel", icon = Icons.Outlined.Cancel, color = Color(0xFFB45309)) { s.cancelBooking(b.id) }
                }
                if (s.ownsBooking(b)) IconButton({ s.deletingBookingId = b.id }) { Icon(Icons.Outlined.Delete, "Delete booking from history", tint = Palette.Red) }
            }
            if (farmer) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF8FAFC)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label("Tourist Uploads:")
                    Pill("ID: ${b.uploadedIdDoc ?: "No ID uploaded"}", Color.White)
                    Pill("Payment: ${b.uploadedReceiptDoc ?: b.paymentMethod ?: "Payment method not set"}", Color.White)
                    val req = b.specialRequests
                    if (req != null) Text("\"$req\"", Modifier.weight(1f), fontSize = 11.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Spacer(Modifier.weight(1f))
                    Label("Assigned Guide:")
                    Dropdown("", (listOf("") + s.guidesFor(b) + listOfNotNull(b.assignedGuide)).distinct(), b.assignedGuide ?: "", { it.ifEmpty { "Assign Tour Guide..." } }, Modifier.width(260.dp)) {
                        if (it.isNotEmpty()) s.assignGuide(b.id, it)
                    }
                }
            }
        }
    }
}

@Composable
private fun Meta(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, Modifier.size(14.dp), tint = Palette.Forest); Spacer(Modifier.width(4.dp)); Text(text, fontSize = 12.sp, color = Color(0xFF334155))
}

@Composable
fun MyPurchasesPage(s: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            H1("My Purchases")
            Sub("Marketplace orders placed by your account, with saved order details and current status.")
        }
        PrimaryButton("Browse Marketplace", icon = Icons.Outlined.ShoppingBag) { s.navigate(Page.MARKET) }
    }
    val orders = s.visibleOrders
    if (orders.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth(), padding = 32.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(42.dp), tint = Palette.Muted)
                Sub("You haven't placed a marketplace order yet.")
                PrimaryButton("Explore Marketplace") { s.navigate(Page.MARKET) }
            }
        }
    }
    orders.forEach { order ->
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${order.orderNumber}", fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
                Spacer(Modifier.width(10.dp))
                StatusPill(order.fulfillmentStatus)
                Spacer(Modifier.weight(1f))
                Sub(order.orderDate)
            }
            Spacer(Modifier.height(8.dp))
            order.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(
                        item.productImageUrl.ifBlank { s.images.defaultProductFallbackImage },
                        Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                        contentDescription = item.productName,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("${item.productName} × ${item.qty}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(item.sellerName.ifBlank { "Seller unavailable for this legacy order" }, fontSize = 11.sp, color = Palette.Muted)
                    }
                    Text("$PESO${money(item.qty * item.unitPrice)}", fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Palette.Border)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Total · ${order.paymentMethod}", fontWeight = FontWeight.Bold, color = Palette.Forest)
                    Text("$PESO${money(order.totalAmount)}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
                }
                SecondaryButton("Order Details", icon = Icons.Outlined.Description) { s.selectedOrderForView = order }
                if (s.canCancelOrder(order)) {
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("Cancel Order", icon = Icons.Outlined.Cancel, color = Palette.Red) {
                        s.confirm("Cancel order #${order.orderNumber}?", "The items will be returned to stock and the farm will be notified.", "Yes, Cancel Order") {
                            s.cancelOrder(order.id)
                        }
                    }
                }
                if (s.canDeletePurchase(order)) {
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("Delete", icon = Icons.Outlined.Delete, color = Palette.Red) {
                        s.confirm("Delete cancelled order #${order.orderNumber}?", "This permanently removes the cancelled order from your purchase history.", "Yes, Delete") {
                            s.deletePurchase(order.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FarmerOrdersPage(s: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Pill("Marketplace Order Desk"); Spacer(Modifier.height(6.dp))
            H1("Marketplace Orders")
            Sub("Review Cash on Delivery orders for ${s.myFarmLabel} produce and confirm or cancel them.")
        }
        SecondaryButton("Print Packing Slips", icon = Icons.Outlined.Download) {
            Printing.openHtml("packing-slips.html", Printing.orderHtml(s.visibleOrders, "${s.myFarmLabel} · Packing Slips"))
        }
    }
    val tabs = listOf("All") + ORDER_STATUSES
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChipRow(tabs, s.ordersFilter, { t -> "$t (${if (t == "All") s.visibleOrders.size else s.visibleOrders.count { it.fulfillmentStatus == t }})" }) { s.ordersFilter = it }
        Spacer(Modifier.weight(1f))
        Pill("Total Orders Value: $PESO${money(s.visibleOrders.sumOf(s::farmShare))}")
    }
    if (s.visibleOrders.isEmpty()) Sub("No orders for your farm's produce yet.")
    s.visibleOrders.filter { s.ordersFilter == "All" || it.fulfillmentStatus == s.ordersFilter }.forEach { o ->
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("#${o.orderNumber}", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
                StatusPill(o.fulfillmentStatus); Sub(o.orderDate)
                Spacer(Modifier.weight(1f)); Label("Payment Status:"); Pill("✓ ${o.paymentStatus}", Color(0xFFDCFCE7), Color(0xFF166534))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Label("Tourist Customer Details")
                    Text(o.customerName, fontWeight = FontWeight.Bold); Text("Phone: ${o.customerPhone}", fontSize = 12.sp); Text("Delivery address: ${o.customerAddress}", fontSize = 12.sp)
                    o.specialDeliveryNotes?.let { Text("Delivery Note: $it", fontSize = 11.sp, color = Color(0xFF92400E)) }
                }
                Column(Modifier.weight(1f)) {
                    Label("Ordered Farm Produce")
                    val mine = s.farmProducts.map { it.id }.toSet()
                    val (ownItems, otherItems) = if (s.isFarmer) o.items.partition { it.productId in mine } else o.items to emptyList()
                    ownItems.forEach { Row { Text("${it.productName} × ${it.qty}", Modifier.weight(1f), fontSize = 12.sp); Text("$PESO${money(it.qty * it.unitPrice)}", fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
                    if (otherItems.isNotEmpty()) Sub("+ ${otherItems.size} item(s) supplied by other farms")
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row { Text("Total Amount:", Modifier.weight(1f), fontWeight = FontWeight.Bold); Text("$PESO${money(o.totalAmount)}", fontWeight = FontWeight.ExtraBold, color = Palette.Forest) }
                }
                Column(Modifier.weight(1f)) {
                    Label("Payment")
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF8FAFC)).clickable { s.selectedOrderForView = o }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("${o.paymentStatus} · $PESO${money(o.totalAmount)}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Click to open order details →", fontSize = 11.sp, color = Palette.Forest)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Label("Order Status:")
                    if (o.fulfillmentStatus == "Pending" && s.canManageOrder(o)) {
                        PrimaryButton("Confirm Order", icon = Icons.Outlined.CheckCircle) { s.orderStatusUpdate(o.id, "Confirmed") }
                    } else Sub(if (o.fulfillmentStatus == "Confirmed") "This order is confirmed." else "This order was cancelled.")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Sub("Delivery address: ${o.customerAddress}", Modifier.weight(1f))
                SecondaryButton("Receipt", icon = Icons.Outlined.Print) { s.printOrderReceipt(o) }
                Spacer(Modifier.width(6.dp))
                if (s.canCancelOrder(o)) {
                    SecondaryButton("Cancel Order", icon = Icons.Outlined.Cancel, color = Color(0xFFB45309)) {
                        s.confirm("Cancel order #${o.orderNumber}?", "The customer is notified and the items go back into stock.", "Yes, Cancel Order") { s.cancelOrder(o.id) }
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
fun FarmerProductsPage(s: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Pill("Farm Produce Inventory"); Spacer(Modifier.height(6.dp))
            H1("My Produce Catalog")
            Sub("Manage the produce listed by ${s.myFarmLabel}. Add new harvests with photos and pricing.")
        }
        PrimaryButton("List New Produce", icon = Icons.Filled.Add) { s.openAddProduct() }
    }
    if (s.farmProducts.isEmpty()) Sub("No products listed yet. Use List New Produce to add your first harvest.")
    Grid(s.farmProducts, 4) { p ->
        GlassCard(padding = 0.dp) {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                AsyncImage(p.imageUrl, Modifier.fillMaxSize())
                Pill(p.category, Color.White.copy(alpha = 0.92f), Palette.Deep, Modifier.align(Alignment.TopStart).padding(10.dp))
                if (p.stock > 0) Pill("In Stock (${p.stock})", Color(0xFFDCFCE7), Color(0xFF166534), Modifier.align(Alignment.TopEnd).padding(10.dp))
                else Pill("Sold Out", Color(0xFFFEE2E2), Palette.Red, Modifier.align(Alignment.TopEnd).padding(10.dp))
            }
            Column(Modifier.padding(14.dp)) {
                Text(p.name, fontWeight = FontWeight.ExtraBold, color = Palette.Deep, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Sub(p.description, maxLines = 2, minLines = 2)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column { Label("Farmer Price"); Row { Text("$PESO${money(p.price)}", fontWeight = FontWeight.ExtraBold, color = Palette.Forest); Text(" ${p.unit}", fontSize = 11.sp, color = Palette.Muted) } }
                    Spacer(Modifier.weight(1f)); Pill("Active Listing")
                }
                if (s.canEditProduct(p)) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SecondaryButton("Edit", Modifier.weight(1f), icon = Icons.Outlined.Edit) { s.openEditProduct(p) }
                        SecondaryButton("+10", icon = Icons.Outlined.Add) { s.restockProduct(p.id, 10) }
                        IconButton({ s.confirm("Delete \"${p.name}\"?", "It will be removed from the market and from customers' carts. Past orders keep it.") { s.deleteProduct(p.id) } }) {
                            Icon(Icons.Outlined.Delete, "Delete product", tint = Palette.Red)
                        }
                    }
                }
            }
        }
    }
}
