package ph.agritourism.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.ui.*
import kotlin.math.roundToInt

@Composable
fun CheckoutPage(s: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { H1("Your Cart & Checkout"); Sub("Pay the delivery rider in cash when your order arrives") }
        BackAction { s.navigate(Page.MARKET) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("1 Cart", "2 Delivery", "3 Payment", "4 Confirmation").forEachIndexed { i, t ->
            val step = i + 1
            val active = step <= s.checkoutStep
            Text(t, Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (active) Palette.Forest else Color.White)
                .border(1.dp, if (active) Palette.Forest else Palette.Border, RoundedCornerShape(50))
                .clickable { if (step <= s.checkoutStep && s.checkoutStep != 4) s.checkoutStep = step }.padding(vertical = 10.dp),
                textAlign = TextAlign.Center, color = if (active) Color.White else Palette.Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
    when (s.checkoutStep) {
        1 -> CartStep(s)
        2 -> GlassCard {
            H2("Shipping & Contact Information")
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Field("Recipient Full Name", s.recipientName, { s.recipientName = it }, Modifier.weight(1f), "Who will receive the order", icon = Icons.Outlined.Person)
                Field("Mobile Contact Number", s.contactPhone, { v -> s.contactPhone = v.filter { it.isDigit() || it in "+ -()" } }, Modifier.weight(1f), "+63 912 345 6789", icon = Icons.Outlined.Phone)
            }
            Spacer(Modifier.height(12.dp))
            Field("Delivery Address", s.deliveryAddress, { s.deliveryAddress = it }, placeholder = "House no., street, barangay, city, province", singleLine = false, minLines = 3, icon = Icons.Outlined.Home)
            Spacer(Modifier.height(12.dp))
            Field("Special Rider Instructions", s.deliveryNotes, { s.deliveryNotes = it }, placeholder = "Optional — landmarks, gate code, etc.", icon = Icons.Outlined.EditNote, maxLength = 200)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                PrimaryButton("Proceed to Payment Method →") { s.deliveryDetailsError()?.let(s::showToast) ?: run { s.checkoutStep = 3 } }
                Spacer(Modifier.width(12.dp))
                BackAction { s.checkoutStep = 1 }
            }
        }
        3 -> PaymentStep(s)
        else -> CartStep(s)
    }
    s.purchaseConfirmationOrder?.let { order ->
        val close = {
            s.purchaseConfirmationOrder = null
            s.checkoutStep = 1
            s.navigate(Page.MARKET)
        }
        Modal({ close() }, 540.dp) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(60.dp), tint = Color(0xFF16A34A))
                Spacer(Modifier.height(10.dp))
                Text("Purchase Successful!", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
                Spacer(Modifier.height(8.dp))
                Text("Order reference: ${order.orderNumber}", fontWeight = FontWeight.Bold, color = Palette.Forest)
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF8FAFC)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    order.items.forEach { item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(item.productName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("× ${item.qty}", fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider(color = Palette.Border)
                    SummaryRow("Total Amount", "$PESO${money(order.totalAmount)}", true)
                    SummaryRow("Payment Method", order.paymentMethod)
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("View Order Details", Modifier.weight(1f)) {
                        s.purchaseConfirmationOrder = null
                        s.selectedOrderForView = order
                    }
                    Spacer(Modifier.width(12.dp))
                    BackAction { close() }
                }
            }
        }
    }
}
@Composable
private fun CartStep(s: AppState) {
    if (s.cart.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth(), padding = 40.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(48.dp), tint = Palette.Muted)
                Spacer(Modifier.height(8.dp)); Sub("Your cart is currently empty.")
                Spacer(Modifier.height(12.dp)); PrimaryButton("Explore Fresh Vegetables & Produce") { s.navigate(Page.MARKET) }
            }
        }
        return
    }
    val picked = s.checkoutItems
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { s.selectAllCart(picked.size != s.cart.size) }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(picked.size == s.cart.size, { s.selectAllCart(it) }, colors = CheckboxDefaults.colors(checkedColor = Palette.Forest))
                Text("Select all (${picked.size} of ${s.cart.size} selected)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            s.cart.forEach { item ->
                val p = item.product
                val atMax = item.quantity >= p.stock
                val selected = p.id !in s.cartUnselected
                GlassCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected, { s.toggleCartItem(p.id) }, colors = CheckboxDefaults.colors(checkedColor = Palette.Forest))
                        Spacer(Modifier.width(6.dp))
                        AsyncImage(p.imageUrl, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
                            Text(p.farmOrigin, fontSize = 11.sp, color = Palette.Forest)
                            Text("$PESO${p.discountedPrice.roundToInt()} ${p.unit}", fontSize = 12.sp, color = Palette.Muted)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ s.updateCartQty(p.id, -1) }, Modifier.size(30.dp).clip(CircleShape).background(Palette.Mint)) { Icon(Icons.Filled.Remove, "Decrease quantity") }
                                Text("${item.quantity}", Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.ExtraBold)
                                IconButton({ s.updateCartQty(p.id, 1) }, Modifier.size(30.dp).clip(CircleShape).background(if (atMax) Color(0xFFE2E8F0) else Palette.Forest), enabled = !atMax) {
                                    Icon(Icons.Filled.Add, "Increase quantity", tint = Color.White)
                                }
                            }
                            Text(if (atMax) "Max stock reached" else "Limit: ${p.stock} max ${p.unit}", fontSize = 10.sp, color = if (atMax) Palette.Red else Palette.Muted)
                        }
                        Spacer(Modifier.width(16.dp))
                        Text("$PESO${(p.discountedPrice * item.quantity).roundToInt()}", Modifier.width(80.dp), fontWeight = FontWeight.ExtraBold, color = Palette.Forest, textAlign = TextAlign.End)
                        IconButton({ s.updateCartQty(p.id, -item.quantity) }) { Icon(Icons.Outlined.Delete, "Remove product from cart", tint = Palette.Red) }
                    }
                }
            }
        }
        GlassCard(Modifier.weight(1f)) {
            H2("Order Summary")
            Spacer(Modifier.height(12.dp))
            SummaryRow("Subtotal (${picked.sumOf { it.quantity }} item(s))", "$PESO${money(s.cartSubtotal)}")
            SummaryRow("Delivery Fee (Refrigerated Van)", "$PESO${money(if (picked.isEmpty()) 0.0 else s.deliveryFee)}")
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Palette.Border)
            SummaryRow("Total", "$PESO${money(s.cartTotal)}", true)
            Spacer(Modifier.height(16.dp))
            if (picked.isEmpty()) { Sub("Tick the items you want to buy."); Spacer(Modifier.height(8.dp)) }
            PrimaryButton("Proceed to Delivery Details →", Modifier.fillMaxWidth(), enabled = picked.isNotEmpty()) { s.checkoutStep = 2 }
        }
    }
}

