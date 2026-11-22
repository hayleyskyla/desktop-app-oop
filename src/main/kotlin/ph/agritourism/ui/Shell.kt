package ph.agritourism.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import ph.agritourism.model.Role
import ph.agritourism.ui.dialogs.Overlays
import ph.agritourism.ui.screens.*

@Composable
fun AppShell(s: AppState) {
    // The opening page is a full-window landing route: no sidebar container, no empty nav gutter,
    // and no extra label. Logged-in dashboards keep their normal navigation shell.
    val isOpeningPage = !s.isLoggedIn && s.activePage == Page.HOME
    Box(Modifier.fillMaxSize().background(Palette.Cream).testTag("app-root")) {
        // Scenic farm wallpaper with frosted cream layer
        AsyncImage(s.images.globalFarmBackground, Modifier.fillMaxSize().blur(18.dp))
        Box(Modifier.fillMaxSize().background(Color(0xD9F8F6F0)))

        Column(Modifier.fillMaxSize()) {
            TopBar(s, isOpeningPage)
            Row(Modifier.fillMaxSize()) {
                if (!isOpeningPage) Sidebar(s)
                Box(Modifier.weight(1f).fillMaxHeight().testTag("page-content")) { PageContent(s) }
            }
        }
        Overlays(s)
        Toast(s, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TopBar(s: AppState, isOpeningPage: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(60.dp).shadow(3.dp, ambientColor = Color(0x14000000), spotColor = Color(0x14000000)).background(Color.White.copy(alpha = 0.97f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (!isOpeningPage) {
            IconButton({ s.isSidebarCollapsed = !s.isSidebarCollapsed }, Modifier.handCursor().size(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Palette.Border, RoundedCornerShape(10.dp))) {
                Icon(if (s.isSidebarCollapsed) Icons.AutoMirrored.Outlined.ViewSidebar else Icons.AutoMirrored.Outlined.MenuOpen, "Toggle sidebar", tint = Palette.Ink)
            }
            Spacer(Modifier.width(12.dp))
        }
        Row(Modifier.clip(RoundedCornerShape(10.dp)).handCursor().clickable { s.navigate(if (s.isLoggedIn) Page.DASHBOARD else Page.HOME) }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AsyncImage("res:icons/pwa-192x192.png", Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(10.dp))
            Text("AgriTourism", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Palette.Deep)
            Text("  Philippines", fontSize = 12.sp, color = Palette.Forest)
        }
        Spacer(Modifier.weight(1f))
        androidx.compose.animation.AnimatedVisibility(
            visible = s.isLoggedIn,
            enter = fadeIn(androidx.compose.animation.core.tween(180)) + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut(androidx.compose.animation.core.tween(120)) + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    SearchField(s.searchQuery, { s.searchQuery = it }, "Search farms, tours, produce…",
                        Modifier.width(280.dp).testTag("global-search"))
                    if (s.searchQuery.isNotBlank()) {
                        val popupOffset = with(LocalDensity.current) { IntOffset(0, 44.dp.roundToPx()) }
                        Popup(
                            alignment = Alignment.TopStart,
                            offset = popupOffset,
                            properties = PopupProperties(focusable = false),
                        ) {
                            Surface(
                                Modifier.width(360.dp).heightIn(max = 360.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                shadowElevation = 12.dp,
                            ) {
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                                    if (s.searchResults.isEmpty()) {
                                        Text("No results found", Modifier.fillMaxWidth().padding(16.dp), color = Palette.Muted)
                                    } else {
                                        s.searchResults.take(10).forEach { result ->
                                            Row(
                                                Modifier.fillMaxWidth().clickable { s.openSearchResult(result) }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                AsyncImage(result.image, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                                                Spacer(Modifier.width(10.dp))
                                                Column {
                                                    Text(result.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(result.subtitle, fontSize = 11.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
        }
        if (s.isLoggedIn) {
            BadgedIcon(Icons.Outlined.Notifications, s.unreadCount, "Notification") { s.isNotificationsOpen = !s.isNotificationsOpen }
            Spacer(Modifier.width(8.dp))
            if (s.isTourist) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
                    .handCursor().clickable { s.checkoutStep = 1; s.navigate(Page.CART_CHECKOUT) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BadgedBox({ if (s.cart.isNotEmpty()) Badge(containerColor = Palette.Forest) { Text("${s.cart.size}") } }) {
                        Icon(Icons.Outlined.ShoppingBag, "Cart", tint = Palette.Forest, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("$PESO${money(s.cartValue)}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Palette.Deep)
                }
            }
            Spacer(Modifier.width(12.dp))
        } else {
            SecondaryButton("Log In", icon = Icons.AutoMirrored.Outlined.Login) { s.navigate(Page.LOGIN) }
            Spacer(Modifier.width(8.dp))
            PrimaryButton("Sign Up") { s.navigate(Page.SIGNUP) }
        }
    }
}

@Composable
private fun BadgedIcon(icon: ImageVector, count: Int, desc: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.handCursor().size(40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Palette.Border, RoundedCornerShape(12.dp))) {
        BadgedBox({ if (count > 0) Badge(containerColor = Palette.Amber) { Text("$count") } }) { Icon(icon, desc, tint = Palette.Forest) }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Sidebar(s: AppState) {
    var hovered by remember { mutableStateOf(false) }
    val expanded = !s.isSidebarCollapsed || hovered
    val width by androidx.compose.animation.core.animateDpAsState(if (expanded) 264.dp else 76.dp,
        androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "sidebar-w")
    Column(Modifier.width(width).fillMaxHeight().background(Color.White.copy(alpha = 0.94f)).clipToBounds().testTag("sidebar")
        .onPointerEvent(PointerEventType.Enter) { if (s.isSidebarCollapsed) hovered = true }
        .onPointerEvent(PointerEventType.Exit) { hovered = false }) {
        Row(Modifier.fillMaxWidth().clickable { if (s.isSidebarCollapsed) s.isSidebarCollapsed = false else s.navigate(Page.DASHBOARD) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Mint), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Eco, null, tint = Palette.Forest)
            }
            AnimatedVisibility(expanded, enter = fadeIn(androidx.compose.animation.core.tween(220, 90)), exit = fadeOut(androidx.compose.animation.core.tween(90))) {
                Row {
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("AgriTourism", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = Palette.Deep, softWrap = false)
                        Text("FARM · CONNECT · EXPERIENCE", fontSize = 9.sp, color = Palette.Forest, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, softWrap = false)
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.Border)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            @Composable
            fun item(icon: ImageVector, label: String, active: Boolean, badge: String? = null, onClick: () -> Unit) =
                NavItem(icon, label, active, expanded, badge, onClick)

            // The landing page has no navigation items; role navigation appears only after login.
            if (s.isLoggedIn) {
                item(Icons.Outlined.Layers, "Dashboard", s.activePage == Page.DASHBOARD) { s.navigate(Page.DASHBOARD) }
                if (s.userRole == Role.ADMIN) {
                    item(Icons.Outlined.Map, "Tour", s.activePage == Page.ADMIN_TOURS) { s.navigate(Page.ADMIN_TOURS) }
                    item(Icons.Outlined.AutoAwesome, "Fruit Picking", s.activePage == Page.ADMIN_FRUIT_PICKING) { s.navigate(Page.ADMIN_FRUIT_PICKING) }
                    item(Icons.Outlined.Agriculture, "Products", s.activePage == Page.ADMIN_PRODUCTS) { s.navigate(Page.ADMIN_PRODUCTS) }
                }
                if (s.userRole == Role.FARMER) {
                    item(Icons.Outlined.CalendarMonth, "Tourist Bookings & Check-In", s.activePage == Page.MY_BOOKINGS) { s.navigate(Page.MY_BOOKINGS) }
                    item(Icons.Outlined.ShoppingBag, "Tourist Orders", s.activePage == Page.FARMER_ORDERS, "${s.visibleOrders.size}") { s.navigate(Page.FARMER_ORDERS) }
                    item(Icons.Outlined.Agriculture, "My Produce Catalog", s.activePage == Page.FARMER_PRODUCTS) { s.navigate(Page.FARMER_PRODUCTS) }
                    item(Icons.Outlined.Person, "Farmer Profile & Catalog", s.activePage == Page.FARMER_PROFILE) { s.navigate(Page.FARMER_PROFILE) }
                    item(Icons.Outlined.Schedule, "Guides & Time Slots", s.activePage == Page.FARM_SETUP) { s.navigate(Page.FARM_SETUP) }
                } else if (s.isTourist) {
                    item(Icons.Outlined.Explore, "Explore Tours", s.activePage == Page.FARM_DETAIL) { s.navigate(Page.FARM_DETAIL) }
                    item(Icons.Outlined.AutoAwesome, "Fruit Picking", s.activePage == Page.FRUIT_PICKING) { s.navigate(Page.FRUIT_PICKING) }
                    item(Icons.Outlined.Agriculture, "Vegetables & Harvest", s.activePage == Page.MARKET) { s.navigate(Page.MARKET) }
                    item(Icons.AutoMirrored.Outlined.MenuBook, "Farmers & Stories", s.activePage == Page.FARMERS_STORIES, "Profiles") { s.navigate(Page.FARMERS_STORIES) }
                    item(Icons.Outlined.CalendarMonth, "My Bookings", s.activePage == Page.MY_BOOKINGS) { s.navigate(Page.MY_BOOKINGS) }
                    item(Icons.Outlined.FavoriteBorder, "My Wishlist", s.activePage == Page.WISHLIST) { s.navigate(Page.WISHLIST) }
                    item(Icons.Outlined.ShoppingBag, "My Purchases", s.activePage == Page.MY_PURCHASES, "${s.visibleOrders.size}") { s.navigate(Page.MY_PURCHASES) }
                }
                if (s.userRole != Role.ADMIN) {
                    item(Icons.Outlined.HelpOutline, "Help & FAQs", s.activePage == Page.HELP) { s.navigate(Page.HELP) }
                }
            }
            if (s.isLoggedIn && s.userRole == Role.FARMER && expanded) {
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Add Farm Product", Modifier.fillMaxWidth(), Icons.Filled.Add, color = Color(0xFF15803D)) { s.openAddProduct() }
                SecondaryButton("Add Walk-in Tour", Modifier.fillMaxWidth(), Icons.Outlined.EventAvailable) { s.isAddBookingModalOpen = true }
            }
        }
        HorizontalDivider(color = Palette.Border)
        // The account block is one bottom row: profile on the left, exit arrow directly opposite on the right.
        // Sizes adapt to the collapsed dock so the two controls never overlap.
        val avatarSize = if (expanded) 40.dp else 28.dp
        val logoutButtonSize = if (expanded) 40.dp else 32.dp
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (expanded) 14.dp else 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val profileModifier = Modifier.weight(1f).then(
                if (s.isTourist) {
                    Modifier.handCursor().clickable { s.isProfileModalOpen = true }.testTag("tourist-profile")
                } else Modifier.testTag("sidebar-profile")
            )
            Row(profileModifier, verticalAlignment = Alignment.CenterVertically) {
                if (s.isLoggedIn) {
                    Avatar(s.avatar, avatarSize)
                    AnimatedVisibility(expanded, enter = fadeIn(androidx.compose.animation.core.tween(220, 90)), exit = fadeOut(androidx.compose.animation.core.tween(90))) {
                        Row {
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(s.displayName, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Palette.Deep, softWrap = false)
                                Text(s.userRole.subtitle, fontSize = 11.sp, color = Palette.Forest, softWrap = false)
                            }
                        }
                    }
                } else {
                    Icon(Icons.Outlined.Monitor, null, tint = Palette.Forest)
                    if (expanded) {
                        Spacer(Modifier.width(10.dp))
                        Text("Catalog Preview", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            if (s.isLoggedIn) {
                val logoutTag = if (s.userRole == Role.ADMIN) "admin-logout" else "sidebar-logout"
                TooltipArea(tooltip = {
                    Surface(shape = RoundedCornerShape(8.dp), color = Palette.Deep, shadowElevation = 6.dp) {
                        Text("Log Out", Modifier.padding(8.dp), color = Color.White, fontSize = 12.sp)
                    }
                }) {
                    IconButton(
                        onClick = { s.signOut() },
                        modifier = Modifier.handCursor().size(logoutButtonSize).clip(RoundedCornerShape(12.dp)).testTag(logoutTag),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, "Log Out", tint = Palette.Forest)
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NavItem(icon: ImageVector, label: String, active: Boolean, expanded: Boolean, badge: String?, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(when { active -> Palette.Forest; hovered -> Palette.Mint; else -> Color.Transparent },
        androidx.compose.animation.core.tween(200), label = "nav-bg")
    val fg by animateColorAsState(if (active) Color.White else Palette.Ink, androidx.compose.animation.core.tween(200), label = "nav-fg")
    val nudge by androidx.compose.animation.core.animateDpAsState(if (hovered && !active && expanded) 4.dp else 0.dp,
        androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMedium), label = "nav-nudge")
    val bar by androidx.compose.animation.core.animateDpAsState(if (active) 18.dp else 0.dp,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "nav-bar")
    TooltipArea(tooltip = { if (!expanded) Surface(shape = RoundedCornerShape(8.dp), color = Palette.Deep, shadowElevation = 6.dp) { Text(label, Modifier.padding(8.dp), color = Color.White, fontSize = 12.sp) } }) {
        Box(contentAlignment = Alignment.CenterStart) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg)
                .hoverable(interaction).handCursor().clickable(interaction, LocalIndication.current, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 11.dp).offset(x = nudge), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, label, Modifier.size(20.dp), tint = fg)
                AnimatedVisibility(expanded, Modifier.weight(1f), enter = fadeIn(androidx.compose.animation.core.tween(220, 90)) + slideInHorizontally { -it / 6 },
                    exit = fadeOut(androidx.compose.animation.core.tween(90))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(14.dp))
                        Text(label, Modifier.weight(1f), fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold, fontSize = 13.sp, color = fg,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                        if (badge != null) Pill(badge, if (active) Color.White.copy(alpha = 0.2f) else Palette.Mint, if (active) Color.White else Palette.Forest)
                    }
                }
            }
            Box(Modifier.offset(x = (-12).dp).width(4.dp).height(bar).clip(RoundedCornerShape(2.dp)).background(Palette.Forest))
        }
    }
}

@Composable
private fun PageContent(s: AppState) {
    val scroll = rememberScrollState()
    LaunchedEffect(s.activePage) { scroll.scrollTo(0) }
    AnimatedContent(
        targetState = s.activePage,
        transitionSpec = {
            val ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
            (fadeIn(androidx.compose.animation.core.tween(320, delayMillis = 60, easing = ease)) +
                slideInVertically(androidx.compose.animation.core.tween(380, delayMillis = 60, easing = ease)) { 36 }) togetherWith
                fadeOut(androidx.compose.animation.core.tween(120))
        },
        label = "page",
    ) { p ->
        val farmerBlocked = s.isFarmer && p in setOf(Page.FARM_DETAIL, Page.FRUIT_PICKING, Page.MARKET, Page.FARMERS_STORIES)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                farmerBlocked -> FarmerRestrictionNotice(s)
                !s.canAccess(p) -> AccessDeniedNotice(s)
                p == Page.DASHBOARD -> when (s.userRole) {
                    Role.ADMIN -> AdminDashboard(s)
                    Role.FARMER -> FarmerDashboard(s)
                    Role.USER -> TouristDashboard(s)
                }
                p == Page.FARM_DETAIL -> ExploreTours(s)
                p == Page.FRUIT_PICKING -> FruitPickingPage(s)
                p == Page.MARKET -> MarketPage(s)
                p == Page.FARMERS_STORIES -> FarmersStoriesPage(s)
                p == Page.CART_CHECKOUT -> CheckoutPage(s)
                p == Page.MY_BOOKINGS -> MyBookingsPage(s)
                p == Page.MY_PURCHASES -> MyPurchasesPage(s)
                p == Page.WISHLIST -> WishlistPage(s)
                p == Page.FARMER_ORDERS -> FarmerOrdersPage(s)
                p == Page.FARMER_PRODUCTS -> FarmerProductsPage(s)
                p == Page.HOME -> CatalogHome(s)
                p == Page.SIGNUP -> SignupPage(s)
                p == Page.LOGIN -> LoginPage(s)
                p == Page.FARMER_PROFILE -> FarmerProfilePage(s)
                p == Page.FARM_SETUP -> FarmSetupPage(s)
                p == Page.PLATFORM_SETTINGS -> PlatformSettingsPage(s)
                p == Page.ADMIN_TOURS -> AdminToursPage(s)
                p == Page.ADMIN_FRUIT_PICKING -> AdminFruitPickingPage(s)
                p == Page.ADMIN_PRODUCTS -> AdminProductsPage(s)
                p == Page.HELP -> HelpPage(s)
            }
        }
    }
}

@Composable
private fun AccessDeniedNotice(s: AppState) {
    GlassCard(Modifier.fillMaxWidth(), padding = 40.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(44.dp), tint = Palette.Muted)
            H2(if (s.isLoggedIn) "Access restricted" else "Please log in")
            Sub(if (s.isLoggedIn) "Your ${s.userRole.subtitle} account does not have permission to view this page." else "You need to log in to view this page.")
            if (s.isLoggedIn) TextAction("Back") { s.navigate(Page.DASHBOARD) }
            else PrimaryButton("Log In") { s.navigate(Page.LOGIN) }
        }
    }
}

@Composable
private fun Toast(s: AppState, modifier: Modifier) {
    val msg = rememberLast(s.toastMessage)
    AnimatedVisibility(s.toastMessage != null, modifier.padding(bottom = 28.dp),
        enter = fadeIn(androidx.compose.animation.core.tween(200)) + slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) { it } + scaleIn(initialScale = 0.9f),
        exit = fadeOut(androidx.compose.animation.core.tween(160)) + slideOutVertically { it / 2 }) {
        Row(Modifier.shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Palette.Deep)
            .padding(horizontal = 20.dp, vertical = 14.dp).widthIn(max = 640.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Color(0xFF86EFAC))
            Spacer(Modifier.width(10.dp))
            Text(msg.orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            TextAction("Close", color = Color.White.copy(alpha = 0.8f)) { s.toastMessage = null }
        }
    }
}
