package ph.agritourism.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ph.agritourism.data.Database
import ph.agritourism.model.*
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

enum class Page {
    DASHBOARD, HOME, FARM_DETAIL, FRUIT_PICKING, MARKET, FARMERS_STORIES, MY_BOOKINGS, CART_CHECKOUT,
    WISHLIST, MY_PURCHASES, FARMER_ORDERS, FARMER_PRODUCTS, FARMER_PROFILE, SIGNUP, LOGIN, FARM_SETUP,
    PLATFORM_SETTINGS, ADMIN_TOURS, ADMIN_FRUIT_PICKING, ADMIN_PRODUCTS, TRASH_BIN, HELP
}

enum class SearchResultType { FARM, TOUR, ORCHARD, PRODUCT }

data class CatalogSearchResult(
    val type: SearchResultType,
    val id: Long,
    val title: String,
    val subtitle: String,
    val image: String,
)

/** Picture-and-description preview of a catalog item, shown to guests on the opening page. */
data class CatalogPreview(val image: String, val tag: String, val title: String, val description: String, val details: List<Pair<String, String>> = emptyList())

enum class ShowcaseCategory { ALL, PLACES, PRODUCTS, ORCHARDS }

const val PESO = "₱"

/** Marketplace order states; delivery milestones are intentionally not tracked. */
val ORDER_STATUSES = listOf("Pending", "Confirmed", "Cancelled")
val TOUR_PAYMENT_METHODS = listOf(PAY_AT_FARM_PAYMENT_METHOD)
val WALK_IN_PAYMENT_METHODS = listOf(PAY_AT_FARM_PAYMENT_METHOD)
val CHECKOUT_PAYMENT_METHODS = listOf(COD_PAYMENT_METHOD)

private val STAMP = DateTimeFormatter.ofPattern("MMM d, yyyy · hh:mm a", Locale.ENGLISH)
fun nowStamp(): String = LocalDateTime.now().format(STAMP)

/** Parses ISO (2026-11-20) or display (Nov 20, 2026) booking dates. */
fun parseTourDate(d: String): LocalDate? = parseDate(d)

/** Parses an order date such as "Nov 19, 2026 · 10:15 AM". */
fun parseOrderDate(d: String): LocalDate? = parseTourDate(d.substringBefore("·"))

data class MonthPoint(val label: String, val value: Double)
data class ProductSalesMetric(val productName: String, val quantity: Int, val revenue: Double)

/** Buckets dated values into the last [months] calendar months, ending at the later of today or the newest entry. */
fun monthlySeries(entries: List<Pair<LocalDate, Double>>, months: Int = 6): List<MonthPoint> {
    val now = java.time.YearMonth.now()
    val end = entries.maxOfOrNull { java.time.YearMonth.from(it.first) }?.takeIf { it.isAfter(now) } ?: now
    val sums = entries.groupBy({ java.time.YearMonth.from(it.first) }, { it.second }).mapValues { it.value.sum() }
    return (months - 1 downTo 0).map { i ->
        val ym = end.minusMonths(i.toLong())
        MonthPoint(ym.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH), sums[ym] ?: 0.0)
    }
}

fun cumulativeMonthlyCount(dates: List<LocalDate>, months: Int = 6): List<MonthPoint> {
    require(months > 0) { "At least one month is required." }
    val now = java.time.YearMonth.now()
    val end = dates.maxOfOrNull { java.time.YearMonth.from(it) }?.takeIf { it.isAfter(now) } ?: now
    val start = end.minusMonths((months - 1).toLong())
    val counts = dates.groupingBy { java.time.YearMonth.from(it) }.eachCount()
    var total = dates.count { java.time.YearMonth.from(it).isBefore(start) }
    return (0 until months).map { offset ->
        val month = start.plusMonths(offset.toLong())
        total += counts[month] ?: 0
        MonthPoint(month.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH), total.toDouble())
    }
}

fun money(v: Number): String {
    val d = v.toDouble()
    return if (d == Math.floor(d)) "%,d".format(d.toLong()) else "%,.2f".format(d)
}

class AppState(val db: Database, private val scope: CoroutineScope) {
    // static catalog (from SQLite)
    var images by mutableStateOf(db.images())
    var settings by mutableStateOf(db.settings())
    var guides by mutableStateOf(db.guides())
    var couriers by mutableStateOf(db.couriers())
    var timeSlots by mutableStateOf(db.timeSlots())
    /** Every tour/orchard including deleted (archived) ones, so old bookings still find their farm. */
    var allTours by mutableStateOf(db.tours())
    var allOrchards by mutableStateOf(db.fruitPicking())
    val tours: List<FarmTour> get() = allTours.filterNot { it.archived }
    val fruitPicking: List<FruitPickingItem> get() = allOrchards.filterNot { it.archived }
    var farmers by mutableStateOf(db.farmers())
    var reviews by mutableStateOf(db.reviews())
    var faqs by mutableStateOf(db.faqs())

    // navigation & role
    // The app starts signed out; every role-specific page and action needs a verified login.
    var activePage by mutableStateOf(Page.HOME)
    var userRole by mutableStateOf(Role.USER)
    var isLoggedIn by mutableStateOf(false)
    var currentUser by mutableStateOf<User?>(null)
    var showcaseCategory by mutableStateOf(ShowcaseCategory.ALL)
    var isSidebarCollapsed by mutableStateOf(false)

    // login
    var loginEmail by mutableStateOf("")
    var loginPassword by mutableStateOf("")
    var loginRole by mutableStateOf(Role.USER)
    var loginError by mutableStateOf<String?>(null)

    // selections
    var selectedTour by mutableStateOf(tours.first())
    var selectedFruit by mutableStateOf(fruitPicking.first())
    var isTourModalOpen by mutableStateOf(false)
    var isFruitModalOpen by mutableStateOf(false)

    // tour/fruit reservation inputs
    var tourDate by mutableStateOf(LocalDate.now().plusDays(7).toString())
    var tourTime by mutableStateOf("")
    var tourGuests by mutableStateOf(1)
    var tourPaymentMethod by mutableStateOf(PAY_AT_FARM_PAYMENT_METHOD)
    var fruitDate by mutableStateOf(LocalDate.now().plusDays(7).toString())
    var fruitTime by mutableStateOf("")
    var fruitGuests by mutableStateOf(2)
    var fruitPaymentMethod by mutableStateOf(PAY_AT_FARM_PAYMENT_METHOD)

    // persisted collections
    /** Every product including deleted (archived) ones, so old orders still find their farm. */
    var allProducts by mutableStateOf(db.products())
    val products: List<Product> get() = allProducts.filterNot { it.archived }
    var bookings by mutableStateOf(db.bookings())
    var farmerOrders by mutableStateOf(db.orders())
    var notifications by mutableStateOf(db.notifications())
    var userAccounts by mutableStateOf(db.users())
    /** Soft-deleted farmer accounts restored from the Admin Trash Bin. */
    var trashFarmers by mutableStateOf(db.deletedFarmers())
    var adminListingFarmId by mutableStateOf<Int?>(null)
    var favoriteIds by mutableStateOf(emptySet<Long>())
    var cart by mutableStateOf(emptyList<CartItem>())
    /** Cart items the tourist unticked; everything else is bought at checkout (new items start ticked). */
    var cartUnselected by mutableStateOf(emptySet<Long>())

    // checkout
    var checkoutStep by mutableStateOf(1)
    var recipientName by mutableStateOf("")
    var contactPhone by mutableStateOf("")
    var deliveryAddress by mutableStateOf("")
    var deliveryNotes by mutableStateOf("")
    var paymentMethod by mutableStateOf(COD_PAYMENT_METHOD)
    var lastOrderTotal by mutableStateOf(0.0)
    var lastOrderReference by mutableStateOf("")
    var lastOrderPaymentMethod by mutableStateOf(COD_PAYMENT_METHOD)
    var lastPlacedOrder by mutableStateOf<FarmerOrder?>(null)
    var purchaseConfirmationOrder by mutableStateOf<FarmerOrder?>(null)
    var isPlacingOrder by mutableStateOf(false)

    // overlays
    var isNotificationsOpen by mutableStateOf(false)
    var selectedNotification by mutableStateOf<AppNotification?>(null)
    var isProfileModalOpen by mutableStateOf(false)
    var isAddProductModalOpen by mutableStateOf(false)
    /** A pending "are you sure?" question; the dialog runs [Confirm.action] on Yes. */
    data class Confirm(
        val title: String,
        val message: String,
        val yesLabel: String,
        val action: () -> Unit,
        val cancelLabel: String = "Keep",
    )
    var pendingConfirm by mutableStateOf<Confirm?>(null)
    var editingTour by mutableStateOf<FarmTour?>(null)
    var editingOrchard by mutableStateOf<FruitPickingItem?>(null)
    var newProductDiscount by mutableStateOf("0")
    /** Product being edited in the product modal; null when adding a new one. */
    var editingProductId by mutableStateOf<Long?>(null)
    var isAddBookingModalOpen by mutableStateOf(false)
    var isMockupDrawerOpen by mutableStateOf(false)
    var selectedBookingForView by mutableStateOf<Booking?>(null)
    var selectedBookingForTicket by mutableStateOf<Booking?>(null)
    var editingBooking by mutableStateOf<Booking?>(null)
    var reviewingBooking by mutableStateOf<Booking?>(null)
    var deletingBookingId by mutableStateOf<Long?>(null)
    var selectedOrderForView by mutableStateOf<FarmerOrder?>(null)
    var selectedFarmerForModal by mutableStateOf<FarmerProfile?>(null)
    var editingFarmerStory by mutableStateOf<FarmerProfile?>(null)
    var selectedProductForView by mutableStateOf<Product?>(null)
    var selectedReceipt by mutableStateOf<Receipt?>(null)
    var catalogPreview by mutableStateOf<CatalogPreview?>(null)
    var isAddTourModalOpen by mutableStateOf(false)
    var isAddOrchardModalOpen by mutableStateOf(false)
    var isEditStoryOpen by mutableStateOf(false)
    var toastMessage by mutableStateOf<String?>(null)
    private var toastJob: Job? = null
    private val orderSubmissionInProgress = AtomicBoolean(false)

    // filters
    var marketFilter by mutableStateOf("All")
    var searchQuery by mutableStateOf("")
    var bookingsFilter by mutableStateOf("All")
    var ordersFilter by mutableStateOf("All")
    var farmerStoriesFilter by mutableStateOf("All")
    var farmerSearchQuery by mutableStateOf("")
    var currentSlide by mutableStateOf(0)

    // walk-in booking form
    var newBookingCustomer by mutableStateOf("")
    var newBookingPhone by mutableStateOf("")
    var newBookingActivity by mutableStateOf(tours.first().name)
    var newBookingDate by mutableStateOf(LocalDate.now().plusDays(7).toString())
    var newBookingTime by mutableStateOf("")
    var newBookingGuests by mutableStateOf(1)
    var newBookingPayment by mutableStateOf(PAY_AT_FARM_PAYMENT_METHOD)

    // add product form
    var newProductName by mutableStateOf("")
    var newProductCategory by mutableStateOf("Vegetables")
    var newProductPrice by mutableStateOf("100")
    var newProductUnit by mutableStateOf("kg")
    var newProductStock by mutableStateOf("25")
    var newProductDesc by mutableStateOf("")
    var newProductImage by mutableStateOf(images.presetTomatoImage.ifBlank { "res:images/photo-1598170845058-32b9d6a5da37-w600.jpg" })
    /** Extra product photos kept in the shared listing_images gallery; the main photo is separate. */
    var newProductGallery by mutableStateOf<List<String>>(emptyList())

    init {
        newProductImage = products.firstOrNull { it.id == 101L }?.imageUrl ?: newProductImage
        scope.launch {
            while (true) {
                delay(3800)
                currentSlide = (currentSlide + 1) % farmers.size
            }
        }
    }

    private fun loadCart(): List<CartItem> {
        val byId = db.products().filterNot { it.archived }.associateBy { it.id }
        val user = currentUser?.email ?: return emptyList()
        return db.cart(user).mapNotNull { (id, q) -> byId[id]?.let { CartItem(it, q) } }
    }

    private fun persistCart(items: List<CartItem>) {
        val user = currentUser?.email ?: return
        db.tx {
            db.clearCart(user)
            items.forEachIndexed { i, c -> db.setCartItem(user, c.product.id, c.quantity, i) }
        }
        cart = items
    }

    // ---------------------------------------------------------------- reference data (from SQLite)
    fun setting(key: String): String = settings[key].orEmpty()
    val deliveryFee: Double get() = settings["delivery_fee"]?.toDoubleOrNull() ?: 0.0

    /** Time slots a tour ("tour") or orchard ("orchard") offers. */
    fun slotsFor(type: String, id: Int): List<String> = timeSlots.filter { it.activityType == type && it.activityId == id }.map { it.label }
    fun slotsFor(b: Booking): List<String> = slotsFor(b.activityType, b.farmId)

    /** Guides of a farm (admins choosing for an unknown farm see every guide). */
    fun guidesFor(farmId: Int?): List<String> =
        (if (farmId == null) guides else guides.filter { it.farmerId == farmId }).map { it.name }
    fun guidesFor(b: Booking): List<String> = guidesFor(farmOf(b))