@Composable
private fun SummaryRow(l: String, v: String, bold: Boolean = false) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Text(l, Modifier.weight(1f), fontSize = if (bold) 16.sp else 13.sp, fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Normal)
    Text(v, fontSize = if (bold) 18.sp else 13.sp, fontWeight = FontWeight.ExtraBold, color = if (bold) Palette.Forest else Color.Unspecified)
}

private data class PayOption(val method: String, val label: String, val icon: ImageVector, val desc: String)

@Composable
private fun PaymentStep(s: AppState) {
    LaunchedEffect(s.paymentMethod) {
        if (s.paymentMethod !in CHECKOUT_PAYMENT_METHODS) s.paymentMethod = CHECKOUT_PAYMENT_METHODS.first()
    }
    GlassCard {
        H2("Select Payment Method"); Sub("Cash on Delivery")
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(
                PayOption("Cash on Delivery", "Cash on Delivery", Icons.Outlined.Payments, "Pay rider directly upon vegetable arrival"),
            ).forEach { o ->
                val sel = s.paymentMethod == o.method
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (sel) Palette.Mint else Color.White)
                    .border(2.dp, if (sel) Palette.Forest else Palette.Border, RoundedCornerShape(16.dp)).clickable { s.paymentMethod = o.method }.padding(16.dp)) {
                    Row { Icon(o.icon, null, tint = Palette.Forest); Spacer(Modifier.weight(1f)); if (sel) Icon(Icons.Filled.CheckCircle, null, tint = Palette.Forest) }
                    Spacer(Modifier.height(8.dp))
                    Text(o.label, fontWeight = FontWeight.ExtraBold); Sub(o.desc)
                    Text(if (sel) "Selected" else "Click to select", fontSize = 11.sp, color = Palette.Forest, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF8FAFC)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Cash on Delivery (COD) Selected", fontWeight = FontWeight.ExtraBold)
                Text("Please prepare exact cash amount of $PESO${money(s.cartTotal)} upon arrival of the delivery rider.", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            PrimaryButton("Confirm and Place Order · $PESO${money(s.cartTotal)}", enabled = !s.isPlacingOrder) { s.placeOrder() }
            Spacer(Modifier.width(12.dp))
            BackAction { s.checkoutStep = 2 }
        }
    }
}