    /** Product categories configured in settings plus any already used by products. */
    val productCategories: List<String>
        get() = (setting("product_categories").split(",").map { it.trim() } + products.map { it.category }).filter { it.isNotBlank() }.distinct()
    val provinces: List<String> get() = farmers.map { it.province.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    val maxDiscount: Int get() = products.filter { it.stock > 0 }.maxOfOrNull { it.discountPercent } ?: 0

    private fun defaultGuide(farmId: Int?, host: String): String? =
        guidesFor(farmId).firstOrNull() ?: host.takeIf { it.isNotBlank() }?.let { "$it (Farm Host & Guide)" }

    // ---------------------------------------------------------------- derived
    val roleFilteredNotifications: List<AppNotification>
        get() {
            if (!isLoggedIn) return emptyList()
            val mine = visibleBookings.map { it.id }.toSet()
            // The generic "Booking" and "Tour Booking Confirmed" notices are retired; real
            // acceptance/decline/cancellation updates use the booking_update label.
            val current = notifications.filterNot { it.title == "Booking" || it.title == "Tour Booking Confirmed" }
            return when (userRole) {
                Role.FARMER -> current.filter { it.targetRole == "farmer" && it.recipientEmail.equals(userEmail, true) }
                // Tourists see broadcasts plus notifications addressed to them or about their own bookings.
                Role.USER -> current.filter { n ->
                    n.targetRole == "tourist" && when {
                        n.recipientEmail != null -> n.recipientEmail.equals(userEmail, true)
                        n.bookingId != null -> n.bookingId in mine
                        else -> true
                    }
                }
                Role.ADMIN -> current
            }
        }

    val unreadCount get() = roleFilteredNotifications.count { !it.read }

    /** Cart items ticked for checkout. */
    val checkoutItems: List<CartItem> get() = cart.filter { it.product.id !in cartUnselected }
    val cartValue: Double get() = cart.sumOf { it.product.discountedPrice * it.quantity }
    val cartSubtotal: Double get() = checkoutItems.sumOf { it.product.discountedPrice * it.quantity }
    val cartTotal: Double get() = if (checkoutItems.isNotEmpty()) cartSubtotal + deliveryFee else 0.0

    fun toggleCartItem(id: Long) { cartUnselected = if (id in cartUnselected) cartUnselected - id else cartUnselected + id }
    fun selectAllCart(selected: Boolean) { cartUnselected = if (selected) emptySet() else cart.map { it.product.id }.toSet() }

    val filteredProducts: List<Product>
        get() = products.filter { p ->
            (marketFilter == "All" || p.category == marketFilter) &&
                (p.name.contains(searchQuery, true) || p.farmOrigin.contains(searchQuery, true))
        }

    val searchResults: List<CatalogSearchResult>
        get() {
            val query = searchQuery.trim()
            if (query.isEmpty()) return emptyList()
            fun matches(vararg fields: String) = fields.any { it.contains(query, ignoreCase = true) }
            return buildList {
                farmers.filter { f ->
                    matches(f.name, f.farmName, f.location, f.province, f.storyQuote, f.fullBiography, f.specialtyProduce.joinToString())
                }.forEach { add(CatalogSearchResult(SearchResultType.FARM, it.id.toLong(), it.farmName, "${it.name} · ${it.location}", it.farmBannerUrl)) }
                tours.filter { t ->
                    matches(t.name, t.location, t.description, t.highlightTag, t.farmerName, t.activities.joinToString(), t.amenities.joinToString())
                }.forEach { add(CatalogSearchResult(SearchResultType.TOUR, it.id.toLong(), it.name, "Farm tour · ${it.location}", it.imageUrl)) }
                fruitPicking.filter { f ->
                    matches(f.title, f.farmName, f.location, f.fruitName, f.description, f.seasonalFruits, f.seasonAvailability)
                }.forEach { add(CatalogSearchResult(SearchResultType.ORCHARD, it.id.toLong(), it.title, "Fruit picking · ${it.location}", it.imageUrl)) }
                products.filter { it.stock > 0 && matches(it.name, it.category, it.farmOrigin, it.description) }
                    .forEach { add(CatalogSearchResult(SearchResultType.PRODUCT, it.id, it.name, "Available ${it.category.lowercase()} · ${it.farmOrigin}", it.imageUrl)) }
            }
        }

    val displayName: String get() = currentUser?.fullName ?: "Guest"
    val userEmail: String get() = currentUser?.email.orEmpty()
    val userPhone: String get() = currentUser?.phone.orEmpty()

    // ---------------------------------------------------------------- farm ownership
    /** The farm a farmer account manages (null for tourists/admins or unlinked farmers). */
    val myFarmId: Int? get() = if (isFarmer) currentUser?.farmerId else null
    val myFarm: FarmerProfile? get() = myFarmId?.let { id -> farmers.firstOrNull { it.id == id } }
    val myFarmLabel: String get() = myFarm?.farmName ?: if (isAdmin) "All Partner Farms" else "Your Farm"

    // ---------------------------------------------------------------- capacity & availability
    fun capacityFor(farmId: Int?): Int = db.farmCapacity(farmId)
    fun paxHeldFor(farmId: Int?): Int = db.confirmedPaxForFarm(farmId)
    fun availablePaxFor(farmId: Int?): Int = db.availablePaxForFarm(farmId)
    fun availablePaxFor(tour: FarmTour): Int = availablePaxFor(tour.farmerId)
    fun availablePaxFor(item: FruitPickingItem): Int = availablePaxFor(item.farmerId)

    fun availabilityFor(tour: FarmTour): AvailabilityStatus =
        availabilityStatus(tour.bookingEnabled, availablePaxFor(tour), capacityFor(tour.farmerId))

    fun availabilityFor(item: FruitPickingItem): AvailabilityStatus =
        availabilityStatus(true, availablePaxFor(item), capacityFor(item.farmerId))

    /** Updates the signed-in farmer's capacity; returns an error or null on success. */
    fun saveFarmCapacity(capacityText: String): String? {
        val farmId = myFarmId ?: return "Only farmers linked to a farm can set capacity."
        val capacity = capacityText.trim().toIntOrNull() ?: return "Enter a positive whole number."
        val error = runCatching { db.setFarmCapacity(farmId, capacity) }.exceptionOrNull()?.message
        if (error != null) return error
        farmers = db.farmers()
        showToast("Farm capacity set to $capacity pax.")
        return null
    }

    /** Which farm runs the tour/orchard a booking is for. */
    fun farmOf(b: Booking): Int? =
        if (b.activityType == "orchard") allOrchards.firstOrNull { it.id == b.farmId }?.farmerId
        else allTours.firstOrNull { it.id == b.farmId }?.farmerId

    /** Farms whose products are in an order. */
    fun farmsOf(o: FarmerOrder): Set<Int> {
        val byId = allProducts.associateBy { it.id }
        return o.items.mapNotNull { it.productId?.let(byId::get)?.farmerId }.toSet()
    }

    /** Part of an order's value that belongs to the current farmer (whole order for admins). */
    fun farmShare(o: FarmerOrder): Double {
        if (!isFarmer) return o.totalAmount
        val mine = allProducts.filter { myFarmId != null && it.farmerId == myFarmId }.map { it.id }.toSet()
        return o.items.filter { it.productId in mine }.sumOf { it.qty * it.unitPrice }
    }

    /** Admin visibility only; changing a booking or order always requires the owning farmer. */
    fun canManageBooking(b: Booking) = isAdmin || (myFarmId != null && farmOf(b) == myFarmId)
    fun canManageOrder(o: FarmerOrder) = isAdmin || (myFarmId != null && myFarmId in farmsOf(o))
    fun canActOnBooking(b: Booking) = isFarmer && canManageBooking(b)
    fun canActOnOrder(o: FarmerOrder) = isFarmer && canManageOrder(o)

    val myTours: List<FarmTour> get() = if (isAdmin) tours else tours.filter { myFarmId != null && it.farmerId == myFarmId }
    val myOrchards: List<FruitPickingItem> get() = if (isAdmin) fruitPicking else fruitPicking.filter { myFarmId != null && it.farmerId == myFarmId }

    /** Tourists see their own reservations, farmers their farm's, admins all. */
    val visibleBookings: List<Booking>
        get() = when {
            !isLoggedIn -> emptyList()
            userRole == Role.USER -> bookings.filter(::ownsBooking)
            else -> bookings.filter(::canManageBooking)
        }

    /** Tourists see their own orders, farmers orders containing their produce, admins all. */
    val visibleOrders: List<FarmerOrder>
        get() = when {
            !isLoggedIn -> emptyList()
            userRole == Role.USER -> farmerOrders.filter(::ownsOrder)
            else -> farmerOrders.filter(::canManageOrder)
        }

    /** Booking awaiting the tourist's 1-day attendance answer (linked through an attendance_reminder notification). */
    val pendingAttendanceBooking: Booking?
        get() {
            val mine = visibleBookings.filter { it.status != "Cancelled" }
            val reminded = notifications.filter { it.type == "attendance_reminder" }.mapNotNull { it.bookingId }.toSet()
            return mine.firstOrNull { it.id in reminded }
        }

    // ---------------------------------------------------------------- dashboard metrics (live from SQLite)
    val farmProducts: List<Product>
        get() = when { isAdmin -> products; myFarmId != null -> products.filter { it.farmerId == myFarmId }; else -> emptyList() }
    val activeBookings: List<Booking> get() = visibleBookings.filter { it.status != "Cancelled" }
    val openOrders: List<FarmerOrder> get() = visibleOrders.filter { it.fulfillmentStatus != "Cancelled" && !it.isReceivedByUser }
    val totalRevenue: Double get() = visibleOrders.filter { it.fulfillmentStatus != "Cancelled" }.sumOf(::farmShare) + activeBookings.sumOf { it.totalAmount }
    val fulfillmentRate: Double get() = if (bookings.isEmpty()) 0.0 else bookings.count { it.status != "Cancelled" } * 100.0 / bookings.size
    val orderStatusCounts: Map<String, Int>
        get() = ORDER_STATUSES.associateWith { status -> farmerOrders.count { it.fulfillmentStatus == status } }
    val productSales: List<ProductSalesMetric>
        get() = farmerOrders.filter { it.fulfillmentStatus != "Cancelled" }
            .flatMap { order ->
                if (!isFarmer) order.items
                else {
                    val owned = allProducts.filter { it.farmerId == myFarmId }.map { it.id }.toSet()
                    order.items.filter { it.productId in owned }
                }
            }
            .groupBy { it.productName }
            .map { (name, items) -> ProductSalesMetric(name, items.sumOf { it.qty }, items.sumOf { it.qty * it.unitPrice }) }
            .sortedWith(compareByDescending<ProductSalesMetric> { it.quantity }.thenByDescending { it.revenue })
    val monthlyBookings: List<MonthPoint>
        get() = monthlySeries(bookings.mapNotNull { b -> parseTourDate(b.tourDate)?.let { it to 1.0 } })
    val registeredUsersOverTime: List<MonthPoint>
        get() = cumulativeMonthlyCount(userAccounts.mapNotNull { account ->
            account.createdAt.takeIf { it.isNotBlank() }?.take(10)?.let(::parseTourDate)
        })
    val monthlyRevenue: List<MonthPoint>
        get() = monthlySeries(
            visibleOrders.filter { it.fulfillmentStatus != "Cancelled" }.mapNotNull { o -> parseOrderDate(o.orderDate)?.let { it to farmShare(o) } } +
                activeBookings.mapNotNull { b -> parseTourDate(b.tourDate)?.let { it to b.totalAmount.toDouble() } }
        )
    val myReviews: List<CustomerReview> get() = reviews.filter { r -> r.userEmail?.equals(userEmail, true) ?: r.author.equals(displayName, true) }
    val favoriteNames: List<String>
        get() = favoriteIds.mapNotNull { id ->
            allTours.firstOrNull { it.id.toLong() == id }?.name ?: allOrchards.firstOrNull { it.id + 100L == id }?.title
        }

    val avatar: String get() = currentUser?.avatarUrl.orEmpty()

    // ---------------------------------------------------------------- generic
    fun showToast(msg: String) {
        toastMessage = msg
        toastJob?.cancel()
        toastJob = scope.launch { delay(3200); toastMessage = null }
    }

    // ---------------------------------------------------------------- access control
    val isTourist get() = isLoggedIn && userRole == Role.USER
    val isFarmer get() = isLoggedIn && userRole == Role.FARMER
    val isAdmin get() = isLoggedIn && userRole == Role.ADMIN
    /** Only farmer hosts may use the farm-side booking/order controls; admins are read-only. */
    val isStaff get() = isFarmer

    fun ownsBooking(b: Booking) = isTourist && b.customerEmail.equals(userEmail, true)
    fun ownsOrder(o: FarmerOrder) = isTourist && o.customerEmail.equals(userEmail, true)
    fun canViewBooking(b: Booking) = canManageBooking(b) || ownsBooking(b)
    fun canViewOrder(o: FarmerOrder) = canManageOrder(o) || ownsOrder(o)

    /** Admin-only raw SQL; reloads app state afterwards so the screens match the database. */
    fun runAdminSql(sql: String): Database.QueryResult {
        check(isAdmin) { "Only administrators can run SQL." }
        val r = db.runSql(sql)
        if (r.columns.isEmpty()) reloadFromDb()
        return r
    }

    /** Which pages each role may open. */
    fun canAccess(p: Page): Boolean = when (p) {
        Page.HOME, Page.LOGIN, Page.SIGNUP -> true
        Page.HELP -> !isAdmin
        // Tourist pages need a login; guests only see the public catalog on the home page.
        Page.FARM_DETAIL, Page.FRUIT_PICKING, Page.MARKET, Page.FARMERS_STORIES -> isTourist
        Page.DASHBOARD, Page.MY_BOOKINGS -> isLoggedIn
        Page.WISHLIST -> isTourist
        Page.MY_PURCHASES -> isTourist
        Page.CART_CHECKOUT -> isTourist
        Page.FARMER_ORDERS, Page.FARMER_PRODUCTS -> isFarmer
        Page.FARMER_PROFILE -> isFarmer
        Page.PLATFORM_SETTINGS, Page.ADMIN_TOURS, Page.ADMIN_FRUIT_PICKING, Page.ADMIN_PRODUCTS, Page.TRASH_BIN -> isAdmin
        Page.FARM_SETUP -> isFarmer && myFarmId != null
    }

    /** Returns true (and explains why) when the current user may NOT perform an action. */
    private fun denied(allowed: Boolean, what: String): Boolean {
        if (!isLoggedIn) { showToast("Please log in to $what."); activePage = Page.LOGIN; return true }
        if (!allowed) { showToast("Your ${userRole.subtitle} account is not allowed to $what."); return true }
        return false
    }

    private fun booking(id: Long) = bookings.firstOrNull { it.id == id }
    private fun order(id: String) = farmerOrders.firstOrNull { it.id == id }

    fun navigate(p: Page) {
        when {
            canAccess(p) -> activePage = p
            !isLoggedIn -> { showToast("Please log in to open that page."); activePage = Page.LOGIN }
            else -> showToast("Your ${userRole.subtitle} account cannot open that page.")
        }
    }

    fun openShowcase(c: ShowcaseCategory) { showcaseCategory = c; activePage = Page.HOME }

    /** Only tourists can open booking modals; guests, farmers, and admins get a read-only preview. */
    private fun fromCatalog(preview: CatalogPreview, open: () -> Unit) {
        if (isTourist) open() else catalogPreview = preview
    }

    fun openCatalogTour(t: FarmTour) = fromCatalog(CatalogPreview(t.imageUrl, t.location, t.name, t.description, listOf(
        "Price" to "$PESO${money(t.pricePerPerson)} per person", "Hours" to t.operatingHours, "Rating" to "★ ${t.rating} (${t.reviewsCount} reviews)",
        "Activities" to t.activities.joinToString(), "Amenities" to t.amenities.joinToString(),
    ).filter { it.second.isNotBlank() })) { selectedTour = t; isTourModalOpen = true }

    fun openCatalogOrchard(f: FruitPickingItem) = fromCatalog(CatalogPreview(f.imageUrl, f.fruitName.ifBlank { f.farmName }, f.title, f.description, listOf(
        "Farm" to "${f.farmName} · ${f.location}", "Price" to "$PESO${money(f.pricePerPerson)} per picker", "Duration" to f.duration,
        "Season" to f.seasonAvailability, "Fruits" to f.seasonalFruits, "Rating" to "★ ${f.rating} (${f.reviewsCount} reviews)",
    ).filter { it.second.isNotBlank() })) { selectedFruit = f; isFruitModalOpen = true }

    fun openCatalogProduct(p: Product) {
        selectedProductForView = p
    }

    fun openSearchResult(result: CatalogSearchResult) {
        searchQuery = ""
        when (result.type) {
            SearchResultType.FARM -> farmers.firstOrNull { it.id.toLong() == result.id }?.let { selectedFarmerForModal = it }
            SearchResultType.TOUR -> tours.firstOrNull { it.id.toLong() == result.id }?.let { selectedTour = it; isTourModalOpen = true }
            SearchResultType.ORCHARD -> fruitPicking.firstOrNull { it.id.toLong() == result.id }?.let { selectedFruit = it; isFruitModalOpen = true }
            SearchResultType.PRODUCT -> products.firstOrNull { it.id == result.id }?.let { selectedProductForView = it }
        }
    }

    fun farmerIdFor(type: String, id: Int): Int? = when (type) {
        "tour" -> allTours.firstOrNull { it.id == id }?.farmerId
        "orchard" -> allOrchards.firstOrNull { it.id == id }?.farmerId
        else -> null
    }

    fun availableSlotsFor(type: String, id: Int): List<String> {
        if (type == "tour" && tours.firstOrNull { it.id == id }?.bookingEnabled != true) return emptyList()
        if (availablePaxFor(farmerIdFor(type, id)) <= 0) return emptyList()
        return slotsFor(type, id).filter { db.bookingScheduleError(type, id, it) == null }
    }

    /** Reloads every observable collection from SQLite without showing a toast (used by live dashboards). */
    fun refreshFromDb() {
        allProducts = db.products(); bookings = db.bookings(); farmerOrders = db.orders(); userAccounts = db.users()
        trashFarmers = db.deletedFarmers()
        notifications = db.notifications(); reviews = db.reviews(); allTours = db.tours(); allOrchards = db.fruitPicking()
        farmers = db.farmers(); faqs = db.faqs(); settings = db.settings(); images = db.images(); guides = db.guides(); couriers = db.couriers(); timeSlots = db.timeSlots()
        currentUser = currentUser?.let { db.findUser(it.email) }
        if ((currentUser == null || currentUser?.isActive == false) && isLoggedIn) { signOut(); return }
        currentUser?.let { userRole = it.role }
        favoriteIds = currentUser?.let { db.favorites(it.email) } ?: emptySet(); cart = loadCart()
        if (!canAccess(activePage)) activePage = Page.DASHBOARD
    }

    fun reloadFromDb() {
        refreshFromDb()
        showToast("Reloaded data from SQLite database.")
    }

    fun toggleFavorite(id: Long, name: String) {
        val user = currentUser?.email
        if (user == null) { showToast("Log in to save favorites."); activePage = Page.LOGIN; return }
        val exists = id in favoriteIds
        db.setFavorite(user, id, !exists)
        favoriteIds = if (exists) favoriteIds - id else favoriteIds + id
        showToast(if (exists) "Removed \"$name\" from your favorites." else "Saved \"$name\" to your favorites.")
    }

    fun signOut() {
        isLoggedIn = false
        currentUser = null
        userRole = Role.USER
        cart = emptyList(); cartUnselected = emptySet(); favoriteIds = emptySet()
        loginPassword = ""
        loginRole = Role.USER
        checkoutStep = 1; lastPlacedOrder = null; purchaseConfirmationOrder = null
        isNotificationsOpen = false
        selectedNotification = null
        isProfileModalOpen = false; isAddProductModalOpen = false; editingProductId = null; newProductGallery = emptyList()
        pendingConfirm = null; editingTour = null; editingOrchard = null; isAddBookingModalOpen = false
        selectedBookingForView = null; selectedBookingForTicket = null; editingBooking = null; deletingBookingId = null; reviewingBooking = null
        selectedOrderForView = null; selectedReceipt = null; selectedProductForView = null; editingFarmerStory = null
        adminListingFarmId = null
        isAddTourModalOpen = false; isAddOrchardModalOpen = false; isEditStoryOpen = false; catalogPreview = null
        activePage = Page.HOME
        paymentMethod = COD_PAYMENT_METHOD
        tourPaymentMethod = PAY_AT_FARM_PAYMENT_METHOD
        fruitPaymentMethod = PAY_AT_FARM_PAYMENT_METHOD
        newBookingPayment = PAY_AT_FARM_PAYMENT_METHOD
        tourGuests = 1; fruitGuests = 2; newBookingGuests = 1
        showToast("Signed out successfully. Welcome to AgriTourism Philippines!")
    }

    private fun startSession(u: User) {
        currentUser = u
        userRole = u.role
        isLoggedIn = true
        loginError = null
        loginPassword = ""
        favoriteIds = db.favorites(u.email)
        myTours.firstOrNull()?.let { newBookingActivity = it.name }
        cart = loadCart(); cartUnselected = emptySet()
        checkoutStep = 1
        recipientName = u.fullName
        contactPhone = u.phone
        deliveryAddress = u.address
        newBookingTime = myTours.firstOrNull()?.let { availableSlotsFor("tour", it.id).firstOrNull() }.orEmpty()
    }

    /** Verifies the email/password against the users table and detects the role from the stored account. */
    fun login(): Boolean {
        val u = db.authenticate(loginEmail, loginPassword)
        loginError = when {
            u == null -> if (db.findUser(loginEmail)?.isActive == false) {
                "This account is disabled. Contact an administrator."
            } else {
                "Incorrect email or password."
            }
            else -> null
        }
        if (u == null || loginError != null) return false
        startSession(u)
        showToast("Welcome back, ${u.fullName}! Logged in as ${u.role.key.uppercase()}.")
        activePage = Page.DASHBOARD
        return true
    }

    /** Creates the account in the users table; returns an error message or null on success. */
    fun signup(
        fullName: String, email: String, phone: String, password: String, role: Role,
        farmName: String = "", province: String = "",
        firstName: String = "", middleName: String = "", lastName: String = "",
        houseNo: String = "", street: String = "", municipality: String = "", city: String = "",
    ): String? {
        if (role == Role.ADMIN) return "Administrator accounts cannot be self-registered."
        if (!isValidPhone(phone)) return "Enter a valid 11-digit mobile number (09XX XXX XXXX or +63 9XX XXX XXXX)."
        if (role == Role.FARMER && (farmName.isBlank() || province.isBlank())) return "Farmers must enter their farm name and province."
        val structured = listOf(firstName, middleName, lastName, houseNo, street, municipality, city).any { it.isNotBlank() }
        if (structured && listOf(firstName, lastName, houseNo, street, municipality, city).any { it.isBlank() }) {
            return "Complete the first name, last name, house number, street, municipality, and city."
        }
        if (db.findUser(email) != null) return "An account with this email already exists."
        val u = db.tx {
            val created = db.createUser(fullName, email, phone, password, role.key,
                firstName, middleName, lastName, houseNo, street, municipality, city)!!
            if (role == Role.FARMER) db.createFarm(created.id, created.fullName, farmName, province, images.defaultProductFallbackImage, images.farmerProfileBanner)
            db.findUser(email)!!
        }
        farmers = db.farmers()
        userAccounts = db.users()
        startSession(u)
        showToast("Account created successfully! Welcome to AgriTourism, ${u.fullName}.")
        activePage = Page.DASHBOARD
        return null
    }

    /** Admin accounts are read-only apart from deleting Farmer/Tourist accounts. */
    fun setUserAccountActive(id: Long, active: Boolean): String? =
        "Administrator accounts cannot activate or deactivate accounts."

    /** Deletes a tourist account while keeping historical bookings and orders intact. */
    fun deleteTouristAccount(userId: Long): String? {
        if (!isAdmin) return "Only administrators can delete accounts."
        val account = userAccounts.firstOrNull { it.id == userId } ?: return "That account no longer exists."
        if (account.role != Role.USER) return "Only tourist accounts can be deleted."
        if (account.id == currentUser?.id) return "You cannot delete your own account."
        if (!db.deleteTouristAccount(userId)) return "That account no longer exists."
        userAccounts = db.users()
        notifications = db.notifications()
        showToast("${account.fullName}'s tourist account was deleted.")
        return null
    }

    /** Permanently deletes a farmer account together with every listing, file and dependent record it owns. */
    fun deleteFarmerAccount(userId: Long): String? {
        if (!isAdmin) return "Only administrators can delete accounts."
        val account = userAccounts.firstOrNull { it.id == userId } ?: return "That account no longer exists."
        if (account.role != Role.FARMER) return "Only farmer accounts can be deleted."
        if (account.id == currentUser?.id) return "You cannot delete your own account."
        val result = try {
            db.deleteFarmerAccountCascade(userId)
        } catch (e: Exception) {
            return "Could not delete farmer account: ${e.message ?: "unknown error"}"
        }
        if (!result.accountDeleted) return "That account no longer exists."
        refreshTrashAndCatalog()
        if (result.failedUploads.isEmpty()) {
            showToast("${account.fullName}'s account and all associated farm content were permanently deleted.")
        } else {
            showToast("Account deleted, but ${result.failedUploads.size} uploaded file(s) could not be removed.")
        }
        return null
    }

    private fun refreshTrashAndCatalog() {
        trashFarmers = db.deletedFarmers()
        farmers = db.farmers(); userAccounts = db.users(); allTours = db.tours(); allOrchards = db.fruitPicking()
        allProducts = db.products(); bookings = db.bookings(); farmerOrders = db.orders()
        notifications = db.notifications(); guides = db.guides()
        timeSlots = db.timeSlots(); reviews = db.reviews(); cart = loadCart()
    }

    /** Restores every original row and id from the Trash Bin. */
    fun restoreFarmerAccount(userId: Long): String? {
        if (!isAdmin) return "Only administrators can restore accounts."
        val account = trashFarmers.firstOrNull { it.user.id == userId } ?: return "That account is not in the Trash Bin."
        if (!db.restoreFarmerAccount(userId)) return "That account could not be restored."
        refreshTrashAndCatalog()
        showToast("${account.user.fullName}'s account and farm data were restored.")
        return null
    }

    /** Permanently removes an account that is already in the Trash Bin. */
    fun permanentlyDeleteFarmerAccount(userId: Long): String? {
        if (!isAdmin) return "Only administrators can empty the Trash Bin."
        val account = trashFarmers.firstOrNull { it.user.id == userId } ?: return "That account is not in the Trash Bin."
        val result = try {
            db.deleteFarmerAccountCascade(userId)
        } catch (e: Exception) {
            return "Could not permanently delete farmer account: ${e.message ?: "unknown error"}"
        }
        if (!result.accountDeleted) return "That account could not be permanently deleted."
        refreshTrashAndCatalog()
        if (result.failedUploads.isEmpty()) {
            showToast("${account.user.fullName}'s trashed account and farm content were permanently deleted.")
        } else {
            showToast("Account deleted, but ${result.failedUploads.size} uploaded file(s) could not be removed.")
        }
        return null
    }

    /** Saves the signed-in account's own profile fields; `email` and `address` are optional for older callers. */
    fun updateProfile(
        fullName: String, phone: String, address: String? = null, email: String? = null,
        firstName: String = "", middleName: String = "", lastName: String = "",
        houseNo: String = "", street: String = "", municipality: String = "", city: String = "",
    ): String? {
        val u = currentUser ?: return "You are not logged in."
        val trimmedName = fullName.trim()
        if (trimmedName.isBlank()) return "Name cannot be empty."
        if (!isValidPhone(phone)) return "Enter a valid 11-digit mobile number (09XX XXX XXXX or +63 9XX XXX XXXX)."
        val structured = listOf(firstName, middleName, lastName, houseNo, street, municipality, city).any { it.isNotBlank() }
        if (structured && listOf(firstName, lastName, houseNo, street, municipality, city).any { it.isBlank() }) {
            return "Complete the first name, last name, house number, street, municipality, and city."
        }
        val composedName = if (structured) {
            listOf(firstName.trim(), middleName.trim(), lastName.trim()).filter { it.isNotBlank() }.joinToString(" ")
        } else trimmedName
        val nextAddress = if (structured) {
            listOf(houseNo.trim(), street.trim(), municipality.trim(), city.trim()).filter { it.isNotBlank() }.joinToString(", ")
        } else address?.trim() ?: u.address
        val nextEmail = email?.trim()?.lowercase().orEmpty().ifBlank { u.email }
        val emailChanged = !nextEmail.equals(u.email, ignoreCase = true)
        if (emailChanged && db.findUser(nextEmail) != null) return "An account with this email already exists."
        if (emailChanged && !db.updateUserEmail(u.id, nextEmail)) {
            return "Enter a valid email address that is not already registered."
        }
        db.updateUserProfile(u.id, composedName, phone.trim(), firstName, middleName, lastName, houseNo, street, municipality, city)
        if (address != null || structured) db.updateUserAddress(u.id, nextAddress)
        currentUser = db.users().firstOrNull { it.id == u.id }
        userAccounts = db.users()
        if (recipientName == u.fullName) recipientName = composedName
        if (contactPhone == u.phone) contactPhone = phone.trim()
        if (deliveryAddress == u.address) deliveryAddress = nextAddress
        if (emailChanged) {
            loginEmail = nextEmail
            favoriteIds = db.favorites(nextEmail)
            cart = loadCart()
        }
        showToast("Profile settings updated!")
        return null
    }

    // ---------------------------------------------------------------- cart
    fun addToCart(product: Product) {
        if (!isLoggedIn) {
            showToast("Log in or Sign Up is required before adding products to your cart.")
            activePage = Page.LOGIN
            return
        }
        if (!isTourist) { showToast("Only tourist accounts can buy produce."); return }
        val maxStock = product.stock
        val currentQty = cart.firstOrNull { it.product.id == product.id }?.quantity ?: 0
        if (maxStock <= 0) { showToast("Sorry, \"${product.name}\" is currently sold out!"); return }
        if (currentQty >= maxStock) {
            showToast("Cannot add more: Farmer's limit reached ($maxStock ${product.unit} max in stock)."); return
        }
        val next = if (currentQty > 0) cart.map { if (it.product.id == product.id) it.copy(quantity = it.quantity + 1) else it }
        else cart + CartItem(product, 1)
        persistCart(next)
        showToast("Added ${product.name} to cart! (Stock remaining: ${maxStock - (currentQty + 1)})")
    }

    fun updateCartQty(productId: Long, delta: Int) {
        val target = cart.firstOrNull { it.product.id == productId } ?: return
        val live = products.firstOrNull { it.id == productId } ?: target.product
        val newQty = target.quantity + delta
        if (delta > 0 && newQty > live.stock) {
            showToast("Cannot exceed farmer's stock limit! Only ${live.stock} ${live.unit} available.")
            return
        }
        persistCart(cart.mapNotNull { if (it.product.id == productId) (if (newQty > 0) it.copy(quantity = newQty) else null) else it })
    }

    /** Returns an error if the delivery details are incomplete. */
    fun deliveryDetailsError(): String? = when {
        recipientName.isBlank() -> "Please enter the recipient's name."
        !isValidPhone(contactPhone) -> "Enter a valid 11-digit mobile number."
        deliveryAddress.isBlank() -> "Please enter a delivery address."
        else -> null
    }

    fun placeOrder() {
        if (!orderSubmissionInProgress.compareAndSet(false, true)) return
        isPlacingOrder = true
        try {
            if (!isTourist) { showToast("Only tourist accounts can place orders."); return }
            if (paymentMethod !in CHECKOUT_PAYMENT_METHODS) {
                paymentMethod = CHECKOUT_PAYMENT_METHODS.first()
                showToast("That payment method is no longer available. Please use $COD_PAYMENT_METHOD.")
                return
            }
            if (cart.isEmpty()) { showToast("Your cart is empty."); checkoutStep = 1; return }
            val buying = checkoutItems
            if (buying.isEmpty()) { showToast("Tick at least one item in your cart to check out."); checkoutStep = 1; return }
            deliveryDetailsError()?.let { showToast(it); checkoutStep = 2; return }
            val byId = products.associateBy { it.id }
            buying.firstOrNull { (byId[it.product.id]?.stock ?: 0) < it.quantity }?.let { c ->
                val left = byId[c.product.id]?.stock ?: 0
                showToast("Only $left ${c.product.unit} of \"${c.product.name}\" left in stock. Please update your cart.")
                checkoutStep = 1; return
            }
            val orderAmt = cartTotal
            val existingNumbers = farmerOrders.map { it.orderNumber }.toSet()
            var refCode: String
            do { refCode = "ORD-${LocalDate.now().year}-${Random.nextInt(1000, 10000)}" } while (refCode in existingNumbers)
            val stamp = nowStamp()
            val newOrderId = "ord-${System.currentTimeMillis()}"
            val order = FarmerOrder(
                id = newOrderId,
                orderNumber = refCode,
                customerEmail = userEmail,
                customerName = recipientName.trim(),
                customerPhone = contactPhone.trim(),
                customerAddress = deliveryAddress.trim(),
                items = buying.map {
                    val product = byId[it.product.id]!!
                    val seller = product.farmerId?.let { farmId -> farmers.firstOrNull { farm -> farm.id == farmId }?.farmName }
                        ?: product.farmOrigin
                    OrderItem(product.name, it.quantity, product.discountedPrice, product.id, product.imageUrl, seller)
                },
                totalAmount = orderAmt,
                paymentMethod = paymentMethod,
                paymentStatus = if (paymentMethod == COD_PAYMENT_METHOD) "COD" else "Paid via $paymentMethod",
                fulfillmentStatus = "Pending",
                orderDate = stamp,
                uploadedReceiptUrl = "", // no payment proof uploaded through the desktop checkout
                specialDeliveryNotes = deliveryNotes.trim().ifBlank { null },
            )
            val remaining = cart - buying.toSet()
            try {
                db.tx {
                    db.saveOrderWithStock(order)
                    persistCart(remaining)
                }
            } catch (e: Database.StockUnavailableException) {
                showToast(e.message ?: "Product stock changed during checkout. Please review your cart.")
                return
            }
            purchaseConfirmationOrder = order
            // Remember the delivery address on the account for next time.
            currentUser?.let { u -> if (u.address != order.customerAddress) { db.updateUserAddress(u.id, order.customerAddress); currentUser = u.copy(address = order.customerAddress) } }
            farmerOrders = db.orders()
            allProducts = db.products()
            lastPlacedOrder = order
            lastOrderTotal = orderAmt
            lastOrderPaymentMethod = paymentMethod
            lastOrderReference = refCode
            checkoutStep = 1
            cartUnselected = emptySet()
            val productNames = order.items.joinToString { it.productName }
            addNotification(AppNotification(System.currentTimeMillis(), "Purchase Successful!",
                "Purchase Successful! You've purchased $productNames. Your order is now Pending.",
                "Order #$refCode · ${order.items.sumOf { it.qty }} item(s) · $PESO${money(orderAmt)} · Cash on Delivery.",
                nowStamp(), false, "order", "tourist", recipientEmail = userEmail, orderId = order.id,
                eventKey = "order:${order.id}:purchased"))
            notifyFarms(farmsOf(order), AppNotification(System.currentTimeMillis(), "New Produce Order: ${order.customerName}",
                "Order #$refCode · $PESO${money(orderAmt)} · ${order.items.sumOf { it.qty }} item(s) to pack.",
                "${order.customerName} placed order #$refCode (${order.items.joinToString { "${it.qty}× ${it.productName}" }}). Payment: ${order.paymentStatus}. Deliver to ${order.customerAddress}.",
                nowStamp(), false, "farmer_order", "farmer", orderId = order.id,
                eventKey = "order:${order.id}:placed"))
            showToast("Order placed successfully! Amount: $PESO${money(orderAmt)}")
        } finally {
            isPlacingOrder = false
            orderSubmissionInProgress.set(false)
        }
    }

    // ---------------------------------------------------------------- bookings
    /** Saves a booking unless it would double-book the customer; returns false (with a message) if rejected. */
    private fun trySaveBooking(b: Booking, validateSchedule: Boolean = true): Boolean = try {
        db.saveBooking(b, validateSchedule = validateSchedule); true
    } catch (e: Database.InvalidBookingPaymentMethodException) {
        showToast(e.message ?: "Farm bookings must use Pay at the Farm.")
        false
    } catch (e: Database.InvalidBookingScheduleException) {
        showToast(e.message ?: OUTSIDE_BOOKING_SCHEDULE_MESSAGE)
        false
    } catch (e: Database.InvalidBookingDateException) {
        showToast(e.message ?: "Choose a booking date from today through December 31 of next year.")
        false
    } catch (e: Database.InvalidTourPriceException) {
        showToast(e.message ?: "This tour has an invalid price.")
        false
    } catch (e: Database.DoubleBookingException) {
        val x = e.existing
        showToast("Double booking: ${b.customerName} already has #${x.bookingCode} (${x.activity}) on ${x.tourDate} at ${slotTime(x.tourTime)}.")
        false
    } catch (e: Database.CapacityExceededException) {
        showToast(e.message ?: "This farm is full for that date.")
        false
    } catch (e: IllegalArgumentException) {
        showToast(e.message ?: "Please check the number of persons.")
        false
    } catch (e: java.sql.SQLException) {
        if (e.message?.contains("UNIQUE", true) == true) { showToast("Double booking: that time slot is already reserved for this customer."); false } else throw e
    }

    private fun updateBooking(id: Long, f: (Booking) -> Booking) {
        val updated = booking(id)?.let(f) ?: return
        if (trySaveBooking(updated, validateSchedule = false)) bookings = bookings.map { if (it.id == id) updated else it }
    }

    private fun updateNotifications(list: List<AppNotification>) {
        notifications = list
    }

    /** Sends a farmer alert to the account of each farm involved (admins always see farmer alerts). */
    private fun notifyFarms(farms: Set<Int?>, n: AppNotification) {
        val emails = farms.filterNotNull().mapNotNull { db.farmerAccount(it)?.email }.distinct()
        if (emails.isEmpty()) addNotification(n) else emails.forEach { addNotification(n.copy(recipientEmail = it)) }
    }

    private fun addNotification(n0: AppNotification) {
        if (n0.title in setOf("Booking", "Tour Booking Confirmed")) return
        // Keep ids unique even when several alerts are created in the same millisecond.
        val n = n0.copy(id = max(n0.id, (notifications.maxOfOrNull { it.id } ?: 0) + 1))
        if (db.saveNotification(n)) notifications = listOf(n) + notifications
    }

    fun bookingAction(id: Long, newStatus: String) {
        if (denied(booking(id)?.let(::canActOnBooking) == true, "accept or decline this booking")) return
        updateBooking(id) { it.copy(status = newStatus, attendanceStatus = if (newStatus == "Cancelled") "Declined by Farm" else it.attendanceStatus) }
        booking(id)?.let { b ->
            val ok = newStatus == "Confirmed"
            addNotification(AppNotification(System.currentTimeMillis(), if (ok) "Booking Accepted: ${b.farmName}" else "Booking Declined: ${b.farmName}",
                "Your reservation #${b.bookingCode} for ${b.tourDate} was ${if (ok) "accepted" else "declined"} by the farm host.",
                "Reservation #${b.bookingCode} (${b.activity}, ${b.tourDate} ${b.tourTime}, ${b.participants} guests) was ${if (ok) "accepted. See you at the farm!" else "declined by the farm host."}",
                nowStamp(), false, "booking_update", "tourist", b.id, recipientEmail = b.customerEmail))
        }
        showToast("Booking ${if (newStatus == "Confirmed") "ACCEPTED" else "DECLINED"} successfully!")
    }

    fun cancelBooking(id: Long) {
        val allowed = booking(id)?.let { b -> ownsBooking(b) || canActOnBooking(b) } == true
        if (denied(allowed, "cancel this booking")) return
        updateBooking(id) { it.copy(status = "Cancelled", attendanceStatus = "Cancelled by Tourist") }
        showToast("Booking appointment has been CANCELLED.")
    }

    fun deleteBooking(id: Long) {
        if (denied(booking(id)?.let(::ownsBooking) == true, "delete this booking")) return
        db.tx {
            db.deleteBooking(id)
            db.deleteNotificationsForBooking(id)
        }
        bookings = bookings.filterNot { it.id == id }
        notifications = notifications.filterNot { it.bookingId == id }
        deletingBookingId = null
        showToast("Booking deleted from your records.")
    }

    fun saveEditedBooking() {
        if (denied(editingBooking?.let { e -> booking(e.id)?.let { b -> ownsBooking(b) || canActOnBooking(b) } } == true, "edit this booking")) return
        val e0 = editingBooking ?: return
        if (e0.participants < 1) { showToast("A booking needs at least 1 guest."); return }
        if (parseTourDate(e0.tourDate) == null) { showToast("Please enter a valid date (YYYY-MM-DD)."); return }
        val original = bookings.firstOrNull { it.id == e0.id } ?: return
        // Keep the total consistent with the per-person rate when the group size changes.
        val perPerson = original.totalAmount.toDouble() / max(1, original.participants)
        val e = e0.copy(totalAmount = (perPerson * e0.participants).roundToInt())
        if (!trySaveBooking(e)) return
        bookings = bookings.map { if (it.id == e.id) e else it }
        editingBooking = null
        showToast("Booking #${e.bookingCode} updated successfully!")
    }

    fun attendanceConfirmation(bookingId: Long, willAttend: Boolean) {
        if (denied(booking(bookingId)?.let(::ownsBooking) == true, "answer attendance for this booking")) return
        val b = bookings.firstOrNull { it.id == bookingId } ?: return
        val now = System.currentTimeMillis()
        if (willAttend) {
            updateBooking(bookingId) { it.copy(attendanceStatus = "Confirmed Attending") }
            updateNotifications(notifications.map {
                if (it.bookingId == bookingId) it.copy(read = true, attendanceStatus = "Confirmed Attending",
                    title = "Confirmed: Tour Attendance Locked In",
                    desc = "You confirmed you are proceeding tomorrow at ${b.tourTime}!").also(db::saveNotification) else it
            })
            notifyFarms(setOf(farmOf(b)), AppNotification(now, "Visitor Confirmed Arrival: ${b.customerName}",
                "${b.customerName} confirmed arrival for ${b.activity} on ${b.tourDate} (${b.tourTime})!",
                "Tourist ${b.customerName} responded YES to 1-day confirmation. Booking #${b.bookingCode}. Group size: ${b.participants} guests. Contact: ${b.customerPhone ?: "not provided"}. Fresh farm refreshments and local guide are confirmed.",
                nowStamp(), false, "farmer_attendance_alert", "farmer"))
            showToast("Your attendance for ${b.farmName} tomorrow is confirmed.")
        } else {
            updateBooking(bookingId) { it.copy(attendanceStatus = "Cancelled by Tourist", status = "Cancelled") }
            updateNotifications(notifications.map {
                if (it.bookingId == bookingId) it.copy(read = true, attendanceStatus = "Cancelled by Tourist",
                    title = "Tour Visit Cancelled",
                    desc = "You cancelled your tour appointment for ${b.farmName}.").also(db::saveNotification) else it
            })
            notifyFarms(setOf(farmOf(b)), AppNotification(now, "Tourist Cancelled Visit: ${b.customerName}",
                "${b.customerName} responded NO for ${b.activity} on ${b.tourDate}.",
                "Tourist ${b.customerName} responded NO to 1-day confirmation. Booking #${b.bookingCode} is now marked as Cancelled.",
                nowStamp(), false, "farmer_attendance_alert", "farmer"))
            showToast("Tour appointment cancelled. We hope to see you next time.")
        }
        selectedBookingForView = selectedBookingForView?.let { v -> bookings.firstOrNull { it.id == v.id } }
    }

    fun checkInBooking(id: Long) {
        if (denied(booking(id)?.let(::canActOnBooking) == true, "check in guests for this booking")) return
        if (bookings.firstOrNull { it.id == id }?.status == "Cancelled") { showToast("Cancelled bookings cannot be checked in."); return }
        val nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"))
        updateBooking(id) { it.copy(attendanceStatus = "Arrived & Checked In", status = "Confirmed", checkedInAt = nowTime) }
        selectedBookingForView = selectedBookingForView?.let { v -> bookings.firstOrNull { it.id == v.id } }
        showToast("Tourist guest checked in at farm gate ($nowTime)!")
    }

    fun assignGuide(id: Long, guide: String) {
        if (denied(booking(id)?.let(::canActOnBooking) == true, "assign a guide to this booking")) return
        updateBooking(id) { it.copy(assignedGuide = guide) }
        selectedBookingForView = selectedBookingForView?.let { v -> bookings.firstOrNull { it.id == v.id } }
        showToast("Tour guide \"$guide\" assigned to reservation.")
    }

    private fun newBookingCode(): String {
        val used = bookings.map { it.bookingCode }.toSet()
        var code: String
        do { code = "BK-${LocalDate.now().year}-${Random.nextInt(1000, 10000)}" } while (code in used)
        return code
    }

    private fun newId(): Long = max(System.currentTimeMillis(), (bookings.maxOfOrNull { it.id } ?: 0) + 1)

    /** Validates a reservation date (must parse and not be in the past). */
    private fun dateError(d: String): String? {
        val date = parseTourDate(d) ?: return "Please enter a valid date (YYYY-MM-DD)."
        val window = BookingDateWindow.current()
        return when {
            date.isBefore(window.first) -> "Please choose a date from today onwards."
            date.isAfter(window.last) -> "Please choose a date no later than December 31 of next year."
            else -> null
        }
    }

    fun bookSelectedTour() {
        if (!isLoggedIn) { showToast("Please log in using the Log In button at the top header to confirm your booking."); activePage = Page.LOGIN; return }
        if (!isTourist) { showToast("Only tourist accounts can book tours."); return }
        dateError(tourDate)?.let { showToast(it); return }
        if (tourGuests < 1) { showToast("Please add at least 1 person."); return }
        if (tourPaymentMethod !in TOUR_PAYMENT_METHODS) { showToast("Please choose an available tour payment method."); return }
        val t = selectedTour
        if (!t.bookingEnabled) { showToast("Bookings for this tour are currently unavailable."); return }
        val left = availablePaxFor(t)
        if (tourGuests > left) { showToast("Only $left slot(s) are available at ${t.name}. Reduce the number of persons."); return }
        val slots = availableSlotsFor("tour", t.id)
        if (tourTime.isBlank()) tourTime = slots.firstOrNull().orEmpty()
        db.bookingScheduleError("tour", t.id, tourTime)?.let { showToast(it); return }
        val nb = Booking(
            id = newId(), bookingCode = newBookingCode(), farmId = t.id, farmName = t.name,
            customerName = displayName, customerPhone = userPhone, customerEmail = userEmail,
            activity = t.name, tourDate = tourDate, tourTime = tourTime, participants = tourGuests,
            totalAmount = t.bookingTotal(tourGuests), paymentMethod = tourPaymentMethod, status = "Confirmed",
            attendanceStatus = "Pending Confirmation",
            assignedGuide = defaultGuide(t.farmerId, t.farmerName),
        )
        if (!trySaveBooking(nb)) return
        bookings = listOf(nb) + bookings
        isTourModalOpen = false
        selectedBookingForTicket = nb
        showToast("Tour Confirmed! Your official Online Ticket Pass is ready below.")
        activePage = Page.MY_BOOKINGS
    }

    fun bookSelectedFruit() {
        if (!isLoggedIn) { showToast("Please log in using the Log In button at the top header to confirm your booking."); activePage = Page.LOGIN; return }
        if (!isTourist) { showToast("Only tourist accounts can book tours."); return }
        dateError(fruitDate)?.let { showToast(it); return }
        if (fruitGuests < 1) { showToast("Please add at least 1 guest."); return }
        if (fruitPaymentMethod !in TOUR_PAYMENT_METHODS) { showToast("Please choose an available fruit-picking payment method."); return }
        val f = selectedFruit
        val left = availablePaxFor(f)
        if (fruitGuests > left) { showToast("Only $left slot(s) are available at ${f.title}. Reduce the number of persons."); return }
        val slots = availableSlotsFor("orchard", f.id)
        if (fruitTime.isBlank()) fruitTime = slots.firstOrNull().orEmpty()
        db.bookingScheduleError("orchard", f.id, fruitTime)?.let { showToast(it); return }
        val nb = Booking(
            id = newId(), bookingCode = newBookingCode(), farmId = f.id, farmName = f.farmName,
            customerName = displayName, customerPhone = userPhone, customerEmail = userEmail,
            activity = f.title, tourDate = fruitDate, tourTime = fruitTime,
            participants = fruitGuests, totalAmount = f.pricePerPerson * fruitGuests, paymentMethod = fruitPaymentMethod,
            attendanceStatus = "Pending Confirmation", status = "Confirmed", assignedGuide = defaultGuide(f.farmerId, farmers.firstOrNull { it.id == f.farmerId }?.name.orEmpty()),
            activityType = "orchard",
        )
        if (!trySaveBooking(nb)) return
        bookings = listOf(nb) + bookings
        isFruitModalOpen = false
        selectedBookingForTicket = nb
        showToast("Fruit picking booked! Your Online Ticket Pass is ready below.")
        activePage = Page.MY_BOOKINGS
    }

    fun saveFarmerBooking() {
        if (denied(isFarmer, "create walk-in bookings")) return
        if (myTours.isEmpty()) { showToast("Your farm has no tours to book yet."); return }
        if (newBookingCustomer.isBlank()) { showToast("Please enter the guest's name."); return }
        if (!isValidPhone(newBookingPhone)) { showToast("Enter a valid 11-digit mobile number for the guest."); return }
        dateError(newBookingDate)?.let { showToast(it); return }
        if (newBookingGuests < 1) { showToast("Please add at least 1 person."); return }
        if (newBookingPayment !in WALK_IN_PAYMENT_METHODS) { showToast("Please choose an available walk-in payment method."); return }
        val t = myTours.firstOrNull { it.name == newBookingActivity } ?: myTours.first()
        val left = availablePaxFor(t)
        if (newBookingGuests > left) { showToast("Only $left slot(s) are available at ${t.name}. Reduce the number of persons."); return }
        val slots = availableSlotsFor("tour", t.id)
        if (newBookingTime.isBlank()) newBookingTime = slots.firstOrNull().orEmpty()
        db.bookingScheduleError("tour", t.id, newBookingTime)?.let { showToast(it); return }
        val total = t.bookingTotal(newBookingGuests)
        val nb = Booking(
            id = newId(), bookingCode = newBookingCode(), farmId = t.id, farmName = t.name,
            customerName = newBookingCustomer, customerPhone = phoneDigits(newBookingPhone),
            customerEmail = null, activity = t.name, tourDate = newBookingDate,
            tourTime = newBookingTime, participants = newBookingGuests, totalAmount = total,
            paymentMethod = newBookingPayment, attendanceStatus = "Pending Confirmation", status = "Confirmed",
            assignedGuide = defaultGuide(t.farmerId, t.farmerName),
        )
        if (!trySaveBooking(nb)) return
        bookings = listOf(nb) + bookings
        isAddBookingModalOpen = false
        showToast("Tour booking created for $newBookingCustomer ($PESO$total)!")
        newBookingCustomer = ""
        newBookingPhone = ""
        newBookingGuests = 1
    }

    // ---------------------------------------------------------------- reviews
    fun reviewForBooking(id: Long): CustomerReview? = reviews.firstOrNull { it.bookingId == id }

    /** Tourists can review their own visit once it has happened (checked in, or the date has passed), once per booking. */
    fun canReview(b: Booking): Boolean = ownsBooking(b) && b.status != "Cancelled" && reviewForBooking(b.id) == null &&
        (b.attendanceStatus == "Arrived & Checked In" || parseTourDate(b.tourDate)?.let { !it.isAfter(LocalDate.now()) } == true)

    fun reviewsFor(type: String, id: Int): List<CustomerReview> =
        reviews.filter { it.targetType == type && it.targetId == id }.sortedByDescending { it.id }

    /** Saves a tourist's review for one of their bookings; returns an error message or null on success. */
    fun submitReview(bookingId: Long, rating: Int, comment: String): String? {
        val b = booking(bookingId) ?: return "Booking not found."
        if (!isTourist || !ownsBooking(b)) return "You can only review your own bookings."
        if (reviewForBooking(bookingId) != null) return "You have already reviewed this booking."
        if (!canReview(b)) return "You can review this booking after your visit."
        if (rating !in 1..5) return "Please choose a rating from 1 to 5 stars."
        if (comment.trim().length < 10) return "Please write at least 10 characters about your visit."
        val review = CustomerReview(
            db.nextReviewId(), displayName, "Eco-Tourist", rating,
            LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)), b.activity, comment.trim(),
            userEmail, b.id, b.activityType, b.farmId,
        )
        try { db.addReview(review) } catch (e: Exception) { return e.message ?: "Could not save the review." }
        reviews = db.reviews(); allTours = db.tours(); allOrchards = db.fruitPicking(); farmers = db.farmers()
        selectedTour = tours.firstOrNull { it.id == selectedTour.id } ?: selectedTour
        selectedFruit = fruitPicking.firstOrNull { it.id == selectedFruit.id } ?: selectedFruit
        notifyFarms(setOf(farmOf(b)), AppNotification(System.currentTimeMillis(), "New $rating★ Review: ${b.activity}",
            "${review.author} reviewed booking #${b.bookingCode}.", "\"${review.comment}\" — ${review.author}",
            nowStamp(), false, "farmer_review", "farmer"))
        reviewingBooking = null
        showToast("Thank you! Your $rating-star review was posted.")
        return null
    }

    fun canEditReview(r: CustomerReview) = isTourist && r.userEmail?.equals(userEmail, true) == true
    fun canDeleteReview(r: CustomerReview) = canEditReview(r)

    /** Changes the rating/comment of the tourist's own review; returns an error or null. */
    fun updateReview(reviewId: Int, rating: Int, comment: String): String? {
        val r = reviews.firstOrNull { it.id == reviewId } ?: return "Review not found."
        if (!canEditReview(r)) return "You can only edit your own reviews."
        if (rating !in 1..5) return "Please choose a rating from 1 to 5 stars."
        if (comment.trim().length < 10) return "Please write at least 10 characters about your visit."
        db.updateReview(r, rating, comment.trim())
        reloadRatings()
        reviewingBooking = null
        showToast("Your review was updated.")
        return null
    }

    fun deleteReview(reviewId: Int) {
        val r = reviews.firstOrNull { it.id == reviewId } ?: return
        if (denied(canDeleteReview(r), "delete this review")) return
        db.deleteReview(r)
        reloadRatings()
        reviewingBooking = null
        showToast("Review deleted.")
    }

    private fun reloadRatings() {
        reviews = db.reviews(); allTours = db.tours(); allOrchards = db.fruitPicking(); farmers = db.farmers()
        selectedTour = tours.firstOrNull { it.id == selectedTour.id } ?: selectedTour
        selectedFruit = fruitPicking.firstOrNull { it.id == selectedFruit.id } ?: selectedFruit
    }

    // ---------------------------------------------------------------- farm setup (guides & time slots)
    private fun canManageFarm(farmId: Int?) = isFarmer && farmId != null && farmId == myFarmId

    private fun activityFarm(type: String, id: Int): Int? =
        if (type == "orchard") fruitPicking.firstOrNull { it.id == id }?.farmerId else tours.firstOrNull { it.id == id }?.farmerId

    /** Adds a guide to a farm; returns an error message or null on success. */
    fun addGuide(farmId: Int?, name: String): String? {
        if (!canManageFarm(farmId)) return "You can only manage guides for your own farm."
        val n = name.trim()
        if (n.length < 2) return "Enter the guide's name."
        if (guidesFor(farmId).any { it.equals(n, true) }) return "That guide is already listed."
        db.addGuide(farmId, n); guides = db.guides()
        showToast("Guide \"$n\" added.")
        return null
    }

    fun removeGuide(g: Guide) {
        if (denied(canManageFarm(g.farmerId), "remove this guide")) return
        db.deleteGuide(g.id); guides = db.guides()
        showToast("Guide \"${g.name}\" removed.")
    }

    /** Adds a time slot such as "08:00 AM - Morning Walk" to a tour or orchard; returns an error or null. */
    fun addTimeSlot(type: String, activityId: Int, label: String): String? {
        if (!canManageFarm(activityFarm(type, activityId))) return "You can only manage time slots for your own farm."
        val normalized = normalizeSlot(label).getOrElse { return it.message }
        if (slotsFor(type, activityId).any { slotTime(it) == slotTime(normalized) }) return "There is already a slot at ${slotTime(normalized)}."
        db.addTimeSlot(type, activityId, normalized); timeSlots = db.timeSlots()
        showToast("Time slot \"$normalized\" added.")
        return null
    }

    fun removeTimeSlot(t: TimeSlot) {
        if (denied(canManageFarm(activityFarm(t.activityType, t.activityId)), "remove this time slot")) return
        db.deleteTimeSlot(t.id); timeSlots = db.timeSlots()
        showToast("Time slot \"${t.label}\" removed. Existing bookings keep their time.")
    }

    /** Operating-hours configuration for a tour/orchard: opening, closing, and slot length in minutes. */
    data class OperatingScheduleConfig(val open: String, val close: String, val slotMinutes: Int)

    private fun scheduleKey(type: String, activityId: Int) = "slot_config.$type.$activityId"

    /** The saved auto-schedule for an activity, or null if it has never been configured. */
    fun operatingSchedule(type: String, activityId: Int): OperatingScheduleConfig? =
        settings[scheduleKey(type, activityId)]?.split("|")?.takeIf { it.size == 3 }?.let { parts ->
            val minutes = parts[2].toIntOrNull() ?: return null
            OperatingScheduleConfig(parts[0], parts[1], minutes)
        }

    /**
     * Generates "HH:MM AM - HH:MM AM" slot labels stepping [slotMinutes] from [open] up to (never past)
     * [close]. The final slot is shortened so it ends exactly at closing time.
     */
    fun generateSlotLabels(open: LocalTime, close: LocalTime, slotMinutes: Int): List<String> {
        if (slotMinutes <= 0 || !open.isBefore(close)) return emptyList()
        val labels = mutableListOf<String>()
        var start = open
        while (start.isBefore(close)) {
            val end = minOf(start.plusMinutes(slotMinutes.toLong()), close)
            labels.add("${formatClockTime(start)} - ${formatClockTime(end)}")
            start = end
        }
        return labels
    }

    /**
     * Validates and saves a farm tour's operating hours + slot length, then regenerates its bookable
     * slots from those hours. Slots with active bookings are preserved even if they fall outside the
     * new hours, so confirmed reservations are never modified or deleted. Returns an error or null.
     */
    fun saveOperatingSchedule(type: String, activityId: Int, openText: String, closeText: String, slotMinutes: Int): String? {
        if (type !in setOf("tour", "orchard")) return "Unsupported activity type."
        if (!canManageFarm(activityFarm(type, activityId))) return "You can only manage your own farm's schedule."
        val open = parseClockTime(openText) ?: return "Enter a valid opening time (e.g. 08:00 AM)."
        val close = parseClockTime(closeText) ?: return "Enter a valid closing time (e.g. 05:00 PM)."
        if (!open.isBefore(close)) return "Closing time must be later than opening time."
        if (slotMinutes !in 5..240) return "Slot duration must be between 5 and 240 minutes."
        val labels = generateSlotLabels(open, close, slotMinutes)
        if (labels.isEmpty()) return "The operating hours are too short for the chosen slot duration."
        val openLabel = formatClockTime(open)
        val closeLabel = formatClockTime(close)
        val bookedStarts = bookings.filter {
            it.activityType == type && it.farmId == activityId && it.status != "Cancelled"
        }.map { slotTime(it.tourTime) }.toSet()
        db.tx {
            db.setSetting(scheduleKey(type, activityId), "$openLabel|$closeLabel|$slotMinutes")
            if (type == "tour") allTours.firstOrNull { it.id == activityId }?.let { t ->
                val updated = t.copy(operatingHours = "$openLabel - $closeLabel")
                db.updateTour(updated)
                allTours = allTours.map { if (it.id == activityId) updated else it }
            }
            db.replaceTimeSlots(type, activityId, labels, bookedStarts)
        }
        settings = db.settings(); timeSlots = db.timeSlots()
        showToast("Schedule saved: $openLabel–$closeLabel in $slotMinutes-min slots (${labels.size} generated).")
        return null
    }

    // ---------------------------------------------------------------- platform settings (admin)
    /** Adds a delivery courier; returns an error message or null on success. */
    fun addCourier(c: Courier): String? {
        if (!isAdmin) return "Only administrators can manage couriers."
        if (isAdmin) return "Administrator accounts have read-only access to platform settings."
        if (c.company.isBlank() || c.riderName.isBlank()) return "Enter the courier company and rider name."
        if (c.riderPhone.count(Char::isDigit) < 7) return "Enter a valid rider phone number."
        db.addCourier(c); couriers = db.couriers()
        showToast("Courier ${c.riderName.trim()} (${c.company.trim()}) added.")
        return null
    }

    fun removeCourier(c: Courier) {
        if (denied(false, "remove couriers")) return
        db.deleteCourier(c.id); couriers = db.couriers()
        showToast("Courier ${c.riderName} removed. Past orders keep their courier details.")
    }

    /** Saves a platform setting; returns an error message or null on success. */
    fun saveSetting(key: String, value: String): String? {
        if (!isAdmin) return "Only administrators can change settings."
        if (isAdmin) return "Administrator accounts have read-only access to platform settings."
        val v = value.trim()
        if (key == "delivery_fee" && (v.toDoubleOrNull() ?: -1.0) < 0) return "Delivery fee must be a number of 0 or more."
        if (key == "product_categories" && v.split(",").none { it.isNotBlank() }) return "Enter at least one category."
        if (v.isBlank()) return "This setting cannot be empty."
        db.setSetting(key, v); settings = db.settings(); images = db.images()
        return null
    }

    // ---------------------------------------------------------------- products
    fun saveProduct() {
        if (denied(isFarmer && myFarmId != null, "add products")) return
        if (newProductName.isBlank()) { showToast("Please enter a product name."); return }
        val price = newProductPrice.toDoubleOrNull()
        if (price == null || price <= 0) { showToast("Please enter a valid price."); return }
        val stock = newProductStock.toIntOrNull()
        if (stock == null || stock < 0) { showToast("Please enter a valid stock limit."); return }
        val discount = newProductDiscount.ifBlank { "0" }.toIntOrNull()
        if (discount == null || discount !in 0..90) { showToast("Discount must be between 0 and 90%."); return }
        val unit = if (newProductUnit.startsWith("/")) newProductUnit else "/${newProductUnit.ifBlank { "kg" }}"
        editingProductId?.let { id ->
            val old = products.firstOrNull { it.id == id } ?: run { showToast("That product no longer exists."); return }
            if (denied(canEditProduct(old), "edit this product")) return
            val cover = newProductImage.ifBlank { old.imageUrl }
            val galleryError = saveListingImages("product", id, (listOf(cover) + newProductGallery).distinct().take(Database.MAX_IMAGES_PER_LISTING))
            if (galleryError != null) { showToast(galleryError); return }
            val updated = db.products().first { it.id == id }.copy(
                name = newProductName.trim(), category = newProductCategory, price = price, unit = unit, stock = stock, discountPercent = discount,
                description = newProductDesc.ifBlank { old.description }, farmerId = old.farmerId, farmOrigin = old.farmOrigin,
            )
            db.updateProduct(updated)
            allProducts = db.products()
            cart = cart.map { if (it.product.id == id) it.copy(product = updated) else it }
            isAddProductModalOpen = false; editingProductId = null; newProductGallery = emptyList()
            newProductName = ""; newProductDesc = ""; newProductStock = "25"
            showToast("Saved changes to \"${updated.name}\".")
            return
        }
        val item = Product(
            id = System.currentTimeMillis(), name = newProductName, category = newProductCategory,
            price = price,
            unit = unit,
            discountPercent = discount, stock = stock, rating = 0.0, reviewsCount = 0,
            imageUrl = newProductImage.ifBlank { images.defaultProductFallbackImage },
            description = newProductDesc.ifBlank { "Freshly harvested produce from ${myFarm?.farmName ?: "our partner farms"}." },
            farmOrigin = myFarm?.let { "${it.farmName.split(" & ").first()} (${it.province})" } ?: "AgriTourism Partner Farms",
            farmerId = myFarmId,
        )
        db.saveProduct(item, db.minSort("products") - 1)
        val galleryError = saveListingImages("product", item.id, (listOf(item.imageUrl) + newProductGallery).distinct().take(Database.MAX_IMAGES_PER_LISTING))
        if (galleryError != null) { showToast(galleryError); return }
        allProducts = db.products()
        isAddProductModalOpen = false; newProductGallery = emptyList()
        newProductName = ""; newProductDesc = ""; newProductStock = "25"
        showToast("Successfully added \"${item.name}\" (Stock limit: $stock ${item.unit}) to your catalog!")
    }

    fun canEditProduct(p: Product) = isFarmer && myFarmId != null && p.farmerId == myFarmId

    fun openAddProduct() {
        if (denied(isFarmer && myFarmId != null, "add products")) return
        editingProductId = null
        adminListingFarmId = myFarmId
        newProductName = ""; newProductDesc = ""; newProductStock = "25"; newProductPrice = "100"; newProductUnit = "kg"; newProductDiscount = "0"
        newProductGallery = emptyList()
        isAddProductModalOpen = true
    }

    fun openEditProduct(p: Product) {
        if (denied(canEditProduct(p), "edit this product")) return
        editingProductId = p.id
        adminListingFarmId = p.farmerId
        newProductName = p.name; newProductCategory = p.category; newProductPrice = money(p.price).replace(",", "")
        newProductUnit = p.unit; newProductStock = p.stock.toString(); newProductDesc = p.description; newProductImage = p.imageUrl
        newProductDiscount = p.discountPercent.toString()
        newProductGallery = db.listingImages("product", p.id).filterNot { it.isCover }.map { it.url }
        isAddProductModalOpen = true
    }

    /** Asks before running [action]. */
    fun confirm(title: String, message: String, yesLabel: String = "Yes, Delete", cancelLabel: String = "Keep", action: () -> Unit) {
        pendingConfirm = Confirm(title, message, yesLabel, action, cancelLabel)
    }

    /** Removes a product from the market and every cart; past orders keep it. */
    fun deleteProduct(id: Long) {
        val p = products.firstOrNull { it.id == id } ?: return
        if (denied(canEditProduct(p), "delete this product")) return
        db.setArchived("products", id, true)
        allProducts = allProducts.map { if (it.id == id) it.copy(archived = true) else it }
        cart = cart.filterNot { it.product.id == id }
        isAddProductModalOpen = false; editingProductId = null
        showToast("\"${p.name}\" was removed from your catalog.")
    }

    /** Adds stock to a product without opening the full form. */
    fun restockProduct(id: Long, amount: Int) {
        val p = products.firstOrNull { it.id == id } ?: return
        if (denied(canEditProduct(p), "restock this product")) return
        if (amount <= 0) return
        val stock = p.stock + amount
        db.updateStock(id, stock)
        allProducts = allProducts.map { if (it.id == id) it.copy(stock = stock) else it }
        showToast("\"${p.name}\" restocked to $stock.")
    }

    /** Copies a picked image into the app data folder (next to the database) so it keeps working offline; null on failure. */
    fun storeUpload(file: File): String? = runCatching {
        val dir = File(db.file.absoluteFile.parentFile, "uploads").apply { mkdirs() }
        // Encode spaces so reference cleanup never truncates a file URL.
        val safeName = file.name.replace(" ", "_")
        val dest = File(dir, "${System.currentTimeMillis()}-${safeName}")
        file.copyTo(dest, overwrite = true)
        "file:${dest.absolutePath}"
    }.onFailure { showToast("Could not load that photo: ${it.message}") }.getOrNull()

    /** Copies up to the remaining 5-image allowance; returns all successfully stored URLs. */
    fun storeUploads(files: List<File>, alreadyUsed: Int = 0): List<String> {
        val room = Database.MAX_IMAGES_PER_LISTING - alreadyUsed.coerceAtLeast(0)
        if (room <= 0) { showToast("A listing can have at most ${Database.MAX_IMAGES_PER_LISTING} images."); return emptyList() }
        if (files.size > room) showToast("Only $room more image(s) can be added; the rest were skipped.")
        return files.take(room).mapNotNull(::storeUpload)
    }

    fun listingImages(ownerType: String, ownerId: Long, includeDeleted: Boolean = false): List<ListingImage> =
        db.listingImages(ownerType, ownerId, includeDeleted)

    /** Replaces a gallery safely; unchanged images keep their ids and cover state. */
    fun saveListingImages(ownerType: String, ownerId: Long, urls: List<String>, coverIndex: Int = 0): String? {
        val result = runCatching { db.replaceListingImages(ownerType, ownerId, urls, coverIndex) }.exceptionOrNull()
        if (result != null) return result.message
        allTours = db.tours(); allOrchards = db.fruitPicking(); allProducts = db.products()
        farmers = db.farmers()
        return null
    }

    fun uploadProductImage(file: File) {
        if (denied(isFarmer, "upload product photos")) return
        storeUpload(file)?.let { newProductImage = it; showToast("Photo uploaded & saved for offline catalog!") }
    }

    // ---------------------------------------------------------------- profile photos & farm story
    /** Sets the signed-in user's profile photo; a farmer's photo is also their public farm profile photo. */
    fun changeAvatar(file: File) {
        val u = currentUser ?: run { denied(false, "change your photo"); return }
        val url = storeUpload(file) ?: return
        db.tx {
            db.updateUserAvatar(u.id, url)
            myFarmId?.let { db.updateFarmerPhoto(it, url) }
        }
        currentUser = db.findUser(u.email); farmers = db.farmers()
        showToast("Profile photo updated!")
    }

    /** Changes a farmer's profile photo (own farm, or any farm for admins); the farmer's account uses it too. */
    fun changeFarmerPhoto(farmId: Int, file: File) {
        if (denied(canManageFarm(farmId), "change this farmer's photo")) return
        val url = storeUpload(file) ?: return
        db.tx {
            db.updateFarmerPhoto(farmId, url)
            db.farmerAccount(farmId)?.let { db.updateUserAvatar(it.id, url) }
        }
        farmers = db.farmers(); currentUser = currentUser?.let { db.findUser(it.email) }
        showToast("Farmer photo updated!")
    }

    /** Changes a farm's banner photo (own farm, or any farm for admins). */
    fun changeFarmBanner(farmId: Int, file: File) {
        if (denied(canManageFarm(farmId), "change this farm's photo")) return
        val url = storeUpload(file) ?: return
        db.updateFarmerBanner(farmId, url); farmers = db.farmers()
        showToast("Farm cover photo updated!")
    }

    /** Saves a farm's short story and description; returns an error or null on success. */
    fun saveFarmStory(farmId: Int, story: String, description: String): String? {
        if (!canManageFarm(farmId)) return "You can only edit your own farm's story."
        if (story.trim().length > 280) return "Keep the short story under 280 characters."
        db.updateFarmerStory(farmId, story, description); farmers = db.farmers()
        showToast("Farm story saved!")
        return null
    }

    fun updateFarmDetails(farmId: Int, ownerName: String, farmName: String, location: String, province: String): String? {
        if (!isAdmin) return "Only administrators can edit farmer profiles."
        if (isAdmin) return "Administrator accounts have read-only access to farm profiles."
        if (ownerName.isBlank() || farmName.isBlank() || location.isBlank() || province.isBlank())
            return "Complete the owner name, farm name, location, and province."
        if (db.updateFarmerDetails(farmId, ownerName, farmName, location, province) == 0)
            return "That farmer profile no longer exists."
        reloadFromDb()
        showToast("Farmer and farm profile updated.")
        return null
    }

    // ---------------------------------------------------------------- farmer-created tours & orchards
    data class ActivityDraft(
        val name: String,
        val price: String,
        val description: String,
        val image: String,
        val slot: String,
        val hours: String = "",
        val activities: String = "",
        val fruit: String = "",
        val duration: String = "",
        val season: String = "",
        val experienceType: String = "tour",
        val location: String = "",
        val farmerId: Int? = null,
        val amenities: String = "",
        val estimatedDuration: String = "",
        /** Additional images after [image], which is always the cover. */
        val gallery: List<String> = emptyList(),
    )

    private fun draftFarm(d: ActivityDraft): FarmerProfile? =
        myFarmId?.let { id -> farmers.firstOrNull { it.id == id } }

    private fun draftError(d: ActivityDraft): String? = when {
        !isFarmer || myFarmId == null -> "Only farmer accounts linked to a farm can add experiences."
        draftFarm(d) == null -> "Select the farm responsible for this activity."
        else -> fieldsError(d)
    }

    private fun fieldsError(d: ActivityDraft): String? = when {
        d.name.isBlank() -> "Enter a name."
        (d.price.toIntOrNull() ?: 0) <= 0 -> "Enter a price per person above 0."
        d.description.trim().length < 10 -> "Write a short description (at least 10 characters)."
        else -> null
    }

    /** Validates "08:00 AM - Name" style slot labels; returns the normalized label or an error. */
    private fun normalizeSlot(label: String): Result<String> {
        val l = label.trim().replace(Regex("\\s+"), " ")
        val m = Regex("^(\\d{1,2}):(\\d{2}) ?([AaPp][Mm])(\\s*-\\s*(.+))?$").find(l)
            ?: return Result.failure(IllegalArgumentException("Use a time like \"08:00 AM\" or \"08:00 AM - Morning Walk\"."))
        val (h, min, ap) = m.destructured
        if (h.toInt() !in 1..12 || min.toInt() !in 0..59) return Result.failure(IllegalArgumentException("That time isn't valid."))
        val name = m.groupValues[5].trim()
        return Result.success("%02d:%s %s".format(h.toInt(), min, ap.uppercase()) + if (name.isNotEmpty()) " - $name" else "")
    }

    /** Adds a farm tour or other farm activity, published immediately and owned by its selected farm. */
    fun createTour(d: ActivityDraft): String? {
        draftError(d)?.let { return it }
        val f = draftFarm(d) ?: return "Select the farm responsible for this activity."
        val slot = normalizeSlot(d.slot).getOrElse { return it.message }
        val experienceType = d.experienceType.takeIf { it in setOf("tour", "other_activity") } ?: "tour"
        val tour = FarmTour(0, d.name.trim(), d.location.trim().ifBlank { f.location }, 0.0, 0, d.price.toInt(), d.hours.trim().ifBlank { "8:00 AM - 5:00 PM" },
            d.amenities.split(",").map { it.trim() }.filter { it.isNotEmpty() }, d.activities.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            d.image.ifBlank { f.farmBannerUrl }, d.description.trim(), if (experienceType == "other_activity") "Other Farm Activity" else "New",
            f.name, d.gallery.take(Database.MAX_IMAGES_PER_LISTING - 1), f.id,
            estimatedDuration = d.estimatedDuration.trim(), experienceType = experienceType)
        val id = db.tx { db.createTour(tour).also { db.addTimeSlot("tour", it, slot) } }
        db.replaceListingImages("tour", id.toLong(), (listOf(tour.imageUrl) + d.gallery).distinct().take(Database.MAX_IMAGES_PER_LISTING))
        allTours = db.tours(); farmers = db.farmers(); timeSlots = db.timeSlots()
        isAddTourModalOpen = false
        showToast("Tour \"${tour.name}\" is now listed (#$id).")
        return null
    }

    /** Adds a fruit-picking experience to the farmer's own farm; returns an error or null on success. */
    fun createOrchard(d: ActivityDraft): String? {
        draftError(d)?.let { return it }
        if (d.fruit.isBlank()) return "Enter the fruit tourists will pick."
        val f = draftFarm(d) ?: return "Select the farm responsible for this activity."
        val slot = normalizeSlot(d.slot).getOrElse { return it.message }
        val item = FruitPickingItem(0, d.name.trim(), f.farmName, d.location.trim().ifBlank { f.location }, d.fruit.trim(), d.price.toInt(), 0.0, 0,
            d.duration.trim().ifBlank { "2 hours" }, d.fruit.trim(), d.image.ifBlank { f.farmBannerUrl }, d.description.trim(),
            d.season.trim().ifBlank { "Year-round" }, d.gallery.take(Database.MAX_IMAGES_PER_LISTING - 1), f.id)
        val id = db.tx { db.createOrchard(item).also { db.addTimeSlot("orchard", it, slot) } }
        db.replaceListingImages("orchard", id.toLong(), (listOf(item.imageUrl) + d.gallery).distinct().take(Database.MAX_IMAGES_PER_LISTING))
        allOrchards = db.fruitPicking(); timeSlots = db.timeSlots()
        isAddOrchardModalOpen = false
        showToast("Fruit picking \"${item.title}\" is now listed (#$id).")
        return null
    }

    fun canEditTour(t: FarmTour) = canManageFarm(t.farmerId)
    fun canEditOrchard(o: FruitPickingItem) = canManageFarm(o.farmerId)

    /** Saves changes to a tour; returns an error or null on success. */
    fun updateTour(id: Int, d: ActivityDraft): String? {
        val old = tours.firstOrNull { it.id == id } ?: return "That tour no longer exists."
        if (!canEditTour(old)) return "You can only edit your own farm's tours."
        fieldsError(d)?.let { return it }
        val experienceType = d.experienceType.takeIf { it in setOf("tour", "other_activity") } ?: old.experienceType
        val t = old.copy(name = d.name.trim(), pricePerPerson = d.price.toInt(), description = d.description.trim(),
            imageUrl = d.image.ifBlank { old.imageUrl }, operatingHours = d.hours.trim().ifBlank { old.operatingHours },
            activities = d.activities.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            location = d.location.trim().ifBlank { old.location },
            amenities = d.amenities.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: old.amenities,
            estimatedDuration = d.estimatedDuration.trim().ifBlank { old.estimatedDuration },
            experienceType = experienceType,
            highlightTag = if (experienceType == "other_activity") "Other Farm Activity" else old.highlightTag,
            farmerId = old.farmerId, farmerName = old.farmerName,
            thumbnails = d.gallery.take(Database.MAX_IMAGES_PER_LISTING - 1))
        db.updateTour(t)
        db.replaceListingImages("tour", id.toLong(), (listOf(t.imageUrl) + d.gallery).distinct().take(Database.MAX_IMAGES_PER_LISTING))
        allTours = db.tours()
        if (selectedTour.id == id) selectedTour = allTours.first { it.id == id }
        editingTour = null
        showToast("Tour \"${t.name}\" updated.")
        return null
    }

    fun updateTourManagement(
        id: Int,
        priceText: String,
        priceStatus: TourPriceStatus,
        priceType: TourPriceType,
        galleryText: String,
        bookingEnabled: Boolean,
        published: Boolean = true,
    ): String? {
        if (!isAdmin) return "Only administrators can manage tour pricing and availability."
        if (isAdmin) return "Administrator accounts have read-only access to tour listings."
        val tour = db.tours().firstOrNull { it.id == id } ?: return "That tour no longer exists."
        val price = priceText.toIntOrNull()?.takeIf { it >= 0 }
            ?: return "Enter a valid non-negative tour price."
        val hasValidSlot = db.timeSlots().any { slot ->
            slot.activityType == "tour" && slot.activityId == id &&
                db.bookingScheduleError("tour", id, slot.label) == null
        }
        if (published && bookingEnabled && !hasValidSlot) return "Add operating hours and at least one valid time slot before enabling bookings."
        val updated = tour.copy(
            pricePerPerson = price,
            priceApproved = true,
            priceStatus = priceStatus,
            priceType = priceType,
            thumbnails = galleryText.lines().map(String::trim).filter(String::isNotEmpty),
            bookingEnabled = bookingEnabled,
            archived = !published,
        )
        db.updateTour(updated)
        db.setArchived("tours", id.toLong(), !published)
        allTours = allTours.map { if (it.id == id) updated else it }
        if (selectedTour.id == id) selectedTour = updated
        showToast("Tour listing, pricing, and availability updated for ${updated.name}.")
        return null
    }

    /** Publishes or hides one of the current farmer's own listings; admins stay read-only. */
    fun setListingPublished(table: String, id: Long, published: Boolean): String? {
        if (isAdmin) return "Administrator accounts have read-only access to catalog listings."
        if (!isFarmer) return "Only farmers can manage catalog listings."
        if (table !in setOf("tours", "fruit_picking", "products")) return "That listing type is not supported."
        val farmId = myFarmId ?: return "Only farmers linked to a farm can manage listings."
        val owned = when (table) {
            "tours" -> allTours.any { it.id.toLong() == id && it.farmerId == farmId }
            "fruit_picking" -> allOrchards.any { it.id.toLong() == id && it.farmerId == farmId }
            else -> allProducts.any { it.id == id && it.farmerId == farmId }
        }
        if (!owned) return "You can only manage your own farm's listings."
        db.setArchived(table, id, !published)
        when (table) {
            "tours" -> allTours = allTours.map { if (it.id.toLong() == id) it.copy(archived = !published) else it }
            "fruit_picking" -> allOrchards = allOrchards.map { if (it.id.toLong() == id) it.copy(archived = !published) else it }
            "products" -> {
                allProducts = allProducts.map { if (it.id == id) it.copy(archived = !published) else it }
                if (!published) cart = cart.filterNot { it.product.id == id }
            }
        }
        showToast("Listing ${if (published) "published" else "unpublished"}.")
        return null
    }

    /** Saves changes to a fruit-picking experience; returns an error or null on success. */
    fun updateOrchard(id: Int, d: ActivityDraft): String? {
        val old = fruitPicking.firstOrNull { it.id == id } ?: return "That experience no longer exists."
        if (!canEditOrchard(old)) return "You can only edit your own farm's orchards."
        fieldsError(d)?.let { return it }
        if (d.fruit.isBlank()) return "Enter the fruit tourists will pick."
        val o = old.copy(title = d.name.trim(), pricePerPerson = d.price.toInt(), description = d.description.trim(),
            imageUrl = d.image.ifBlank { old.imageUrl }, fruitName = d.fruit.trim(), seasonalFruits = d.fruit.trim(),
            duration = d.duration.trim().ifBlank { old.duration }, seasonAvailability = d.season.trim().ifBlank { old.seasonAvailability },
            location = d.location.trim().ifBlank { old.location },
            farmName = old.farmName, farmerId = old.farmerId,
            thumbnails = d.gallery.take(Database.MAX_IMAGES_PER_LISTING - 1))
        db.updateOrchard(o)
        db.replaceListingImages("orchard", id.toLong(), (listOf(o.imageUrl) + d.gallery).distinct().take(Database.MAX_IMAGES_PER_LISTING))
        allOrchards = db.fruitPicking()
        if (selectedFruit.id == id) selectedFruit = allOrchards.first { it.id == id }
        editingOrchard = null
        showToast("\"${o.title}\" updated.")
        return null
    }

    /** Upcoming non-cancelled bookings for a tour/orchard. */
    fun upcomingBookings(type: String, id: Int): List<Booking> = bookings.filter {
        it.activityType == type && it.farmId == id && it.status != "Cancelled" &&
            parseTourDate(it.tourDate)?.let { d -> !d.isBefore(LocalDate.now()) } != false
    }

    fun askDeleteTour(t: FarmTour) {
        val n = upcomingBookings("tour", t.id).size
        confirm("Delete \"${t.name}\"?", "Tourists will no longer see or book it." +
            if (n > 0) " $n upcoming booking(s) stay valid — contact those guests if the tour won't run." else " Past bookings are kept.",
            yesLabel = "Yes, Delete") { deleteTour(t.id) }
    }

    fun askDeleteOrchard(o: FruitPickingItem) {
        val n = upcomingBookings("orchard", o.id).size
        confirm("Delete \"${o.title}\"?", "Tourists will no longer see or book it." +
            if (n > 0) " $n upcoming booking(s) stay valid — contact those guests if it won't run." else " Past bookings are kept.",
            yesLabel = "Yes, Delete") { deleteOrchard(o.id) }
    }

    /** Removes a tour from the catalog; existing bookings keep working. */
    fun deleteTour(id: Int) {
        val t = tours.firstOrNull { it.id == id } ?: return
        if (denied(canEditTour(t), "delete this tour")) return
        db.setArchived("tours", id.toLong(), true)
        allTours = allTours.map { if (it.id == id) it.copy(archived = true) else it }
        if (selectedTour.id == id) { isTourModalOpen = false; tours.firstOrNull()?.let { selectedTour = it } }
        editingTour = null
        showToast("Tour \"${t.name}\" removed. Existing bookings are kept.")
    }

    fun deleteOrchard(id: Int) {
        val o = fruitPicking.firstOrNull { it.id == id } ?: return
        if (denied(canEditOrchard(o), "delete this experience")) return
        db.setArchived("fruit_picking", id.toLong(), true)
        allOrchards = allOrchards.map { if (it.id == id) it.copy(archived = true) else it }
        if (selectedFruit.id == id) { isFruitModalOpen = false; fruitPicking.firstOrNull()?.let { selectedFruit = it } }
        editingOrchard = null
        showToast("\"${o.title}\" removed. Existing bookings are kept.")
    }

    // ---------------------------------------------------------------- orders
    /** Only the owning tourist or owning farmer may cancel a pending order. */
    fun canCancelOrder(o: FarmerOrder): Boolean = !o.isReceivedByUser && when {
        ownsOrder(o) -> o.fulfillmentStatus == "Pending"
        isFarmer && canManageOrder(o) -> o.fulfillmentStatus == "Pending"
        else -> false
    }

    fun canUpdateOrderAddress(o: FarmerOrder) =
        ownsOrder(o) && o.fulfillmentStatus == "Pending" && !o.isReceivedByUser

    fun updateOrderAddress(id: String, address: String) {
        val o = order(id) ?: return
        if (denied(canUpdateOrderAddress(o), "update this order's delivery address")) return
        val normalized = address.trim()
        if (normalized.length !in 5..300) {
            showToast("Enter a delivery address between 5 and 300 characters.")
            return
        }
        val updated = db.updatePendingOrderAddress(id, userEmail, normalized)
        if (updated == null) {
            showToast("Only your pending order can have its delivery address updated.")
            farmerOrders = db.orders()
            return
        }
        farmerOrders = db.orders()
        selectedOrderForView = updated
        showToast("Delivery address updated.")
    }

    /** Cancels an order and puts its items back in stock. */
    fun cancelOrder(id: String) {
        val o = order(id) ?: return
        if (denied(canCancelOrder(o), "cancel this order")) return
        val byTourist = isTourist
        val cancelled = db.transitionOrderStatus(id, "Cancelled", allowConfirmedCancellation = false)
        if (cancelled == null) {
            showToast("This order can no longer be cancelled.")
            return
        }
        farmerOrders = db.orders()
        allProducts = db.products()
        if (byTourist) notifyFarms(farmsOf(o), AppNotification(System.currentTimeMillis(), "Order Cancelled: ${o.customerName}",
            "Order #${o.orderNumber} was cancelled by the customer.", "${o.customerName} cancelled order #${o.orderNumber}. Items were returned to stock.",
            nowStamp(), false, "farmer_order", "farmer", orderId = o.id, eventKey = "order:${o.id}:cancelled"))
        else o.customerEmail?.takeIf { it.isNotBlank() }?.let { email ->
            val productNames = o.items.joinToString { it.productName }
            addNotification(AppNotification(System.currentTimeMillis(), "Order Cancelled.",
                "Order Cancelled. Your order for $productNames has been cancelled.",
                "Order #${o.orderNumber} was cancelled by the farm. Contact the farm about any payment you made.",
                nowStamp(), false, "order", "tourist", recipientEmail = email, orderId = o.id,
                eventKey = "order:${o.id}:cancelled"))
        }
        showToast("Order #${o.orderNumber} cancelled. Items returned to stock.")
    }

    /** Tourists may permanently remove their own cancelled orders from their purchase history. */
    fun canDeletePurchase(o: FarmerOrder): Boolean =
        ownsOrder(o) && o.fulfillmentStatus == "Cancelled" && !o.isReceivedByUser

    /** Permanently deletes a cancelled purchase that belongs to the signed-in tourist. */
    fun deletePurchase(id: String) {
        val o = order(id) ?: return
        if (denied(canDeletePurchase(o), "delete this cancelled purchase")) return
        if (!db.deleteOrder(id)) {
            showToast("Only your own cancelled orders can be deleted.")
            farmerOrders = db.orders()
            return
        }
        farmerOrders = db.orders()
        if (selectedOrderForView?.id == id) selectedOrderForView = null
        showToast("Order #${o.orderNumber} removed from your purchases.")
    }

    /** Backwards-compatible alias used by older call sites; same cancelled-tourist-only rule. */
    fun deleteOrder(id: String) = deletePurchase(id)

    fun orderStatusUpdate(id: String, status: String) {
        if (status == "Cancelled") {
            cancelOrder(id)
            return
        }
        val o = order(id) ?: return
        if (denied(canActOnOrder(o), "confirm this order")) return
        if (status != "Confirmed" || o.fulfillmentStatus != "Pending") {
            showToast("Only pending orders can be confirmed.")
            return
        }
        if (db.transitionOrderStatus(id, "Confirmed") == null) {
            showToast("This order is no longer pending.")
            return
        }
        farmerOrders = db.orders()
        o.customerEmail?.takeIf { it.isNotBlank() }?.let { email ->
            val productNames = o.items.joinToString { it.productName }
            addNotification(AppNotification(System.currentTimeMillis(), "Order Confirmed!",
                "Order Confirmed! Your order for $productNames has been confirmed by the farmer.",
                "Order #${o.orderNumber} · Total: $PESO${money(o.totalAmount)} · Cash on Delivery.",
                nowStamp(), false, "order", "tourist", recipientEmail = email, orderId = o.id,
                eventKey = "order:${o.id}:confirmed"))
        }
        showToast("Order #${o.orderNumber} confirmed.")
    }

    // ---------------------------------------------------------------- notifications
    fun openNotification(n: AppNotification) {
        if (n !in roleFilteredNotifications) return
        updateNotifications(notifications.map { if (it.id == n.id) it.copy(read = true).also(db::saveNotification) else it })
        selectedNotification = n
    }

    // ---------------------------------------------------------------- receipts / tickets
    fun printTourReceipt(b: Booking) {
        if (denied(canViewBooking(b), "view this receipt")) return
        val unit = (b.totalAmount.toDouble() / max(1, b.participants)).roundToInt().toDouble()
        selectedReceipt = Receipt(
            "RCP-${b.bookingCode}", "${b.tourDate} · ${b.paymentMethod ?: PAY_AT_FARM_PAYMENT_METHOD}", b.customerName,
            b.customerPhone ?: "—", b.customerEmail ?: "Walk-in guest", b.farmName,
            farmOf(b)?.let { id -> farmers.firstOrNull { it.id == id }?.name } ?: b.assignedGuide ?: "Farm Host",
            listOf(ReceiptItem("${b.activity} (${b.tourTime})", b.participants, unit, b.totalAmount.toDouble())),
            b.totalAmount.toDouble(), 0.0, b.totalAmount.toDouble(), b.paymentMethod ?: PAY_AT_FARM_PAYMENT_METHOD,
            when { b.status == "Cancelled" -> "CANCELLED"; isPayOnSite(b.paymentMethod) -> "TO BE PAID AT THE FARM"; else -> "PAID & CONFIRMED" },
            "AUTH-TK-${b.id}88", "tour_booking",
        )
    }

    fun printOrderReceipt(o: FarmerOrder) {
        if (denied(isFarmer && canManageOrder(o), "print this receipt")) return
        val items = o.items.map { ReceiptItem(it.productName, it.qty, it.unitPrice, it.qty * it.unitPrice) }
        val sub = items.sumOf { it.total }
        val fee = (o.totalAmount - sub).coerceAtLeast(0.0)
        selectedReceipt = Receipt(
            "RCP-${o.orderNumber}", o.orderDate, o.customerName, o.customerPhone, o.customerAddress,
            farmsOf(o).mapNotNull { id -> farmers.firstOrNull { it.id == id }?.farmName }.ifEmpty { listOf("AgriTourism Partner Farms") }.joinToString(" · "),
            farmsOf(o).mapNotNull { id -> farmers.firstOrNull { it.id == id }?.name }.ifEmpty { listOf("AgriTourism Philippines") }.joinToString(", "),
            items, sub, fee, sub + fee,
            o.paymentMethod, o.paymentStatus.ifBlank { if (o.paymentMethod == COD_PAYMENT_METHOD) "COD" else "PAID" }, "TXN-${o.orderNumber}-PH", "produce_order",
        )
    }

    private fun isPayOnSite(method: String?) = method == PAY_AT_FARM_PAYMENT_METHOD

}
