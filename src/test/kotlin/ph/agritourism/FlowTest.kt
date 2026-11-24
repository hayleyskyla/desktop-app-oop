package ph.agritourism

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ph.agritourism.data.AdminSetup
import ph.agritourism.data.Database
import ph.agritourism.model.AppNotification
import ph.agritourism.model.AvailabilityStatus
import ph.agritourism.model.Courier
import ph.agritourism.model.Booking
import ph.agritourism.model.COD_PAYMENT_METHOD
import ph.agritourism.model.PAY_AT_FARM_PAYMENT_METHOD
import ph.agritourism.model.Role
import ph.agritourism.model.TourPriceStatus
import ph.agritourism.model.TourPriceType
import ph.agritourism.model.bookingTotal
import ph.agritourism.model.phoneDigits
import ph.agritourism.ui.AppState
import ph.agritourism.ui.CHECKOUT_PAYMENT_METHODS
import ph.agritourism.ui.ImageLoader
import ph.agritourism.ui.ORDER_STATUSES
import ph.agritourism.ui.Page
import ph.agritourism.ui.TOUR_PAYMENT_METHODS
import ph.agritourism.ui.WALK_IN_PAYMENT_METHODS
import ph.agritourism.model.slotKey
import java.io.File
import kotlin.test.*

/** Exercises the same user flows as the original React app against a real temporary SQLite database. */
class FlowTest {
    private val file = File.createTempFile("agri", ".db").also { it.delete() }
    private val adminEmail = "admin-${java.util.UUID.randomUUID()}@test.invalid"
    private val adminPassword = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()
    private val adminSetup = AdminSetup(adminEmail, adminPassword)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: Database
    private lateinit var s: AppState

    @BeforeTest fun setUp() { db = Database(file, adminSetup); s = AppState(db, scope) }
    @AfterTest fun tearDown() { scope.cancel(); db.close(); file.delete() }

    private fun reopen(): AppState { db.close(); db = Database(file, adminSetup); return AppState(db, scope) }

    /** Logs [st] in as the seeded demo account for [role]. */
    private fun AppState.loginAs(role: Role): AppState {
        if (isLoggedIn) signOut()
        loginRole = role
        loginEmail = if (role == Role.ADMIN) adminEmail else role.email
        loginPassword = if (role == Role.ADMIN) adminPassword else Database.DEFAULT_PASSWORD
        assertTrue(login(), "login as $role failed: $loginError")
        return this
    }

    /** Logs in as a specific seeded account; all seed accounts share the demo password. */
    private fun AppState.loginAsUser(email: String): AppState {
        if (isLoggedIn) signOut()
        loginEmail = email
        loginPassword = Database.DEFAULT_PASSWORD
        assertTrue(login(), "login as $email failed: $loginError")
        return this
    }

    private fun AppState.loginAsOwnerFarmerFor(farmId: Int?): AppState {
        val account = assertNotNull(farmId?.let(db::farmerAccount), "no farmer account owns farm $farmId")
        return loginAsUser(account.email)
    }

    private fun AppState.loginAsOwnerFarmerForBooking(bookingId: Long): AppState {
        val b = assertNotNull(bookings.firstOrNull { it.id == bookingId })
        return loginAsOwnerFarmerFor(farmOf(b))
    }

    private fun AppState.loginAsOwnerFarmerForOrder(orderId: String): AppState {
        val o = assertNotNull(db.orders().firstOrNull { it.id == orderId })
        return loginAsOwnerFarmerFor(assertNotNull(farmsOf(o).firstOrNull()))
    }

    private fun AppState.loginAsBookingTourist(bookingId: Long): AppState {
        val b = assertNotNull(bookings.firstOrNull { it.id == bookingId })
        return loginAsUser(assertNotNull(b.customerEmail))
    }

    /** Gives a seeded listing a farmer owner/account so farm-side actions can be exercised. */
    private fun AppState.assignSeedBookingOwnerToFarmer(bookingId: Long) {
        val b = assertNotNull(db.bookings().firstOrNull { it.id == bookingId })
        var ownerId = if (b.activityType == "orchard") {
            db.fruitPicking().first { it.id == b.farmId }.farmerId
        } else {
            db.tours().first { it.id == b.farmId }.farmerId
        }
        if (ownerId == null) {
            ownerId = 2 // a seeded farm profile distinct from the default demo farmer (farm 1)
            if (b.activityType == "orchard") db.runSql("UPDATE fruit_picking SET farmer_id=$ownerId WHERE id=${b.farmId}")
            else db.runSql("UPDATE tours SET farmer_id=$ownerId WHERE id=${b.farmId}")
        }
        if (db.farmerAccount(ownerId) == null) {
            val email = "farm$ownerId-owner@test.invalid"
            assertNotNull(db.createUser("Farm $ownerId Owner", email, "09170000000", Database.DEFAULT_PASSWORD, Role.FARMER.key))
            db.runSql("UPDATE users SET farmer_id=$ownerId WHERE lower(email)=lower('$email')")
        }
        s.allTours = db.tours()
        s.allOrchards = db.fruitPicking()
    }

    @Test fun seedMatchesOriginalData() {
        assertEquals(13, s.tours.size); assertEquals(7, s.tours.count { it.priceStatus == TourPriceStatus.ESTIMATED })
        assertEquals(6, s.fruitPicking.size); assertEquals(5, s.farmers.size)
        assertEquals(16, s.products.size); assertEquals(4, s.bookings.size); assertEquals(3, s.farmerOrders.size)
        assertEquals(6, s.notifications.size) // the retired generic "Booking" notice is not seeded
        assertEquals(13, s.tours.map { it.name }.distinct().size)
        assertTrue(s.tours.filter { it.priceStatus == TourPriceStatus.ESTIMATED }.all {
            it.pricePerPerson > 0 && it.bookingEnabled && it.rating == 0.0 && it.reviewsCount == 0
        })
        assertTrue(s.tours.filter { it.priceStatus == TourPriceStatus.ESTIMATED }.all { s.slotsFor("tour", it.id).size == 10 })
        assertFalse(s.isLoggedIn); assertEquals(Page.HOME, s.activePage); assertTrue(s.cart.isEmpty())
        s.loginAs(Role.USER)
        assertEquals(3, s.cart.size); assertEquals(setOf(1L, 2L, 3L), s.favoriteIds)
    }

    @Test fun retiredGenericBookingNoticesAreHiddenAndPurged() {
        assertTrue(db.notifications().none { it.title == "Booking" || it.title == "Tour Booking Confirmed" })
        db.saveNotification(AppNotification(990_001, "Booking", "old", "old", "now", false, "booking", "tourist", recipientEmail = Role.USER.email))
        db.saveNotification(AppNotification(990_002, "Tour Booking Confirmed", "old", "old", "now", false, "booking", "tourist", recipientEmail = Role.USER.email))
        s.loginAs(Role.USER)
        assertTrue(s.roleFilteredNotifications.none { it.title == "Booking" || it.title == "Tour Booking Confirmed" })
        assertTrue(reopen().notifications.none { it.title == "Booking" || it.title == "Tour Booking Confirmed" })
    }

    @Test fun allBundledImagesLoad() {
        val refs = (s.farmers.flatMap { listOf(it.photoUrl) + it.gallery } +
            s.tours.flatMap { listOf(it.imageUrl) + it.thumbnails } +
            s.fruitPicking.flatMap { listOf(it.imageUrl) + it.thumbnails } +
            listOf(s.images.juanAvatar, s.images.mariaAvatar, s.images.adminAvatar, "res:icons/pwa-192x192.png", "res:icons/pwa-512x512.png") +
            ph.agritourism.ui.dialogs.MOCKUPS.map { "res:mockups/${it.second}" }).filter { it.startsWith("res:") }.distinct()
        assertTrue(refs.size >= 40, "expected many local images, got ${refs.size}")
        refs.forEach { assertNotNull(ImageLoader.bytes(it), "missing $it") }
        val draftPhotos = s.tours.filter { it.priceStatus == TourPriceStatus.ESTIMATED }.flatMap { listOf(it.imageUrl) + it.thumbnails }
        assertEquals(29, draftPhotos.size)
        assertEquals(draftPhotos.size, draftPhotos.distinct().size, "draft tours must not share or duplicate photos")
        // every image file shipped in resources is referenced somewhere
        val shipped = listOf("farmers", "fruit_picking", "mockups").flatMap { d ->
            File("src/main/resources/$d").list()!!.map { "res:$d/$it" } }
        val unused = shipped - refs.toSet()
        assertTrue(unused.isEmpty(), "unused images: $unused")
    }

    @Test fun uploadedToursHaveDailySchedulesAndAcceptDifferentCustomersInOneSlot() {
        val draft = s.tours.first { it.name == "Chocolate Hills" }
        assertEquals(300, draft.pricePerPerson)
        assertEquals(TourPriceType.PER_PERSON, draft.priceType)
        val slots = s.slotsFor("tour", draft.id)
        assertEquals(10, slots.size)
        assertEquals("07:00 AM - 08:00 AM", slots.first())
        assertEquals("04:00 PM - 05:00 PM", slots.last())
        assertEquals("7:00 AM - 5:00 PM", draft.operatingHours)
        assertNull(db.bookingScheduleError("tour", draft.id, slots.first()))
        assertNotNull(db.bookingScheduleError("tour", draft.id, "06:00 AM - 07:00 AM"))

        s.loginAs(Role.USER)
        s.selectedTour = draft
        s.tourDate = ph.agritourism.model.BookingDateWindow.current().last.toString()
        s.tourTime = slots.first()
        val bookingCount = s.bookings.size
        s.bookSelectedTour()
        assertEquals(bookingCount + 1, s.bookings.size)
        val customerBooking = s.bookings.first { it.customerEmail.equals(Role.USER.email, true) && it.farmId == draft.id }
        assertEquals(PAY_AT_FARM_PAYMENT_METHOD, customerBooking.paymentMethod)

        val otherCustomer = customerBooking.copy(
            id = Long.MAX_VALUE - 2,
            bookingCode = "OTHER-CUSTOMER-2099",
            customerName = "Another Tourist",
            customerEmail = "another-tourist@test.invalid",
        )
        db.saveBooking(otherCustomer)
        assertEquals(bookingCount + 2, db.bookings().size, "different customers can share one tour time")

        val outsideSchedule = customerBooking.copy(
            id = Long.MAX_VALUE - 3,
            bookingCode = "OUTSIDE-SCHEDULE-2099",
            customerEmail = "outside-tourist@test.invalid",
            tourTime = "06:00 AM - 07:00 AM",
        )
        assertFailsWith<Database.InvalidBookingScheduleException> { db.saveBooking(outsideSchedule) }
        assertNotNull(s.updateTourManagement(draft.id, "300", TourPriceStatus.ESTIMATED, TourPriceType.PER_PERSON, "", true),
            "tourists must not manage tour prices or availability")

        s.loginAs(Role.ADMIN)
        assertNotNull(s.updateTourManagement(draft.id, "275", TourPriceStatus.ESTIMATED, TourPriceType.PER_PERSON,
            draft.thumbnails.joinToString("\n"), true), "admin accounts are read-only for tour listings")
        assertEquals(300, db.tours().first { it.id == draft.id }.pricePerPerson)
        assertTrue(db.tours().first { it.id == draft.id }.bookingEnabled)

        val ids = db.tours().map { it.id }
        val reopened = reopen()
        assertEquals(13, reopened.tours.size)
        assertEquals(ids, reopened.tours.map { it.id })
        assertEquals(bookingCount + 2, reopened.bookings.size)
        assertEquals(10, reopened.slotsFor("tour", draft.id).size)
    }

    @Test fun groupAndPackageTourPricesAreNotMultipliedByGuestCount() {
        val tour = s.tours.first()
        assertEquals(tour.pricePerPerson * 4, tour.copy(priceType = TourPriceType.PER_PERSON).bookingTotal(4))
        assertEquals(tour.pricePerPerson, tour.copy(priceType = TourPriceType.PER_GROUP).bookingTotal(4))
        assertEquals(tour.pricePerPerson, tour.copy(priceType = TourPriceType.PER_PACKAGE).bookingTotal(4))
    }

    @Test fun loginLogoutAndRoles() {
        s.signOut(); assertFalse(s.isLoggedIn); assertEquals(Page.HOME, s.activePage)
        assertTrue(s.roleFilteredNotifications.isEmpty())
        s.addToCart(s.products[0]); assertEquals(Page.LOGIN, s.activePage) // gated
        s.loginAs(Role.FARMER)
        assertEquals(Role.FARMER, s.userRole); assertEquals(Page.DASHBOARD, s.activePage)
        assertTrue(s.roleFilteredNotifications.all { it.targetRole == "farmer" })
        s.loginAs(Role.USER)
        assertEquals(Page.DASHBOARD, s.activePage)
        assertTrue(s.roleFilteredNotifications.all { it.targetRole == "tourist" })
        assertNull(s.signup("New Tourist", "new@x.ph", "0917 555 1234", "secret1", Role.USER))
        assertTrue(s.avatar.isBlank())
        assertTrue(db.findUser("new@x.ph")!!.avatarUrl.isBlank())
        assertEquals("user", db.runSql("SELECT role FROM users WHERE email='new@x.ph'").rows.single().single())
    }

    @Test fun myPurchasesPageIsScopedToCurrentTourist() {
        s.loginAs(Role.USER)
        assertTrue(s.canAccess(Page.MY_PURCHASES))
        assertTrue(s.visibleOrders.isNotEmpty())
        s.navigate(Page.MY_PURCHASES)
        assertEquals(Page.MY_PURCHASES, s.activePage)
        s.signOut()
        assertNull(s.signup("Other Tourist", "other@x.ph", "0917 555 4321", "secret1", Role.USER))
        assertTrue(s.visibleOrders.isEmpty())
        assertTrue(s.canAccess(Page.MY_PURCHASES))
        s.loginAs(Role.FARMER)
        assertFalse(s.canAccess(Page.MY_PURCHASES))
    }

    @Test fun cartCheckoutPersistsOrderAndStock() {
        s.loginAs(Role.USER)
        val mango = s.cart.first().product
        val stockBefore = s.products.first { it.id == mango.id }.stock
        s.updateCartQty(mango.id, 1)
        assertEquals(3, s.cart.first().quantity)
        val expectedTotal = s.cartSubtotal + 70.0
        s.paymentMethod = "Cash on Delivery"
        s.placeOrder()
        assertEquals(1, s.checkoutStep); assertTrue(s.cart.isEmpty())
        assertEquals(expectedTotal, s.lastOrderTotal, 0.001)
        val order = s.farmerOrders.first()
        assertEquals(COD_PAYMENT_METHOD, order.paymentMethod)
        assertEquals("COD", order.paymentStatus); assertEquals(order.id, s.lastPlacedOrder?.id)
        assertEquals(order, s.purchaseConfirmationOrder)
        val orderedMango = order.items.first { it.productName == mango.name }
        assertEquals(3, orderedMango.qty)
        s.placeOrder()
        assertEquals(4, s.farmerOrders.size, "repeated submission must not create another order")
        val s2 = reopen()
        assertEquals(4, s2.farmerOrders.size); assertTrue(s2.cart.isEmpty())
        assertEquals(stockBefore - 3, s2.products.first { it.id == mango.id }.stock)
        assertEquals(order.items, s2.farmerOrders.first().items)
    }

    @Test fun stockLimitEnforced() {
        s.loginAs(Role.USER)
        val p = s.products.first { it.stock in 1..60 }
        s.cart.filter { it.product.id == p.id }.forEach { s.updateCartQty(p.id, -it.quantity) }
        repeat(p.stock + 5) { s.addToCart(p) }
        assertEquals(p.stock, s.cart.first { it.product.id == p.id }.quantity)
    }

    @Test fun tourAndFruitBookingFlow() {
        s.loginAs(Role.USER)
        s.selectedTour = s.tours[1]; s.tourGuests = 3; s.isTourModalOpen = true
        s.bookSelectedTour()
        val b = s.bookings.first()
        assertEquals(s.tours[1].bookingTotal(s.tourGuests), b.totalAmount)
        assertEquals(3, b.participants)
        assertEquals(PAY_AT_FARM_PAYMENT_METHOD, b.paymentMethod)
        assertEquals(b, s.selectedBookingForTicket); assertEquals(Page.MY_BOOKINGS, s.activePage); assertFalse(s.isTourModalOpen)
        s.selectedFruit = s.fruitPicking[0]; s.fruitGuests = 2; s.bookSelectedFruit()
        assertEquals(PAY_AT_FARM_PAYMENT_METHOD, s.bookings.first().paymentMethod)
        assertEquals(s.slotsFor("orchard", s.fruitPicking[0].id).first(), s.bookings.first().tourTime)
        assertEquals(6, reopen().bookings.size)
    }

    @Test fun bookingLifecycle() {
        val id = 101L
        s.assignSeedBookingOwnerToFarmer(id)
        s.loginAs(Role.USER)
        s.attendanceConfirmation(id, true)
        assertEquals("Confirmed Attending", s.bookings.first { it.id == id }.attendanceStatus)
        assertTrue(s.notifications.first().title.startsWith("Visitor Confirmed Arrival"))
        s.loginAs(Role.FARMER) // booking 101 is at a farm Juan doesn't run
        s.checkInBooking(id); assertNotEquals("Arrived & Checked In", s.bookings.first { it.id == id }.attendanceStatus)
        s.loginAs(Role.ADMIN)
        s.checkInBooking(id); assertNotEquals("Arrived & Checked In", s.bookings.first { it.id == id }.attendanceStatus) // admin is read-only
        s.loginAsOwnerFarmerForBooking(id)
        s.checkInBooking(id); assertEquals("Arrived & Checked In", s.bookings.first { it.id == id }.attendanceStatus)
        s.assignGuide(id, "Mang Ben (Orchard Elder)")
        s.editingBooking = s.bookings.first { it.id == id }.copy(
            participants = 4,
            tourDate = "2026-12-01",
            tourTime = "08:00 AM - Morning Agro-Walk",
        ); s.saveEditedBooking()
        s.cancelBooking(id); assertEquals("Cancelled", s.bookings.first { it.id == id }.status)
        val s2 = reopen()
        val b = s2.bookings.first { it.id == id }
        assertEquals("Mang Ben (Orchard Elder)", b.assignedGuide); assertEquals(4, b.participants); assertEquals("Cancelled", b.status)
        s2.loginAs(Role.ADMIN); s2.deleteBooking(id); assertNotNull(s2.bookings.firstOrNull { it.id == id }) // admin is read-only
        s2.loginAsBookingTourist(id); s2.deleteBooking(id); assertNull(reopen().bookings.firstOrNull { it.id == id })
    }

    @Test fun farmerOperations() {
        s.loginAs(Role.FARMER)
        s.newProductName = "Test Kamote"; s.newProductStock = "12"; s.newProductImage = "res:fruit_picking/kamoteng_kahoy.jpg"
        s.saveProduct()
        assertEquals("Test Kamote", s.products.first().name); assertEquals("/kg", s.products.first().unit)
        s.newBookingCustomer = "Walk In"; s.newBookingPhone = "0917 111 2222"; s.newBookingGuests = 3; s.saveFarmerBooking()
        assertEquals("Walk In", s.bookings.first().customerName)
        assertEquals(PAY_AT_FARM_PAYMENT_METHOD, s.bookings.first().paymentMethod)
        assertFalse(s.products.first { it.name == "Test Kamote" }.archived)
        val pending = s.bookings.firstOrNull { it.status == "Pending" }
        if (pending != null) { s.bookingAction(pending.id, "Confirmed"); assertEquals("Confirmed", s.bookings.first { it.id == pending.id }.status) }
        val s2 = reopen()
        assertEquals("Test Kamote", s2.products.first().name)
        assertTrue(s2.farmerOrders.all { it.fulfillmentStatus in ORDER_STATUSES })
    }

    @Test fun receiptsAndPrintHtml() {
        s.loginAs(Role.ADMIN)
        s.printOrderReceipt(s.farmerOrders.first())
        assertNull(s.selectedReceipt, "Admin accounts must not print farmer receipts")
        s.loginAs(Role.FARMER)
        s.printOrderReceipt(s.farmerOrders.first())
        val r = s.selectedReceipt!!
        assertEquals(r.subtotal + r.deliveryFee, r.totalAmount, 0.001)
        assertTrue(ph.agritourism.ui.Printing.receiptHtml(r).contains(r.receiptNumber))
        s.loginAs(Role.ADMIN)
        s.printTourReceipt(s.bookings.first())
        assertEquals(0.0, s.selectedReceipt!!.deliveryFee)
        assertTrue(ph.agritourism.ui.Printing.ticketHtml(s.bookings.first()).contains(s.bookings.first().bookingCode))
    }

    @Test fun favoritesPersist() {
        s.loginAs(Role.USER)
        assertEquals(3, s.favoriteNames.size)
        s.navigate(Page.WISHLIST); assertEquals(Page.WISHLIST, s.activePage)
        s.toggleFavorite(5, "x"); s.toggleFavorite(1, "y")
        assertEquals(setOf(2L, 3L, 5L), reopen().loginAs(Role.USER).favoriteIds)
        assertTrue(reopen().loginAs(Role.ADMIN).favoriteIds.isEmpty()) // favorites are per account
    }

    @Test fun searchFindsAndOpensCatalogItems() {
        s.loginAs(Role.USER)
        s.searchQuery = "sUnNyVaLe"
        assertTrue(s.searchResults.any { it.type == ph.agritourism.ui.SearchResultType.FARM })
        assertTrue(s.searchResults.any { it.type == ph.agritourism.ui.SearchResultType.TOUR })
        assertTrue(s.searchResults.any { it.type == ph.agritourism.ui.SearchResultType.ORCHARD })
        s.openSearchResult(s.searchResults.first { it.type == ph.agritourism.ui.SearchResultType.FARM })
        assertNotNull(s.selectedFarmerForModal)
        s.selectedFarmerForModal = null

        s.searchQuery = "strawberry"
        val orchard = s.searchResults.first { it.type == ph.agritourism.ui.SearchResultType.ORCHARD }
        s.openSearchResult(orchard)
        assertTrue(s.isFruitModalOpen)
        s.isFruitModalOpen = false

        s.searchQuery = "carabao"
        val product = s.searchResults.first { it.type == ph.agritourism.ui.SearchResultType.PRODUCT }
        s.navigate(Page.MARKET)
        s.openSearchResult(product)
        assertEquals(product.id, s.selectedProductForView?.id)
        assertEquals(db.products().first { it.id == product.id }.description, s.selectedProductForView?.description)
        assertEquals(Page.MARKET, s.activePage) // detail is a modal over the listing, not a new route

        s.searchQuery = "no matching catalog entry"
        assertTrue(s.searchResults.isEmpty())
        s.searchQuery = ""
        assertTrue(s.searchResults.isEmpty())
    }

    @Test fun newAccountsUseNeutralAvatarAndUploadsPersist() {
        assertNull(s.signup("New Tourist", "avatar@x.ph", "0917 555 1234", "secret1", Role.USER))
        assertTrue(s.avatar.isBlank())
        assertTrue(db.findUser("avatar@x.ph")!!.avatarUrl.isBlank())

        val image = File.createTempFile("avatar", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        s.changeAvatar(image)
        val saved = s.avatar
        image.delete()
        assertTrue(saved.startsWith("file:"))
        assertEquals(saved, db.findUser("avatar@x.ph")!!.avatarUrl)
        s.signOut()
        assertTrue(s.avatar.isBlank())
        val restored = reopen()
        restored.loginEmail = "avatar@x.ph"; restored.loginPassword = "secret1"
        assertTrue(restored.login())
        assertEquals(saved, restored.avatar)
    }

    @Test fun authUsesDatabase() {
        s.signOut()
        s.loginEmail = Role.USER.email; s.loginPassword = "wrong"
        assertFalse(s.login()); assertFalse(s.isLoggedIn); assertNotNull(s.loginError)
        s.loginEmail = Role.USER.email; s.loginPassword = Database.DEFAULT_PASSWORD
        assertTrue(s.login())
        assertEquals(Role.USER, s.userRole)
        s.signOut()
        assertNotNull(s.signup("Dup", Role.USER.email, "0917 555 9999", "secret1", Role.USER))
        assertNotNull(s.signup("Boss", "boss@x.ph", "0917 555 8888", "secret1", Role.ADMIN))
        assertEquals(1, db.runSql("SELECT COUNT(*) FROM users WHERE role='admin'").rows.single().single().toInt())
        val admin = assertNotNull(db.findUser(adminEmail))
        assertEquals(Role.ADMIN, admin.role)
        val adminId = admin.id
        s.loginEmail = adminEmail; s.loginPassword = adminPassword
        assertTrue(s.login()); assertEquals(Role.ADMIN, s.userRole); assertEquals(Page.DASHBOARD, s.activePage)
        assertTrue(s.canAccess(Page.PLATFORM_SETTINGS))
        s = reopen()
        assertEquals(adminId, db.findUser(adminEmail)?.id)
        assertEquals(1, db.runSql("SELECT COUNT(*) FROM users WHERE role='admin'").rows.single().single().toInt())
        assertNull(s.signup("Ana Reyes", "ana@x.ph", "0917 000 1111", "secret1", Role.USER))
        assertEquals("Ana Reyes", s.displayName); assertEquals("Ana Reyes", s.recipientName)
        s.signOut(); s.loginEmail = "ana@x.ph"; s.loginPassword = "secret1"
        assertTrue(s.login())
        s.selectedTour = s.tours[0]; s.bookSelectedTour()
        assertEquals("ana@x.ph", s.bookings.first().customerEmail)
        assertEquals(listOf(s.bookings.first()), s.visibleBookings)
        assertNull(s.updateProfile("Ana R. Reyes", "0917 000 2222", "Cebu City"))
        assertEquals("Cebu City", s.currentUser!!.address)
        s = reopen()
        assertEquals("Ana R. Reyes", s.db.findUser("ana@x.ph")!!.fullName)
        assertEquals("Cebu City", s.db.findUser("ana@x.ph")!!.address)
        val ana = db.findUser("ana@x.ph")!!
        s.loginAs(Role.ADMIN)
        assertNotNull(s.setUserAccountActive(adminId, false), "admins are read-only for account status")
        assertNotNull(s.setUserAccountActive(ana.id, false), "admins cannot deactivate tourist accounts")
        assertTrue(db.findUser(ana.email)!!.isActive)
        // The authentication layer still honours a disabled account set by an authorised migration.
        db.setUserActive(ana.id, false)
        s.signOut(); s.loginEmail = "ana@x.ph"; s.loginPassword = "secret1"
        assertFalse(s.login()); assertTrue(s.loginError!!.contains("disabled", true))
        db.setUserActive(ana.id, true)
        s.loginEmail = "ana@x.ph"; s.loginPassword = "secret1"
        assertTrue(s.login())
    }

    @Test fun loginAuthenticatesByStoredRoleIgnoringSelection() {
        // The selected login role no longer matters; the role is detected from the stored account.
        s.loginRole = Role.FARMER
        s.loginEmail = Role.USER.email
        s.loginPassword = Database.DEFAULT_PASSWORD
        assertTrue(s.login(), "tourist login failed: ${s.loginError}")
        assertEquals(Role.USER, s.userRole)

        s.signOut()
        s.loginEmail = adminEmail
        s.loginPassword = adminPassword
        assertTrue(s.login(), "admin login failed: ${s.loginError}")
        assertEquals(Role.ADMIN, s.userRole)
        assertEquals(Page.DASHBOARD, s.activePage)
    }

    @Test fun farmerTrashBinRestoresOriginalsAndAdminDeleteRemovesEverything() {
        val farmer = s.userAccounts.first { it.role == Role.FARMER && it.farmerId != null }
        val farmId = farmer.farmerId!!
        val farm = s.farmers.first { it.id == farmId }
        val tourIds = s.allTours.filter { it.farmerId == farmId }.map { it.id }.toSet()
        val orchardIds = s.allOrchards.filter { it.farmerId == farmId }.map { it.id }.toSet()
        val productIds = s.allProducts.filter { it.farmerId == farmId }.map { it.id }.toSet()
        val archivedBefore = s.allTours.filter { it.id in tourIds }.associate { it.id to it.archived } +
            s.allOrchards.filter { it.id in orchardIds }.associate { it.id to it.archived } +
            s.allProducts.filter { it.id in productIds }.associate { it.id to it.archived }
        val farmBookingIds = s.bookings.filter { b ->
            (b.activityType == "tour" && b.farmId in tourIds) || (b.activityType == "orchard" && b.farmId in orchardIds)
        }.map { it.id }.toSet()
        assertTrue(tourIds.isNotEmpty() || orchardIds.isNotEmpty() || productIds.isNotEmpty())

        val bookingIdsBefore = db.bookings().map { it.id }.toSet()
        val orderIdsBefore = db.orders().map { it.id }.toSet()
        s.loginAs(Role.ADMIN)
        val otherAdmin = s.userAccounts.first { it.role == Role.ADMIN }

        // The Trash Bin itself is still available for an explicitly soft-deleted account.
        assertTrue(db.softDeleteFarmerAccount(farmer.id))
        s.refreshFromDb()
        assertFalse(s.userAccounts.any { it.id == farmer.id })
        assertFalse(s.farmers.any { it.id == farmId })
        assertTrue(s.trashFarmers.any { it.user.id == farmer.id })
        assertTrue(s.trashFarmers.first { it.user.id == farmer.id }.tours.map { it.id }.containsAll(tourIds))
        assertTrue(s.tours.none { it.id in tourIds })
        assertTrue(s.fruitPicking.none { it.id in orchardIds })
        assertTrue(s.products.none { it.id in productIds })
        assertTrue(s.allTours.filter { it.id in tourIds }.all { it.archived })
        assertTrue(s.allOrchards.filter { it.id in orchardIds }.all { it.archived })
        assertTrue(s.allProducts.filter { it.id in productIds }.all { it.archived })
        if (farmBookingIds.isNotEmpty()) assertTrue(s.bookings.any { it.id in farmBookingIds })

        // Nothing is erased yet: the rows and historical records are still in the database.
        assertEquals(bookingIdsBefore, db.bookings().map { it.id }.toSet())
        assertEquals(orderIdsBefore, db.orders().map { it.id }.toSet())
        assertEquals(1, db.runSql("SELECT COUNT(*) FROM farmers WHERE name='${farm.name.replace("'", "''")}'").rows.single().single().toInt())
        // Only farmer accounts can be trashed, and admins cannot delete themselves.
        assertNotNull(s.deleteFarmerAccount(otherAdmin.id))
        assertNotNull(s.deleteFarmerAccount(s.currentUser!!.id))

        // Restoration puts back the very same rows, ids and original archive flags.
        assertNull(s.restoreFarmerAccount(farmer.id))
        assertTrue(s.userAccounts.any { it.id == farmer.id })
        assertTrue(s.farmers.any { it.id == farmId })
        assertFalse(s.trashFarmers.any { it.user.id == farmer.id })
        tourIds.forEach { id ->
            assertEquals(archivedBefore[id], s.allTours.first { it.id == id }.archived, "restored tour $id")
        }
        orchardIds.forEach { id ->
            assertEquals(archivedBefore[id], s.allOrchards.first { it.id == id }.archived, "restored orchard $id")
        }
        productIds.forEach { id ->
            assertEquals(archivedBefore[id], s.allProducts.first { it.id == id }.archived, "restored product $id")
        }
        assertEquals(bookingIdsBefore, db.bookings().map { it.id }.toSet())

        // Emptying the Trash Bin still works only for trashed accounts.
        assertNotNull(s.permanentlyDeleteFarmerAccount(farmer.id), "an active account cannot be emptied from Trash")
        assertTrue(db.softDeleteFarmerAccount(farmer.id))
        s.refreshFromDb()
        assertNull(s.permanentlyDeleteFarmerAccount(farmer.id))
        assertNotNull(s.permanentlyDeleteFarmerAccount(farmer.id), "an already deleted account cannot be emptied twice")
        assertNotNull(s.deleteFarmerAccount(farmer.id), "the account is already gone")
        assertFalse(db.allUsers().any { it.id == farmer.id })
        assertEquals(0, db.runSql("SELECT COUNT(*) FROM farmers WHERE id=$farmId").rows.single().single().toInt())
        assertTrue(s.allTours.none { it.id in tourIds })
        assertTrue(s.allOrchards.none { it.id in orchardIds })
        assertTrue(s.allProducts.none { it.id in productIds })
        assertEquals(bookingIdsBefore, db.bookings().map { it.id }.toSet())
        assertEquals(orderIdsBefore, db.orders().map { it.id }.toSet())

        val touristView = reopen().loginAs(Role.USER)
        assertTrue(touristView.tours.none { it.id in tourIds })
        assertTrue(touristView.fruitPicking.none { it.id in orchardIds })
        assertTrue(touristView.products.none { it.id in productIds })
    }

    @Test fun adminFarmerDeletionCascadesOnlyThatFarmerAndCleansUploads() {
        fun sqlText(value: String) = "'" + value.replace("'", "''") + "'"

        val targetEmail = "cascade-target-${java.util.UUID.randomUUID()}@test.invalid"
        val keepEmail = "cascade-keep-${java.util.UUID.randomUUID()}@test.invalid"
        assertNull(s.signup("Cascade Target", targetEmail, "09171110001", "secret1", Role.FARMER,
            "Target Farm", "Batangas", "Cascade", "", "Target", "1", "Main St", "Bauan", "Batangas"))
        assertNull(s.signup("Cascade Keep", keepEmail, "09171110002", "secret1", Role.FARMER,
            "Keep Farm", "Batangas", "Cascade", "", "Keep", "2", "Main St", "Bauan", "Batangas"))
        s.signOut()

        val targetUser = assertNotNull(db.findUser(targetEmail))
        val keepUser = assertNotNull(db.findUser(keepEmail))
        val targetFarmId = assertNotNull(targetUser.farmerId)
        val keepFarmId = assertNotNull(keepUser.farmerId)

        val targetTour = db.tours()[0]
        val keepTour = db.tours()[1]
        val targetOrchard = db.fruitPicking()[0]
        val keepOrchard = db.fruitPicking()[1]
        val targetProduct = db.products()[0]
        val keepProduct = db.products()[1]
        listOf(targetTour.id to targetFarmId, keepTour.id to keepFarmId).forEach { (id, farm) ->
            db.runSql("UPDATE tours SET farmer_id=$farm WHERE id=$id")
        }
        listOf(targetOrchard.id to targetFarmId, keepOrchard.id to keepFarmId).forEach { (id, farm) ->
            db.runSql("UPDATE fruit_picking SET farmer_id=$farm WHERE id=$id")
        }
        listOf(targetProduct.id to targetFarmId, keepProduct.id to keepFarmId).forEach { (id, farm) ->
            db.runSql("UPDATE products SET farmer_id=$farm WHERE id=$id")
        }

        val uploadDir = File(db.file.parentFile, "uploads").apply { mkdirs() }
        val targetOnly = File(uploadDir, "cascade-target-only-${java.util.UUID.randomUUID()}.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val shared = File(uploadDir, "cascade-shared-${java.util.UUID.randomUUID()}.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val targetUrl = "file:${targetOnly.absolutePath}"
        val sharedUrl = "file:${shared.absolutePath}"
        db.runSql("UPDATE tours SET image_url=${sqlText(targetUrl)} WHERE id=${targetTour.id}")
        db.runSql("UPDATE products SET image_url=${sqlText(sharedUrl)} WHERE id=${targetProduct.id}")
        db.runSql("UPDATE products SET image_url=${sqlText(sharedUrl)} WHERE id=${keepProduct.id}")
        db.runSql("INSERT INTO listing_images (owner_type, owner_id, url) VALUES ('tour', ${targetTour.id}, ${sqlText(targetUrl)})")
        db.runSql("INSERT INTO listing_images (owner_type, owner_id, url) VALUES ('product', ${keepProduct.id}, ${sqlText(sharedUrl)})")

        db.runSql("INSERT INTO time_slots (activity_type, activity_id, label, sort_order) VALUES ('tour', ${targetTour.id}, 'Cascade Morning', 1)")
        db.runSql("INSERT INTO time_slots (activity_type, activity_id, label, sort_order) VALUES ('orchard', ${targetOrchard.id}, 'Cascade Pick', 1)")
        db.runSql("INSERT INTO guides (farmer_id, name) VALUES ($targetFarmId, 'Cascade Guide')")
        db.runSql("INSERT INTO guides (farmer_id, name) VALUES ($keepFarmId, 'Keep Guide')")
        db.runSql("""INSERT INTO reviews (id, author, role_tag, rating, review_date, farm_or_crop, comment, target_type, target_id)
            VALUES (990201, 'Guest', 'Tourist', 5, '2026-01-01', 'Target Tour', 'Great', 'tour', ${targetTour.id})""")
        db.setFavorite(Role.USER.email, targetTour.id.toLong(), true)
        db.setFavorite(Role.USER.email, keepTour.id.toLong(), true)
        db.setCartItem(Role.USER.email, targetProduct.id, 1, 1)
        db.setCartItem(Role.USER.email, keepProduct.id, 1, 2)
        db.saveNotification(AppNotification(990202, "Target alert", "x", "x", "now", false, "system", "farmer", recipientEmail = targetEmail))

        val bookingIdsBefore = db.bookings().map { it.id }.toSet()
        val orderIdsBefore = db.orders().map { it.id }.toSet()

        s.loginAs(Role.ADMIN)
        assertNull(s.deleteFarmerAccount(targetUser.id))

        assertNull(db.allUsers().firstOrNull { it.id == targetUser.id })
        assertNotNull(db.findUser(keepEmail))
        assertTrue(db.allFarmers().none { it.id == targetFarmId })
        assertTrue(db.allFarmers().any { it.id == keepFarmId })
        assertTrue(db.tours().none { it.id == targetTour.id })
        assertTrue(db.tours().any { it.id == keepTour.id })
        assertTrue(db.fruitPicking().none { it.id == targetOrchard.id })
        assertTrue(db.fruitPicking().any { it.id == keepOrchard.id })
        assertTrue(db.products().none { it.id == targetProduct.id })
        assertTrue(db.products().any { it.id == keepProduct.id })

        assertTrue(db.listingImages("tour", targetTour.id.toLong(), true).isEmpty())
        assertTrue(db.listingImages("orchard", targetOrchard.id.toLong(), true).isEmpty())
        assertTrue(db.listingImages("product", targetProduct.id, true).isEmpty())
        assertTrue(db.listingImages("product", keepProduct.id, true).any { it.url == sharedUrl })
        assertEquals(0, db.runSql("SELECT COUNT(*) FROM time_slots WHERE activity_type='tour' AND activity_id=${targetTour.id}").rows.single().single().toInt())
        assertEquals(0, db.runSql("SELECT COUNT(*) FROM time_slots WHERE activity_type='orchard' AND activity_id=${targetOrchard.id}").rows.single().single().toInt())
        assertEquals(0, db.runSql("SELECT COUNT(*) FROM guides WHERE farmer_id=$targetFarmId").rows.single().single().toInt())
        assertEquals(1, db.runSql("SELECT COUNT(*) FROM guides WHERE farmer_id=$keepFarmId").rows.single().single().toInt())
        assertEquals(0, db.runSql("SELECT COUNT(*) FROM reviews WHERE target_type='tour' AND target_id=${targetTour.id}").rows.single().single().toInt())
        assertFalse(targetTour.id.toLong() in db.favorites(Role.USER.email))
        assertTrue(keepTour.id.toLong() in db.favorites(Role.USER.email))
        assertFalse(db.cart(Role.USER.email).any { it.first == targetProduct.id })
        assertTrue(db.cart(Role.USER.email).any { it.first == keepProduct.id })
        assertTrue(db.notifications().none { it.recipientEmail == targetEmail })

        assertEquals(bookingIdsBefore, db.bookings().map { it.id }.toSet())
        assertEquals(orderIdsBefore, db.orders().map { it.id }.toSet())
        assertFalse(targetOnly.exists(), "unreferenced upload must be deleted")
        assertTrue(shared.exists(), "upload still used by another farmer must be kept")
        shared.delete()
    }

    @Test fun touristCanDeleteOnlyTheirOwnCancelledPurchases() {
        s.loginAs(Role.USER)
        // Create a fresh pending order from the seeded cart, then cancel it.
        s.recipientName = "Test Buyer"; s.contactPhone = "09171234567"; s.deliveryAddress = "123 Test Street, Batangas"
        s.placeOrder()
        val placed = s.farmerOrders.firstOrNull { it.customerEmail.equals(s.userEmail, true) && it.fulfillmentStatus == "Pending" }
        assertNotNull(placed, "checkout should create a pending order")
        // A pending order cannot be deleted.
        assertFalse(s.canDeletePurchase(placed))
        s.cancelOrder(placed.id)
        val cancelled = s.farmerOrders.first { it.id == placed.id }
        assertEquals("Cancelled", cancelled.fulfillmentStatus)
        assertTrue(s.canDeletePurchase(cancelled))

        // A different tourist cannot delete someone else's cancelled purchase.
        val otherEmail = "other-tourist-${java.util.UUID.randomUUID()}@test.invalid"
        db.createUser("Other Tourist", otherEmail, "", Database.DEFAULT_PASSWORD, Role.USER.key)
        s.signOut(); s.loginEmail = otherEmail; s.loginPassword = Database.DEFAULT_PASSWORD
        assertTrue(s.login(), "second tourist login failed: ${s.loginError}")
        assertFalse(s.canDeletePurchase(cancelled))
        s.deletePurchase(cancelled.id)
        assertTrue(db.orders().any { it.id == cancelled.id }, "another tourist must not delete this order")

        // The owner can permanently delete their own cancelled purchase.
        s.signOut(); s.loginAs(Role.USER)
        assertTrue(s.canDeletePurchase(cancelled))
        s.deletePurchase(cancelled.id)
        assertFalse(db.orders().any { it.id == cancelled.id })
    }

    @Test fun operatingScheduleGeneratesSlotsAndPreservesBookings() {
        val tour = s.tours.first {
            it.bookingEnabled && it.farmerId != null && db.farmerAccount(it.farmerId!!) != null
        }
        s.loginAs(Role.ADMIN)
        assertNotNull(s.saveOperatingSchedule("tour", tour.id, "09:00 AM", "01:00 PM", 60), "admin is read-only for schedules")
        s.loginAsOwnerFarmerFor(tour.farmerId)

        // Auto-generate hourly slots from the configured operating hours.
        assertNull(s.saveOperatingSchedule("tour", tour.id, "09:00 AM", "01:00 PM", 60), "schedule save failed")
        assertEquals(
            listOf("09:00 AM - 10:00 AM", "10:00 AM - 11:00 AM", "11:00 AM - 12:00 PM", "12:00 PM - 01:00 PM"),
            s.slotsFor("tour", tour.id),
        )
        // Generated slots never extend past closing and never duplicate a start time.
        val starts = s.slotsFor("tour", tour.id).map { ph.agritourism.model.slotTime(it) }
        assertEquals(starts.size, starts.toSet().size)

        // A tourist books the last slot.
        s.signOut(); s.loginAs(Role.USER)
        s.selectedTour = s.tours.first { it.id == tour.id }
        s.tourDate = java.time.LocalDate.now().plusDays(3).toString()
        val available = s.availableSlotsFor("tour", tour.id)
        assertTrue(available.isNotEmpty(), "generated slots should be bookable")
        s.tourTime = available.last()
        val bookedLabel = s.tourTime
        s.bookSelectedTour()
        assertTrue(s.bookings.any { it.farmId == tour.id && it.tourTime == bookedLabel && it.customerEmail.equals(s.userEmail, true) })

        // Shrinking the hours preserves the confirmed booking's slot even though it is now past closing.
        s.signOut(); s.loginAsOwnerFarmerFor(tour.farmerId)
        assertNull(s.saveOperatingSchedule("tour", tour.id, "09:00 AM", "11:00 AM", 60), "reschedule failed")
        val after = s.slotsFor("tour", tour.id)
        assertTrue(bookedLabel in after, "booked slot must be preserved when operating hours change")
        // Newly generated slots stay within the new hours.
        after.filter { it != bookedLabel }.forEach { label ->
            val end = ph.agritourism.model.parseSlotStart(label.substringAfter(" - ").trim())
            assertTrue(end != null && !end.isAfter(java.time.LocalTime.of(11, 0)), "slot $label extends past closing")
        }
        // The confirmed booking itself is untouched.
        assertTrue(s.bookings.any { it.tourTime == bookedLabel && it.status != "Cancelled" })

        // Bad input is rejected and leaves slots unchanged.
        val before = s.slotsFor("tour", tour.id)
        assertNotNull(s.saveOperatingSchedule("tour", tour.id, "05:00 PM", "09:00 AM", 60)) // close before open
        assertNotNull(s.saveOperatingSchedule("tour", tour.id, "09:00 AM", "05:00 PM", 0))  // bad duration
        assertEquals(before, s.slotsFor("tour", tour.id))
    }

    @Test fun databaseMigratesLegacyOrderStatusesWithoutDeletingHistory() {
        val order = db.orders().first()
        val stepCount = db.runSql("SELECT COUNT(*) FROM delivery_steps WHERE order_id='${order.id}'").rows.single().single().toInt()
        db.runSql("UPDATE orders SET fulfillment_status='Out for Delivery' WHERE id='${order.id}'")
        val migrated = reopen()
        assertEquals("Confirmed", migrated.farmerOrders.first { it.id == order.id }.fulfillmentStatus)
        assertEquals(3, migrated.farmerOrders.size)
        assertEquals(stepCount, db.runSql("SELECT COUNT(*) FROM delivery_steps WHERE order_id='${order.id}'").rows.single().single().toInt())
        assertTrue(migrated.farmerOrders.all { it.fulfillmentStatus in ORDER_STATUSES })
    }

    @Test fun receivedLegacyOrdersCannotBeCancelledOrRestocked() {
        s.loginAs(Role.ADMIN)
        val received = s.farmerOrders.first { it.isReceivedByUser }
        val stock = db.products().associate { it.id to it.stock }
        assertFalse(s.canCancelOrder(received))
        assertNull(db.transitionOrderStatus(received.id, "Cancelled"))
        assertEquals("Confirmed", db.orders().first { it.id == received.id }.fulfillmentStatus)
        assertEquals(stock, db.products().associate { it.id to it.stock })
    }

    @Test fun listingPublishingIsFarmerOwnedAndAdminIsReadOnly() {
        val product = s.allProducts.first { it.farmerId != null && db.farmerAccount(it.farmerId!!) != null }
        s.loginAs(Role.USER)
        assertNotNull(s.setListingPublished("products", product.id, false))
        assertFalse(s.products.first { it.id == product.id }.archived)
        s.loginAs(Role.ADMIN)
        assertNotNull(s.setListingPublished("products", product.id, false), "admin is read-only for listings")
        assertFalse(s.products.first { it.id == product.id }.archived)

        s.loginAsOwnerFarmerFor(product.farmerId)
        assertNull(s.setListingPublished("products", product.id, false))
        assertTrue(s.products.none { it.id == product.id })
        assertTrue(s.allProducts.any { it.id == product.id && it.archived })
        assertNull(s.setListingPublished("products", product.id, true))
        assertTrue(s.products.any { it.id == product.id })

        val tour = s.allTours.first { it.farmerId != null && db.farmerAccount(it.farmerId!!) != null }
        assertNull(s.setListingPublished("tours", tour.id.toLong(), false))
        assertTrue(s.tours.none { it.id == tour.id })
        assertNull(s.setListingPublished("tours", tour.id.toLong(), true))
        assertTrue(s.tours.any { it.id == tour.id })
    }

    @Test fun adminSetupIsPrivateAndIdempotent() {
        val freshFile = File.createTempFile("agri-admin", ".db").also { it.delete() }
        val freshDb = Database(freshFile, null)
        try {
            assertEquals(0, freshDb.runSql("SELECT COUNT(*) FROM users WHERE role='admin'").rows.single().single().toInt())
            assertFailsWith<IllegalArgumentException> {
                freshDb.createUser("Public Admin", "public-admin@test.invalid", "", "secret-password", Role.ADMIN.key)
            }
        } finally {
            freshDb.close()
            listOf(freshFile, File("${freshFile.path}-wal"), File("${freshFile.path}-shm")).forEach { it.delete() }
        }

        val legacyFile = File.createTempFile("agri-legacy-admin", ".db").also { it.delete() }
        var legacyDb = Database(legacyFile, AdminSetup("legacy-admin@test.invalid", adminPassword))
        try {
            val admin = assertNotNull(legacyDb.findUser("legacy-admin@test.invalid"))
            legacyDb.conn.prepareStatement("UPDATE users SET password_hash=? WHERE id=?").use {
                it.setString(1, Database.hashPassword(Database.DEFAULT_PASSWORD))
                it.setLong(2, admin.id)
                it.executeUpdate()
            }
            legacyDb.close()
            legacyDb = Database(legacyFile, null)
            assertEquals(admin.id, legacyDb.findUser(admin.email)?.id)
            assertNull(legacyDb.authenticate(admin.email, Database.DEFAULT_PASSWORD))
            val additionalEmail = "preferred-admin@test.invalid"
            val additionalPassword = "another-secure-admin-password"
            legacyDb.close()
            legacyDb = Database(legacyFile, AdminSetup(additionalEmail, additionalPassword))
            val additionalAdmin = assertNotNull(legacyDb.findUser(additionalEmail))
            assertEquals(Role.ADMIN, additionalAdmin.role)
            assertEquals(admin.id, legacyDb.findUser(admin.email)?.id)
            assertTrue(legacyDb.authenticate(additionalEmail, additionalPassword) != null)
            val storedHash = legacyDb.runSql("SELECT password_hash FROM users WHERE lower(email)=lower('$additionalEmail')")
                .rows.single().single()
            assertNotEquals(additionalPassword, storedHash)
            legacyDb.close()
            legacyDb = Database(legacyFile, AdminSetup(additionalEmail, additionalPassword))
            assertEquals(2, legacyDb.runSql("SELECT COUNT(*) FROM users WHERE role='admin'").rows.single().single().toInt())
            val state = AppState(legacyDb, scope).also {
                it.loginEmail = additionalEmail
                it.loginPassword = additionalPassword
            }
            assertTrue(state.login())
            assertEquals(Role.ADMIN, state.userRole)
            assertEquals(Page.DASHBOARD, state.activePage)
        } finally {
            legacyDb.close()
            listOf(legacyFile, File("${legacyFile.path}-wal"), File("${legacyFile.path}-shm")).forEach { it.delete() }
        }
    }

    @Test fun transactionPaymentMethodsAreExclusive() {
        assertEquals(listOf(COD_PAYMENT_METHOD), CHECKOUT_PAYMENT_METHODS)
        assertEquals(listOf(PAY_AT_FARM_PAYMENT_METHOD), TOUR_PAYMENT_METHODS)
        assertEquals(listOf(PAY_AT_FARM_PAYMENT_METHOD), WALK_IN_PAYMENT_METHODS)
        assertTrue(s.farmerOrders.all { it.paymentMethod == COD_PAYMENT_METHOD })
        assertTrue(s.bookings.all { it.paymentMethod == PAY_AT_FARM_PAYMENT_METHOD })
        assertFailsWith<IllegalArgumentException> {
            db.saveOrder(s.farmerOrders.first().copy(paymentMethod = PAY_AT_FARM_PAYMENT_METHOD))
        }
        assertFailsWith<Database.InvalidBookingPaymentMethodException> {
            db.saveBooking(s.bookings.first().copy(paymentMethod = COD_PAYMENT_METHOD), validateSchedule = false)
        }
    }

    @Test fun referenceDataComesFromDatabase() {
        assertTrue(s.guides.isNotEmpty() && s.couriers.isNotEmpty() && s.timeSlots.isNotEmpty())
        assertEquals(70.0, s.deliveryFee)
        db.setSetting("delivery_fee", "95"); s.reloadFromDb(); assertEquals(95.0, s.deliveryFee)
        assertEquals(s.farmers.map { it.province }.distinct().sorted(), s.provinces)
        s.loginAs(Role.USER)
        assertEquals(db.findUser(Role.USER.email)!!.address, s.deliveryAddress)
        s.cart.forEach { c -> s.updateCartQty(c.product.id, -c.quantity) }
        s.addToCart(s.products.first { it.stock > 0 }); s.deliveryAddress = "12 Mabini St, Lipa City"; s.placeOrder()
        val o = assertNotNull(s.purchaseConfirmationOrder)
        assertEquals("Pending", o.fulfillmentStatus)
        assertEquals(COD_PAYMENT_METHOD, o.paymentMethod)
        assertTrue(db.orders().any { it.id == o.id })
        assertEquals("12 Mabini St, Lipa City", db.findUser(Role.USER.email)!!.address)
        // A new booking gets one of the farm's own guides.
        val t = s.tours.first { it.farmerId != null && s.guidesFor(it.farmerId).isNotEmpty() }
        s.selectedTour = t; s.tourTime = s.slotsFor("tour", t.id).first(); s.bookSelectedTour()
        assertTrue(s.bookings.first().assignedGuide in s.guidesFor(t.farmerId))
    }

    @Test fun farmSetupAndPlatformSettings() {
        // Farmers manage only their own farm's guides and time slots.
        s.loginAs(Role.FARMER)
        val mine = s.myFarmId!!; val other = s.farmers.first { it.id != mine }.id
        assertNull(s.addGuide(mine, "Ate Liza (Orchard Guide)"))
        assertTrue(db.guides().any { it.farmerId == mine && it.name == "Ate Liza (Orchard Guide)" })
        assertNotNull(s.addGuide(mine, "ate liza (orchard guide)")) // duplicate
        assertNotNull(s.addGuide(other, "Intruder"))
        val tour = s.tours.first { it.farmerId == mine }
        assertNull(s.addTimeSlot("tour", tour.id, "6:15 pm - Night Walk"))
        assertTrue("06:15 PM - Night Walk" in s.slotsFor("tour", tour.id))
        assertNotNull(s.addTimeSlot("tour", tour.id, "06:15 PM")) // same time
        assertNotNull(s.addTimeSlot("tour", tour.id, "25:00 AM"))
        assertNotNull(s.addTimeSlot("tour", s.tours.first { it.farmerId != mine }.id, "06:00 AM"))
        val slot = s.timeSlots.first { it.label == "06:15 PM - Night Walk" }
        s.removeTimeSlot(slot); assertFalse("06:15 PM - Night Walk" in s.slotsFor("tour", tour.id))
        assertNotNull(s.saveSetting("delivery_fee", "1")); assertNotNull(s.addCourier(Courier(0, "X", "Y", "09171234567", "", "")))
        assertFalse(s.canAccess(Page.PLATFORM_SETTINGS)); assertTrue(s.canAccess(Page.FARM_SETUP))
        // Admins can open platform settings but cannot mutate them.
        s.loginAs(Role.ADMIN)
        assertTrue(s.canAccess(Page.PLATFORM_SETTINGS))
        val fee = db.settings()["delivery_fee"]
        val courierCount = s.couriers.size
        assertNotNull(s.saveSetting("delivery_fee", "abc"))
        assertNotNull(s.saveSetting("delivery_fee", "85"))
        assertEquals(fee, db.settings()["delivery_fee"])
        assertNotNull(s.addCourier(Courier(0, "FastFarm", "Kuya Ben", "0917 123 4567", "Motorbike", "Tanauan Hub")))
        assertEquals(courierCount, s.couriers.size)
        s.removeCourier(s.couriers.first())
        assertEquals(courierCount, s.couriers.size)
    }

    @Test fun checkoutOnlySelectedCartItems() {
        s.loginAs(Role.USER)
        s.cart.forEach { c -> s.updateCartQty(c.product.id, -c.quantity) }
        val (p1, p2) = s.products.filter { it.stock > 0 }.take(2)
        s.addToCart(p1); s.addToCart(p2)
        s.toggleCartItem(p2.id)
        assertEquals(listOf(p1.id), s.checkoutItems.map { it.product.id })
        s.deliveryAddress = "Somewhere"; s.placeOrder()
        assertEquals(listOf(p1.name), s.farmerOrders.first().items.map { it.productName })
        assertEquals(listOf(p2.id), s.cart.map { it.product.id })
        assertEquals(listOf(p2.id), db.cart(Role.USER.email).map { it.first })
        s.selectAllCart(false); s.placeOrder(); assertEquals(1, s.checkoutStep)
    }

    @Test fun orderValidationAndStatusSync() {
        s.loginAs(Role.USER)
        s.cart.forEach { c -> s.updateCartQty(c.product.id, -c.quantity) }
        val before = s.farmerOrders.size
        s.placeOrder(); assertEquals(before, s.farmerOrders.size)
        s.addToCart(s.products.first { it.stock > 0 }); s.deliveryAddress = ""
        s.placeOrder(); assertEquals(before, s.farmerOrders.size); assertEquals(2, s.checkoutStep)
        s.deliveryAddress = "Somewhere"; s.paymentMethod = "Bank Transfer"; s.placeOrder()
        assertEquals(before, s.farmerOrders.size)
        assertEquals("Cash on Delivery", s.paymentMethod)
        val product = s.cart.first().product
        val stockBefore = db.products().first { it.id == product.id }.stock
        s.placeOrder()
        val o = s.farmerOrders.first()
        assertEquals("COD", o.paymentStatus); assertEquals("Pending", o.fulfillmentStatus)
        assertNull(o.trackingNumber); assertNull(o.courierName); assertTrue(o.deliverySteps.isEmpty())
        val orderedItem = o.items.single()
        assertEquals(product.imageUrl, orderedItem.productImageUrl)
        assertEquals(s.farmers.firstOrNull { it.id == product.farmerId }?.farmName ?: product.farmOrigin, orderedItem.sellerName)
        assertTrue(s.roleFilteredNotifications.any { it.type == "order" && it.title == "Purchase Successful!" && it.orderId == o.id })
        assertTrue(s.roleFilteredNotifications.any { it.eventKey == "order:${o.id}:purchased" && it.orderId == o.id })
        val purchaseNotification = s.roleFilteredNotifications.first { it.eventKey == "order:${o.id}:purchased" }
        s.openNotification(purchaseNotification)
        assertTrue(db.notifications().first { it.eventKey == "order:${o.id}:purchased" }.read)
        assertEquals(stockBefore - o.items.sumOf { it.qty }, db.products().first { it.id == product.id }.stock)
        s.loginAs(Role.ADMIN)
        assertNotNull(s.orderStatusUpdate(o.id, "Confirmed"))
        assertEquals("Pending", s.farmerOrders.first { it.id == o.id }.fulfillmentStatus) // admin is read-only
        s.loginAsOwnerFarmerForOrder(o.id)
        s.orderStatusUpdate(o.id, "Dispatched")
        assertEquals("Pending", s.farmerOrders.first { it.id == o.id }.fulfillmentStatus)
        s.orderStatusUpdate(o.id, "Confirmed")
        val confirmedEvents = db.notifications().count { it.eventKey == "order:${o.id}:confirmed" }
        s.orderStatusUpdate(o.id, "Confirmed")
        assertEquals(confirmedEvents, db.notifications().count { it.eventKey == "order:${o.id}:confirmed" })
        assertEquals("Confirmed", s.farmerOrders.first { it.id == o.id }.fulfillmentStatus)
        s.loginAs(Role.USER)
        assertTrue(s.roleFilteredNotifications.any { it.type == "order" && it.title.contains("Confirmed") })
        s.loginAsOwnerFarmerForOrder(o.id)
        assertFalse(s.canCancelOrder(s.farmerOrders.first { it.id == o.id })) // confirmed orders are not cancellable
        s.cancelOrder(o.id)
        assertEquals("Confirmed", s.farmerOrders.first { it.id == o.id }.fulfillmentStatus)
        val stockAfterConfirmed = db.products().first { it.id == product.id }.stock

        // A separate pending order can be cancelled by the farm, notifying the tourist and restoring stock.
        s.loginAs(Role.USER)
        s.addToCart(product); s.deliveryAddress = "Somewhere"; s.placeOrder()
        val pending = s.farmerOrders.first { it.id != o.id && it.fulfillmentStatus == "Pending" }
        assertEquals(stockAfterConfirmed - 1, db.products().first { it.id == product.id }.stock)
        s.loginAsOwnerFarmerForOrder(pending.id)
        s.cancelOrder(pending.id)
        assertEquals("Cancelled", s.farmerOrders.first { it.id == pending.id }.fulfillmentStatus)
        assertEquals(1, db.notifications().count { it.eventKey == "order:${pending.id}:cancelled" && it.targetRole == "tourist" })
        s.loginAs(Role.USER)
        assertTrue(s.roleFilteredNotifications.any { it.eventKey == "order:${pending.id}:cancelled" })
        s.loginAsOwnerFarmerForOrder(pending.id)
        assertEquals(stockAfterConfirmed, db.products().first { it.id == product.id }.stock)
        s.cancelOrder(pending.id)
        assertEquals(stockAfterConfirmed, db.products().first { it.id == product.id }.stock)
    }

    @Test fun farmerOrderActionsAreFarmScopedAndNotifyTheBuyer() {
        val farmId = assertNotNull(db.findUser(Role.FARMER.email)?.farmerId)
        val product = assertNotNull(s.products.firstOrNull { it.farmerId == farmId && it.stock >= 2 })

        fun buyPendingOrder(): ph.agritourism.model.FarmerOrder {
            if (s.isLoggedIn) s.signOut()
            s.loginAs(Role.USER)
            s.cart.toList().forEach { s.updateCartQty(it.product.id, -it.quantity) }
            s.addToCart(s.products.first { it.id == product.id })
            s.deliveryAddress = "Farm order test address"
            val before = db.orders().map { it.id }.toSet()
            s.placeOrder()
            return assertNotNull(s.farmerOrders.firstOrNull { it.id !in before && it.customerEmail.equals(Role.USER.email, true) })
        }

        val confirmed = buyPendingOrder()
        s.loginAs(Role.FARMER)
        assertTrue(s.visibleOrders.any { it.id == confirmed.id })
        assertTrue(s.canCancelOrder(confirmed))
        s.orderStatusUpdate(confirmed.id, "Confirmed")
        val confirmedOrder = s.farmerOrders.first { it.id == confirmed.id }
        assertEquals("Confirmed", confirmedOrder.fulfillmentStatus)
        assertFalse(s.canCancelOrder(confirmedOrder), "farmers may only cancel pending orders")
        val confirmedStock = db.products().first { it.id == product.id }.stock
        s.cancelOrder(confirmed.id)
        assertEquals("Confirmed", db.orders().first { it.id == confirmed.id }.fulfillmentStatus)
        assertNull(db.transitionOrderStatus(confirmed.id, "Cancelled"), "the database default must reject confirmed cancellation")
        assertEquals(confirmedStock, db.products().first { it.id == product.id }.stock)

        val pending = buyPendingOrder()
        s.loginAs(Role.FARMER)
        assertTrue(s.canCancelOrder(s.farmerOrders.first { it.id == pending.id }))
        val reservedStock = db.products().first { it.id == product.id }.stock
        s.cancelOrder(pending.id)
        assertEquals("Cancelled", db.orders().first { it.id == pending.id }.fulfillmentStatus)
        assertEquals(reservedStock + pending.items.sumOf { it.qty }, db.products().first { it.id == product.id }.stock)
        assertEquals(1, db.notifications().count {
            it.eventKey == "order:${pending.id}:cancelled" && it.targetRole == "tourist" &&
                it.recipientEmail.equals(Role.USER.email, true)
        })

        s.loginAs(Role.USER)
        assertTrue(s.roleFilteredNotifications.any { it.eventKey == "order:${confirmed.id}:confirmed" })
        assertTrue(s.roleFilteredNotifications.any { it.eventKey == "order:${pending.id}:cancelled" })
        assertEquals(1, db.notifications().count { it.eventKey == "order:${confirmed.id}:confirmed" && it.recipientEmail.equals(Role.USER.email, true) })
    }

    @Test fun customersCanUpdateOnlyTheirPendingOrderAddressAndCannotPrintReceipts() {
        val farmId = assertNotNull(db.findUser(Role.FARMER.email)?.farmerId)
        val product = assertNotNull(s.products.firstOrNull { it.farmerId == farmId && it.stock > 0 })
        s.loginAs(Role.USER)
        s.cart.toList().forEach { s.updateCartQty(it.product.id, -it.quantity) }
        s.addToCart(product)
        s.deliveryAddress = "12 Existing Address, Manila"
        val orderIds = db.orders().map { it.id }.toSet()
        s.placeOrder()
        val order = assertNotNull(s.farmerOrders.firstOrNull { it.id !in orderIds })

        assertTrue(s.canUpdateOrderAddress(order))
        assertNull(db.updatePendingOrderAddress(order.id, "other-account@test.invalid", "99 Other Street"))
        s.updateOrderAddress(order.id, "45 Updated Street, Manila")
        assertEquals("45 Updated Street, Manila", db.orders().first { it.id == order.id }.customerAddress)
        s.printOrderReceipt(order)
        assertNull(s.selectedReceipt, "tourists must not print receipts")

        s.loginAs(Role.FARMER)
        s.printOrderReceipt(s.farmerOrders.first { it.id == order.id })
        assertNotNull(s.selectedReceipt, "the owner farmer retains receipt printing")
        s.selectedReceipt = null
        s.orderStatusUpdate(order.id, "Confirmed")
        assertNull(db.updatePendingOrderAddress(order.id, Role.USER.email, "99 Too Late Street"))

        s.loginAs(Role.USER)
        val confirmed = s.farmerOrders.first { it.id == order.id }
        assertFalse(s.canUpdateOrderAddress(confirmed))
        assertFalse(s.canCancelOrder(confirmed))
        s.updateOrderAddress(order.id, "99 Too Late Street")
        assertEquals("45 Updated Street, Manila", db.orders().first { it.id == order.id }.customerAddress)
    }

    @Test fun farmerOtherActivitiesAndAdminCatalogManagementUseExistingModels() {
        s.loginAs(Role.FARMER)
        val farm = assertNotNull(s.myFarm)
        val farmerActivity = AppState.ActivityDraft(
            name = "Sunset Orchard Walk",
            price = "400",
            description = "A guided sunset walk through the orchard and planting area.",
            image = farm.farmBannerUrl,
            slot = "08:00 AM - Sunset Walk",
            hours = "7:00 AM - 5:00 PM",
            activities = "Orchard walk, Harvest demonstration",
            experienceType = "other_activity",
            location = farm.location,
        )
        assertNull(s.createTour(farmerActivity))
        val publishedActivity = s.allTours.first { it.name == farmerActivity.name }
        assertFalse(publishedActivity.archived)
        assertEquals("other_activity", publishedActivity.experienceType)
        assertEquals(farm.id, publishedActivity.farmerId)

        val orchardDraft = AppState.ActivityDraft(
            name = "Sunrise Mango Picking",
            price = "350",
            description = "Pick seasonal mangoes with a host and learn about orchard care.",
            image = farm.farmBannerUrl,
            slot = "08:00 AM - Mango Picking",
            fruit = "Carabao mango",
            season = "March-June",
            location = farm.location,
        )
        assertNull(s.createOrchard(orchardDraft))
        assertFalse(s.allOrchards.first { it.title == orchardDraft.name }.archived)

        val adminFarm = s.farmers.last()
        s.loginAs(Role.ADMIN)
        s.adminListingFarmId = adminFarm.id
        val adminActivity = farmerActivity.copy(name = "Admin Created Farm Activity", farmerId = adminFarm.id)
        assertNotNull(s.createTour(adminActivity), "admin cannot create tours")
        assertNull(s.allTours.firstOrNull { it.name == adminActivity.name })

        val adminOrchard = orchardDraft.copy(name = "Admin Mango Picking", farmerId = adminFarm.id)
        assertNotNull(s.createOrchard(adminOrchard), "admin cannot create orchards")
        assertNull(s.allOrchards.firstOrNull { it.title == adminOrchard.name })

        s.openAddProduct()
        assertFalse(s.isAddProductModalOpen, "admin cannot open the product editor")
        s.newProductName = "Admin Listed Organic Mangoes"
        s.newProductPrice = "120"
        s.newProductStock = "8"
        s.newProductUnit = "kg"
        s.saveProduct()
        assertNull(s.allProducts.firstOrNull { it.name == "Admin Listed Organic Mangoes" })
    }

    @Test fun staleInventoryCannotCreateAnOrderOrLoseTheCart() {
        s.loginAs(Role.USER)
        s.cart.forEach { c -> s.updateCartQty(c.product.id, -c.quantity) }
        val product = s.products.first { it.stock > 0 }
        s.addToCart(product)
        s.deliveryAddress = "Somewhere"
        val orderCount = db.orders().size
        db.updateStock(product.id, 0)
        s.placeOrder()
        assertEquals(orderCount, db.orders().size)
        assertEquals(0, db.products().first { it.id == product.id }.stock)
        assertTrue(s.cart.any { it.product.id == product.id })
        assertNull(s.purchaseConfirmationOrder)
    }

    @Test fun deletingBookingRemovesItsNotifications() {
        assertTrue(s.notifications.any { it.bookingId == 101L })
        s.loginAs(Role.ADMIN); s.deleteBooking(101L)
        assertTrue(db.notifications().any { it.bookingId == 101L }) // admin is read-only
        s.loginAsBookingTourist(101L); s.deleteBooking(101L)
        assertTrue(reopen().notifications.none { it.bookingId == 101L })
    }

    @Test fun chartsUseDatabaseValues() {
        s.loginAs(Role.ADMIN)
        val bookingsTotal = s.monthlyBookings.sumOf { it.value }
        assertEquals(s.bookings.size.toDouble(), bookingsTotal) // all seeded dates fall in the 6-month window
        val revenue = s.monthlyRevenue.sumOf { it.value }
        assertEquals(s.farmerOrders.sumOf { it.totalAmount } + s.bookings.filter { it.status != "Cancelled" }.sumOf { it.totalAmount }, revenue, 0.01)
        s.loginAs(Role.USER); s.selectedTour = s.tours[0]; s.tourGuests = 2; s.bookSelectedTour()
        assertEquals(bookingsTotal + 1, s.monthlyBookings.sumOf { it.value })
        assertEquals(6, s.monthlyBookings.size)
    }

    @Test fun registeredUserGraphUsesPersistedCreationDatesAcrossRoles() {
        val today = java.time.LocalDate.now()
        val originalAccounts = db.users()
        db.conn.prepareStatement("UPDATE users SET created_at=? WHERE id=?").use { statement ->
            originalAccounts.forEachIndexed { index, account ->
                val date = today.minusMonths((index % 6).toLong()).withDayOfMonth(1)
                statement.setString(1, "$date 12:00:00")
                statement.setLong(2, account.id)
                statement.executeUpdate()
            }
        }
        s.reloadFromDb()
        val dates = db.users().mapNotNull { ph.agritourism.ui.parseTourDate(it.createdAt.take(10)) }
        val graph = s.registeredUsersOverTime
        assertEquals(6, graph.size)
        assertEquals(dates.size.toDouble(), graph.last().value)
        val firstMonth = java.time.YearMonth.now().minusMonths(5)
        assertEquals(
            dates.count { !java.time.YearMonth.from(it).isAfter(firstMonth) }.toDouble(),
            graph.first().value,
        )

        assertNotNull(db.createUser("Graph Tourist", "graph-${java.util.UUID.randomUUID()}@test.invalid", "", "secret1", Role.USER.key))
        s.reloadFromDb()
        assertEquals(db.userCount().toDouble(), s.registeredUsersOverTime.last().value)
    }

    @Test fun farmerMonthlyGraphUsesOnlyFarmOrdersAndBookings() {
        s.loginAs(Role.FARMER)
        val farmId = assertNotNull(s.myFarmId)
        val ownedProducts = db.products().filter { it.farmerId == farmId }.map { it.id }.toSet()
        val ownedTours = db.tours().filter { it.farmerId == farmId }.map { it.id }.toSet()
        val ownedOrchards = db.fruitPicking().filter { it.farmerId == farmId }.map { it.id }.toSet()
        val orderEntries = db.orders().filter { it.fulfillmentStatus != "Cancelled" }.mapNotNull { order ->
            ph.agritourism.ui.parseOrderDate(order.orderDate)?.let { date ->
                date to order.items.filter { it.productId in ownedProducts }.sumOf { it.qty * it.unitPrice }
            }
        }
        val bookingEntries = db.bookings().filter { booking ->
            booking.status != "Cancelled" &&
                (booking.activityType == "tour" && booking.farmId in ownedTours ||
                    booking.activityType == "orchard" && booking.farmId in ownedOrchards)
        }.mapNotNull { booking -> ph.agritourism.ui.parseTourDate(booking.tourDate)?.let { it to booking.totalAmount.toDouble() } }
        assertEquals(ph.agritourism.ui.monthlySeries(orderEntries + bookingEntries), s.monthlyRevenue)
        assertTrue(s.monthlyRevenue.sumOf { it.value } <= s.visibleOrders.sumOf(s::farmShare) + s.activeBookings.sumOf { it.totalAmount })

        val pendingOrder = s.visibleOrders.firstOrNull { it.fulfillmentStatus == "Pending" }
        if (pendingOrder != null) {
            val before = s.monthlyRevenue.sumOf { it.value }
            s.cancelOrder(pendingOrder.id)
            assertTrue(s.monthlyRevenue.sumOf { it.value } <= before)
            assertEquals("Cancelled", db.orders().first { it.id == pendingOrder.id }.fulfillmentStatus)
        }
    }

    @Test fun roleBasedAccess() {
        // Signed out: only public pages; actions redirect to login.
        assertFalse(s.canAccess(Page.DASHBOARD))
        assertFalse(Page.entries.any { it.name == "SQL_STUDIO" })
        s.navigate(Page.DASHBOARD); assertEquals(Page.LOGIN, s.activePage)
        listOf(Page.FARM_DETAIL, Page.FRUIT_PICKING, Page.MARKET, Page.FARMERS_STORIES,
            Page.ADMIN_TOURS, Page.ADMIN_FRUIT_PICKING, Page.ADMIN_PRODUCTS).forEach { assertFalse(s.canAccess(it), "guest $it") }
        // Guests get a picture & description preview instead of being sent to the login page.
        s.activePage = Page.HOME
        s.openCatalogTour(s.tours[0]); assertEquals(Page.HOME, s.activePage); assertFalse(s.isTourModalOpen)
        assertEquals(s.tours[0].description, s.catalogPreview?.description); s.catalogPreview = null
        s.bookingAction(102L, "Confirmed"); assertEquals("Pending", s.bookings.first { it.id == 102L }.status)

        // Tourist: own data only, no staff actions or admin pages.
        s.loginAs(Role.USER)
        assertTrue(s.visibleBookings.all { it.customerEmail == Role.USER.email })
        assertTrue(s.visibleOrders.isNotEmpty() && s.visibleOrders.all { it.customerEmail == Role.USER.email })
        assertTrue(s.roleFilteredNotifications.none { it.targetRole == "farmer" })
        listOf(Page.FARMER_ORDERS, Page.FARMER_PRODUCTS, Page.ADMIN_TOURS, Page.ADMIN_FRUIT_PICKING,
            Page.ADMIN_PRODUCTS).forEach { assertFalse(s.canAccess(it), "$it") }
        s.navigate(Page.PLATFORM_SETTINGS); assertEquals(Page.DASHBOARD, s.activePage)
        s.navigate(Page.ADMIN_TOURS); assertEquals(Page.DASHBOARD, s.activePage)
        s.bookingAction(102L, "Confirmed"); assertEquals("Pending", s.bookings.first { it.id == 102L }.status)
        s.checkInBooking(103L); assertNotEquals("Arrived & Checked In", s.bookings.first { it.id == 103L }.attendanceStatus)
        s.cancelBooking(102L); assertEquals("Pending", s.bookings.first { it.id == 102L }.status) // someone else's booking
        s.deleteBooking(102L); assertNotNull(s.bookings.firstOrNull { it.id == 102L })
        val other = s.farmerOrders.first { it.customerEmail != Role.USER.email }
        val otherStatus = other.fulfillmentStatus
        s.orderStatusUpdate(other.id, "Confirmed")
        assertEquals(otherStatus, s.farmerOrders.first { it.id == other.id }.fulfillmentStatus)
        s.newProductName = "Hack"; s.saveProduct(); assertTrue(s.products.none { it.name == "Hack" })
        assertFails { s.runAdminSql("DELETE FROM users") }

        // Farmer: manages bookings/orders but cannot buy, book, or open admin pages.
        s.loginAs(Role.FARMER)
        assertFalse(s.canAccess(Page.PLATFORM_SETTINGS)); assertFalse(s.canAccess(Page.MARKET)); assertFalse(s.canAccess(Page.CART_CHECKOUT))
        assertFalse(s.canAccess(Page.ADMIN_TOURS)); assertFalse(s.canAccess(Page.ADMIN_FRUIT_PICKING)); assertFalse(s.canAccess(Page.ADMIN_PRODUCTS))
        val cartBefore = db.cart(Role.FARMER.email)
        s.addToCart(s.products.first { it.stock > 0 }); assertEquals(cartBefore, db.cart(Role.FARMER.email))
        val n = s.bookings.size; s.bookSelectedTour(); assertEquals(n, s.bookings.size)
        s.attendanceConfirmation(101L, true); assertNotEquals("Confirmed Attending", s.bookings.first { it.id == 101L }.attendanceStatus)
        s.bookingAction(102L, "Confirmed"); assertEquals("Confirmed", s.bookings.first { it.id == 102L }.status)
        // The tourist who owns booking 102 is notified; others are not.
        assertTrue(s.notifications.first().recipientEmail == "james.smith@outlook.com")
        s.loginAs(Role.USER); assertTrue(s.roleFilteredNotifications.none { it.bookingId == 102L })

        // Admin sees monitoring tables and platform settings, but no tourist browsing, buying or farmer pages.
        s.loginAs(Role.ADMIN)
        val adminDenied = setOf(
            Page.FARM_DETAIL, Page.FRUIT_PICKING, Page.MARKET, Page.FARMERS_STORIES,
            Page.WISHLIST, Page.MY_PURCHASES, Page.CART_CHECKOUT,
            Page.FARMER_ORDERS, Page.FARMER_PRODUCTS, Page.FARMER_PROFILE, Page.FARM_SETUP, Page.HELP,
        )
        Page.entries.forEach { assertEquals(it !in adminDenied, s.canAccess(it), "$it") }
        s.runAdminSql("UPDATE products SET stock=0 WHERE id=101")
        assertEquals(0, s.products.first { it.id == 101L }.stock)

        // Signing out clears the session's private data.
        s.signOut(); assertTrue(s.cart.isEmpty() && s.favoriteIds.isEmpty() && s.visibleBookings.isEmpty())
    }

    @Test fun noDoubleBookings() {
        s.loginAs(Role.USER)
        val TOUR_TIME_SLOTS = s.slotsFor("tour", s.tours[0].id)
        assertTrue(TOUR_TIME_SLOTS.size >= 2 && s.slotsFor("tour", s.tours[2].id) == TOUR_TIME_SLOTS)
        s.selectedTour = s.tours[0]; s.tourTime = TOUR_TIME_SLOTS[0]; s.bookSelectedTour()
        val n = s.bookings.size
        // Same tourist, same date + slot (even at a different farm) is rejected.
        s.selectedTour = s.tours[2]; s.bookSelectedTour(); assertEquals(n, s.bookings.size)
        s.selectedFruit = s.fruitPicking[0]; s.fruitDate = s.tourDate; s.fruitTime = "08:00 AM - Early"; s.bookSelectedFruit()
        assertEquals(n, s.bookings.size)
        // Different slot is fine; rescheduling into an occupied slot is rejected.
        s.tourTime = TOUR_TIME_SLOTS[1]; s.bookSelectedTour(); assertEquals(n + 1, s.bookings.size)
        val second = s.bookings.first()
        s.editingBooking = second.copy(tourTime = "08:00 AM"); s.saveEditedBooking()
        assertEquals(TOUR_TIME_SLOTS[1], s.bookings.first { it.id == second.id }.tourTime)
        // Cancelling frees the slot.
        s.cancelBooking(s.bookings.first { it.tourTime == TOUR_TIME_SLOTS[0] && it.customerEmail == Role.USER.email }.id)
        s.selectedTour = s.tours[2]; s.tourTime = TOUR_TIME_SLOTS[0]; s.bookSelectedTour(); assertEquals(n + 2, s.bookings.size)
        // The database itself refuses a duplicate even if the app check is bypassed.
        val dup = s.bookings.first().copy(id = 999_999L, bookingCode = "BK-DUP")
        assertFailsWith<Database.DoubleBookingException> { db.saveBooking(dup) }
        assertFails { db.runSql("INSERT INTO bookings (id, booking_code, status, slot_key) VALUES (999998, 'X', 'Confirmed', '${dup.slotKey()}')") }
        // A different customer can take the same slot.
        s.loginAs(Role.FARMER); s.newBookingCustomer = "Walk In"; s.newBookingPhone = "0917 222 3333"; s.newBookingDate = s.tourDate; s.newBookingTime = TOUR_TIME_SLOTS[0]
        s.saveFarmerBooking(); assertEquals("Walk In", s.bookings.first().customerName)
    }

    @Test fun newBookingDatesStayWithinTheDynamicWindowAndHistoricalDatesRemainStored() {
        val today = java.time.LocalDate.now()
        val window = ph.agritourism.model.BookingDateWindow.current(today)
        val tour = s.tours.first { it.bookingEnabled && s.availableSlotsFor("tour", it.id).isNotEmpty() }
        val slot = s.availableSlotsFor("tour", tour.id).first()
        val historical = db.bookings().firstOrNull { ph.agritourism.ui.parseTourDate(it.tourDate)?.isBefore(today) == true }
        val originalCount = db.bookings().size
        val idBase = System.currentTimeMillis() + 1_000_000L
        fun booking(date: java.time.LocalDate, suffix: String) = Booking(
            id = idBase + suffix.hashCode().toLong(),
            bookingCode = "DATE-$suffix",
            farmId = tour.id,
            farmName = tour.name,
            customerName = "Date Test $suffix",
            customerEmail = "date-$suffix@test.invalid",
            activity = tour.name,
            tourDate = date.toString(),
            tourTime = slot,
            participants = 1,
            totalAmount = tour.pricePerPerson,
            paymentMethod = PAY_AT_FARM_PAYMENT_METHOD,
            status = "Confirmed",
        )

        assertFailsWith<Database.InvalidBookingDateException> { db.saveBooking(booking(today.minusDays(1), "past")) }
        assertFailsWith<Database.InvalidBookingDateException> { db.saveBooking(booking(window.last.plusDays(1), "future")) }
        db.saveBooking(booking(today, "today"))
        db.saveBooking(booking(window.last, "last"))
        assertEquals(originalCount + 2, db.bookings().size)
        historical?.let { assertTrue(db.bookings().any { saved -> saved.id == it.id && saved.tourDate == it.tourDate }) }
    }

    @Test fun bookingTimesMustFitOperatingHoursAndExperienceDuration() {
        s.loginAs(Role.USER)
        val tour = s.tours.first()
        val closingTime = "05:00 PM - Closing Time"
        val afterHours = "06:00 PM - After Hours"
        db.addTimeSlot("tour", tour.id, closingTime)
        db.addTimeSlot("tour", tour.id, afterHours)
        s.timeSlots = db.timeSlots()
        assertTrue("04:00 PM - Sunset Overlook" in s.availableSlotsFor("tour", tour.id))
        assertFalse(closingTime in s.availableSlotsFor("tour", tour.id))
        assertFalse(afterHours in s.availableSlotsFor("tour", tour.id))

        s.selectedTour = tour
        s.tourDate = java.time.LocalDate.now().plusDays(8).toString()
        s.tourTime = afterHours
        val beforeTour = s.bookings.size
        s.bookSelectedTour()
        assertEquals(beforeTour, s.bookings.size)
        assertTrue(s.toastMessage.orEmpty().contains("outside the farm's available tour schedule"))

        val invalid = s.bookings.first { it.activityType == "tour" }.copy(
            id = 999_991L,
            bookingCode = "BK-INVALID-TIME",
            farmId = tour.id,
            farmName = tour.name,
            activity = tour.name,
            tourDate = s.tourDate,
            tourTime = closingTime,
            customerName = "Schedule Test",
            customerEmail = "schedule-test@x.ph",
        )
        assertFailsWith<Database.InvalidBookingScheduleException> { db.saveBooking(invalid) }

        val orchard = s.fruitPicking.first()
        assertFalse("04:00 PM - Golden Hour Farm Harvest" in s.availableSlotsFor("orchard", orchard.id))

        s.loginAs(Role.FARMER)
        val walkInTour = s.myTours.first()
        db.addTimeSlot("tour", walkInTour.id, afterHours)
        s.timeSlots = db.timeSlots()
        s.newBookingActivity = walkInTour.name
        s.newBookingDate = java.time.LocalDate.now().plusDays(8).toString()
        s.newBookingCustomer = "Walk-in Schedule Test"
        s.newBookingPhone = "0917 333 4444"
        s.newBookingTime = afterHours
        val beforeWalkIn = s.bookings.size
        s.saveFarmerBooking()
        assertEquals(beforeWalkIn, s.bookings.size)
        assertTrue(s.toastMessage.orEmpty().contains("outside the farm's available tour schedule"))
    }

    @Test fun touristReviews() {
        s.assignSeedBookingOwnerToFarmer(101L)
        s.loginAs(Role.USER)
        val seeded = s.bookings.first { it.id == 101L }
        db.saveBooking(seeded.copy(tourDate = java.time.LocalDate.now().plusDays(2).toString()), validateSchedule = false)
        s.bookings = db.bookings()
        val b = s.bookings.first { it.id == seeded.id }
        assertFalse(s.canReview(b)) // visit hasn't happened yet
        assertNotNull(s.submitReview(b.id, 5, "Wonderful farm visit!"))
        s.loginAsOwnerFarmerForBooking(b.id); s.checkInBooking(b.id)
        s.loginAs(Role.ADMIN)
        assertNotNull(s.submitReview(b.id, 5, "Admins cannot review")) // not a tourist
        s.loginAs(Role.USER)
        assertTrue(s.canReview(s.bookings.first { it.id == b.id }))
        assertNotNull(s.submitReview(b.id, 0, "Bad rating value here")); assertNotNull(s.submitReview(b.id, 4, "short"))
        assertNotNull(s.submitReview(103L, 4, "Not my booking at all")) // someone else's booking
        val target = b.activityType to b.farmId
        val before = if (target.first == "tour") s.tours.first { it.id == target.second }.reviewsCount else s.fruitPicking.first { it.id == target.second }.reviewsCount
        assertNull(s.submitReview(b.id, 4, "Loved picking oranges with the kids!"))
        assertNotNull(s.submitReview(b.id, 5, "Trying to review a second time")) // once per booking
        val s2 = reopen().loginAs(Role.USER)
        val r = s2.reviewForBooking(b.id)!!
        assertEquals(4, r.rating); assertEquals(Role.USER.email, r.userEmail)
        assertTrue(r in s2.reviewsFor(target.first, target.second))
        val after = if (target.first == "tour") s2.tours.first { it.id == target.second }.reviewsCount else s2.fruitPicking.first { it.id == target.second }.reviewsCount
        assertEquals(before + 1, after)
        assertFalse(s2.canReview(s2.bookings.first { it.id == b.id }))
        assertFails { db.runSql("INSERT INTO reviews (id, author, booking_id) VALUES (9999, 'x', ${b.id})") } // DB enforces one per booking
    }

    @Test fun farmersOnlyManageTheirOwnFarm() {
        s.loginAs(Role.FARMER)
        val juanFarm = s.myFarmId!!
        assertEquals(setOf(102L, 104L), s.visibleBookings.map { it.id }.toSet()) // orchard 1 + tour 1
        assertTrue(s.farmProducts.isNotEmpty() && s.farmProducts.all { it.farmerId == juanFarm })
        assertTrue(s.visibleOrders.isNotEmpty() && s.visibleOrders.all { juanFarm in s.farmsOf(it) })
        assertTrue(s.myTours.all { it.farmerId == juanFarm } && s.myTours.isNotEmpty())
        s.checkInBooking(103L); assertNotEquals("Arrived & Checked In", s.bookings.first { it.id == 103L }.attendanceStatus)
        val foreignOrder = s.farmerOrders.first { juanFarm !in s.farmsOf(it) }
        s.orderStatusUpdate(foreignOrder.id, "Confirmed"); assertEquals(foreignOrder.fulfillmentStatus, s.farmerOrders.first { it.id == foreignOrder.id }.fulfillmentStatus)
        s.newProductName = "Juan Kamote"; s.saveProduct()
        assertEquals(juanFarm, s.products.first { it.name == "Juan Kamote" }.farmerId)

        // A newly registered farmer gets their own empty farm.
        s.signOut()
        assertNotNull(s.signup("Ka Pedro", "pedro@farm.ph", "0917 111 2222", "secret1", Role.FARMER)) // farm details required
        assertNull(s.signup("Ka Pedro", "pedro@farm.ph", "0917 111 2222", "secret1", Role.FARMER, "Pedro Rice Farm", "Nueva Ecija"))
        assertEquals("Pedro Rice Farm", s.myFarm?.farmName)
        assertTrue(s.visibleBookings.isEmpty() && s.visibleOrders.isEmpty() && s.farmProducts.isEmpty() && s.myTours.isEmpty())
        s.newProductName = "Pedro Rice"; s.saveProduct()
        assertEquals(listOf("Pedro Rice"), s.farmProducts.map { it.name })
        s.loginAs(Role.FARMER); assertTrue(s.farmProducts.none { it.name == "Pedro Rice" })

        // A tourist buying Pedro's rice notifies Pedro, not Juan.
        s.loginAs(Role.USER)
        s.cart.forEach { c -> s.updateCartQty(c.product.id, -c.quantity) }
        s.addToCart(s.products.first { it.name == "Pedro Rice" }); s.deliveryAddress = "Manila"; s.placeOrder()
        assertEquals("pedro@farm.ph", s.notifications.first().recipientEmail)
        s.loginAs(Role.FARMER); assertTrue(s.roleFilteredNotifications.none { it.recipientEmail == "pedro@farm.ph" })
    }

    @Test fun farmerAddsToursStoryAndPhotos() {
        s.loginAs(Role.FARMER)
        val farm = s.myFarm!!
        val tours0 = s.tours.size; val orchards0 = s.fruitPicking.size
        assertNotNull(s.createTour(AppState.ActivityDraft("", "300", "A lovely walk around the farm", "", "08:00 AM"))) // name required
        assertNotNull(s.createTour(AppState.ActivityDraft("Walk", "300", "A lovely walk around the farm", "", "25:00 AM"))) // bad slot
        assertNull(s.createTour(AppState.ActivityDraft("Sunrise Walk", "300", "A lovely walk around the farm", "", "07:30 am - Sunrise")))
        val tour = s.tours.last()
        assertEquals(tours0 + 1, s.tours.size); assertEquals(farm.id, tour.farmerId); assertEquals(listOf("07:30 AM - Sunrise"), s.slotsFor("tour", tour.id))
        assertTrue(tour in s.myTours); assertFalse(tour.archived)
        assertNotNull(s.createOrchard(AppState.ActivityDraft("Mango Picking", "250", "Pick sweet mangoes from the tree", "", "09:00 AM"))) // fruit required
        assertNull(s.createOrchard(AppState.ActivityDraft("Mango Picking", "250", "Pick sweet mangoes from the tree", "", "09:00 AM", fruit = "Mango")))
        assertEquals(orchards0 + 1, s.fruitPicking.size); assertEquals(farm.id, s.fruitPicking.last().farmerId)
        assertFalse(s.fruitPicking.last().archived)

        assertNull(s.saveFarmStory(farm.id, "Farming is family.", "Three generations of rice and mango growers."))
        assertNotNull(s.saveFarmStory(farm.id + 1, "x", "y")) // not my farm
        assertNotNull(s.saveFarmStory(farm.id, "x".repeat(300), "y"))

        val img = File.createTempFile("pic", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        s.changeAvatar(img)
        assertTrue(s.avatar.startsWith("file:")); assertEquals(s.avatar, s.myFarm!!.photoUrl)

        val r = reopen()
        assertEquals("Farming is family.", r.farmers.first { it.id == farm.id }.storyQuote)
        assertTrue(r.tours.any { it.name == "Sunrise Walk" && it.farmerId == farm.id })
        assertTrue(r.farmers.first { it.id == farm.id }.photoUrl.startsWith("file:"))
        img.delete()
    }

    @Test fun touristCanChangePhotoAndFaqsAreSeeded() {
        assertTrue(s.faqs.isNotEmpty())
        assertTrue(s.farmerOrders.none { it.uploadedReceiptUrl.contains("receipt.png") })
        s.loginAs(Role.USER)
        val before = s.avatar
        val img = File.createTempFile("pic", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        s.changeAvatar(img)
        assertNotEquals(before, s.avatar); assertEquals(s.avatar, db.findUser(Role.USER.email)!!.avatarUrl)
        img.delete()
    }

    @Test fun farmerProductCrud() {
        s.loginAs(Role.FARMER)
        val p = s.farmProducts.first()
        val other = s.products.first { it.farmerId != s.myFarmId }
        assertFalse(s.canEditProduct(other))
        s.openEditProduct(other); assertFalse(s.isAddProductModalOpen)
        s.openEditProduct(p)
        s.newProductDesc = "Picked at dawn, edited description."; s.newProductDiscount = "15"; s.saveProduct()
        val saved = db.products().first { it.id == p.id }
        assertEquals("Picked at dawn, edited description.", saved.description); assertEquals(15, saved.discountPercent)
        assertEquals(p.rating, saved.rating)
        s.openEditProduct(p); s.newProductDiscount = "95"; s.saveProduct()
        assertEquals(15, db.products().first { it.id == p.id }.discountPercent) // rejected
        s.restockProduct(p.id, 10); assertEquals(saved.stock + 10, db.products().first { it.id == p.id }.stock)
        // Deleting hides it from the market and carts but keeps it for old orders.
        s.loginAs(Role.USER); s.addToCart(s.products.first { it.id == p.id })
        s.loginAs(Role.FARMER); s.deleteProduct(p.id)
        assertTrue(s.products.none { it.id == p.id }); assertTrue(s.allProducts.any { it.id == p.id && it.archived })
        assertTrue(db.cart(Role.USER.email).none { it.first == p.id })
        val s2 = reopen().loginAs(Role.USER)
        assertTrue(s2.products.none { it.id == p.id }); assertTrue(s2.cart.none { it.product.id == p.id })
    }

    @Test fun farmerTourAndOrchardCrud() {
        s.loginAs(Role.FARMER)
        val mine = s.myFarmId!!
        val t = s.tours.first { it.farmerId == mine }
        val other = s.tours.first { it.farmerId != mine }
        val d = AppState.ActivityDraft("Edited Farm Walk", "420", "A brand new description for the walk.", "", "", "7:00 AM - 4:00 PM", "Planting, Harvest")
        assertNotNull(s.updateTour(other.id, d))
        assertNull(s.updateTour(t.id, d))
        val e = db.tours().first { it.id == t.id }
        assertEquals("Edited Farm Walk", e.name); assertEquals(420, e.pricePerPerson); assertEquals(listOf("Planting", "Harvest"), e.activities)
        assertEquals(t.rating, e.rating); assertEquals(mine, e.farmerId)
        val o = s.fruitPicking.first { it.farmerId == mine }
        assertNull(s.updateOrchard(o.id, d.copy(fruit = "Rambutan", season = "Aug - Oct")))
        assertEquals("Rambutan", db.fruitPicking().first { it.id == o.id }.fruitName)
        // A booking on the tour stays visible to the farm after the tour is deleted.
        val booked = s.visibleBookings.firstOrNull { it.activityType == "tour" && it.farmId == t.id }
        s.deleteTour(other.id); assertTrue(s.tours.any { it.id == other.id }) // not theirs
        s.deleteTour(t.id); s.deleteOrchard(o.id)
        assertTrue(s.tours.none { it.id == t.id }); assertTrue(s.fruitPicking.none { it.id == o.id })
        booked?.let { b -> assertTrue(s.visibleBookings.any { it.id == b.id }) }
        val s2 = reopen()
        assertTrue(s2.tours.none { it.id == t.id }); assertTrue(s2.allTours.any { it.id == t.id })
    }

    @Test fun touristEditsAndDeletesReview() {
        s.assignSeedBookingOwnerToFarmer(101L)
        s.loginAsOwnerFarmerForBooking(101L); s.checkInBooking(101L)
        s.loginAs(Role.USER)
        val b = s.bookings.first { it.id == 101L }
        fun count() = if (b.activityType == "tour") s.allTours.first { it.id == b.farmId }.reviewsCount else s.allOrchards.first { it.id == b.farmId }.reviewsCount
        val before = count()
        assertNull(s.submitReview(b.id, 2, "It was okay, a bit hot outside."))
        val r = s.reviewForBooking(b.id)!!
        assertNull(s.updateReview(r.id, 5, "Actually it was wonderful, loved it!"))
        assertEquals(5, db.reviews().first { it.id == r.id }.rating)
        s.loginAs(Role.FARMER); assertFalse(s.canDeleteReview(s.reviews.first { it.id == r.id }))
        s.loginAs(Role.USER); s.deleteReview(r.id)
        assertNull(s.reviewForBooking(b.id)); assertEquals(before, count())
        assertTrue(s.canReview(s.bookings.first { it.id == b.id })) // can review again
    }

    @Test fun ordersCanBeCancelled() {
        s.loginAs(Role.USER)
        s.cart.forEach { c -> s.updateCartQty(c.product.id, -c.quantity) }
        val p0 = s.products.first { it.stock > 2 }
        db.updateStock(p0.id, 20); s.allProducts = db.products()
        val p = s.products.first { it.id == p0.id }
        s.addToCart(p); s.addToCart(p); s.deliveryAddress = "Somewhere"; s.placeOrder()
        val o = s.farmerOrders.first()
        assertEquals(p.stock - 2, s.products.first { it.id == p.id }.stock)
        assertTrue(s.canCancelOrder(o)); s.cancelOrder(o.id)
        assertEquals("Cancelled", db.orders().first { it.id == o.id }.fulfillmentStatus)
        assertEquals(p.stock, db.products().first { it.id == p.id }.stock)
        assertFalse(s.canCancelOrder(s.farmerOrders.first { it.id == o.id }))
        // Tourists can't cancel confirmed orders and nobody except the owning tourist can delete one.
        s.addToCart(p); s.placeOrder()
        val o2 = s.farmerOrders.first { it.id != o.id }
        s.loginAsOwnerFarmerForOrder(o2.id); s.orderStatusUpdate(o2.id, "Confirmed")
        s.loginAs(Role.USER); assertFalse(s.canCancelOrder(s.farmerOrders.first { it.id == o2.id }))
        s.deleteOrder(o2.id); assertTrue(s.farmerOrders.any { it.id == o2.id })

        s.loginAs(Role.USER)
        s.addToCart(p); s.placeOrder()
        val o3 = s.farmerOrders.first { it.id != o.id && it.id != o2.id }
        s.cancelOrder(o3.id)
        assertEquals("Cancelled", db.orders().first { it.id == o3.id }.fulfillmentStatus)
        s.deleteOrder(o3.id)
        assertTrue(db.orders().none { it.id == o3.id })
        s.loginAs(Role.ADMIN)
        assertFalse(db.orders().none { it.id == o2.id }) // admin record remains untouched
    }

    @Test fun farmerFullCrudOnProductsToursAndOrchards() {
        s.loginAs(Role.USER)
        val mineFarm = db.findUser(Role.FARMER.email)!!.farmerId!!
        val p = s.products.first { it.farmerId == mineFarm && it.stock > 0 }
        s.addToCart(p)
        s.loginAs(Role.FARMER)
        val other = s.products.first { it.farmerId != mineFarm }
        // Update with discount
        s.openEditProduct(p); s.newProductDesc = "Edited description"; s.newProductDiscount = "15"; s.saveProduct()
        val edited = db.products().first { it.id == p.id }
        assertEquals("Edited description", edited.description); assertEquals(15, edited.discountPercent)
        s.newProductDiscount = "95"; s.openEditProduct(edited); s.newProductDiscount = "95"; s.saveProduct()
        assertEquals(15, db.products().first { it.id == p.id }.discountPercent) // rejected
        s.isAddProductModalOpen = false; s.editingProductId = null
        // Restock and delete; other farms' products are off limits
        s.restockProduct(p.id, 10); assertEquals(edited.stock + 10, db.products().first { it.id == p.id }.stock)
        s.deleteProduct(other.id); assertTrue(s.products.any { it.id == other.id })
        s.deleteProduct(p.id)
        assertTrue(s.products.none { it.id == p.id }); assertTrue(db.cart(Role.USER.email).none { it.first == p.id })
        // Tours: edit and delete own, not others
        val tour = s.tours.first { it.farmerId == mineFarm }
        val otherTour = s.tours.first { it.farmerId != mineFarm }
        val d = AppState.ActivityDraft("Renamed Tour", "777", "A brand new description", "", "", hours = "9:00 AM - 4:00 PM", activities = "Walk, Swim")
        assertNotNull(s.updateTour(otherTour.id, d))
        assertNull(s.updateTour(tour.id, d))
        val t2 = db.tours().first { it.id == tour.id }
        assertEquals("Renamed Tour", t2.name); assertEquals(777, t2.pricePerPerson); assertEquals(listOf("Walk", "Swim"), t2.activities)
        s.deleteTour(tour.id)
        assertTrue(s.tours.none { it.id == tour.id }); assertTrue(db.tours().first { it.id == tour.id }.archived)
        // Orchards
        val orch = s.fruitPicking.first { it.farmerId == mineFarm }
        assertNull(s.updateOrchard(orch.id, AppState.ActivityDraft("New Orchard Name", "250", "Fresh picking all day", "", "", fruit = "Mango")))
        assertEquals("Mango", db.fruitPicking().first { it.id == orch.id }.fruitName)
        s.deleteOrchard(orch.id); assertTrue(s.fruitPicking.none { it.id == orch.id })
        // Survives a restart
        val s2 = reopen().loginAs(Role.FARMER)
        assertTrue(s2.products.none { it.id == p.id }); assertTrue(s2.tours.none { it.id == tour.id })
    }

    @Test fun touristEditsAndDeletesReviewAndCancelsOrder() {
        s.assignSeedBookingOwnerToFarmer(101L)
        s.loginAsOwnerFarmerForBooking(101L); s.checkInBooking(101L)
        s.loginAs(Role.USER)
        val b = s.bookings.first { it.id == 101L }
        assertNull(s.submitReview(b.id, 2, "It was okay, a bit hot."))
        val r = s.reviewForBooking(b.id)!!
        assertNull(s.updateReview(r.id, 5, "Actually it was wonderful!"))
        assertEquals(5, db.reviews().first { it.id == r.id }.rating)
        val count = { if (b.activityType == "tour") db.tours().first { it.id == b.farmId }.reviewsCount else db.fruitPicking().first { it.id == b.farmId }.reviewsCount }
        val before = count()
        s.deleteReview(r.id)
        assertNull(s.reviewForBooking(b.id)); assertEquals(before - 1, count())
        // Order cancel returns stock
        s.cart.forEach { c -> s.updateCartQty(c.product.id, -c.quantity) }
        val p = s.products.first { it.stock > 2 }
        s.addToCart(p); s.addToCart(p); s.deliveryAddress = "Somewhere"; s.placeOrder()
        val o = s.farmerOrders.first()
        assertEquals(p.stock - 2, db.products().first { it.id == p.id }.stock)
        assertTrue(s.canCancelOrder(o)); s.cancelOrder(o.id)
        assertEquals("Cancelled", db.orders().first { it.id == o.id }.fulfillmentStatus)
        assertEquals(p.stock, db.products().first { it.id == p.id }.stock)
        assertFalse(s.canCancelOrder(s.farmerOrders.first { it.id == o.id }))
        // The owning tourist can permanently delete only their cancelled purchase; admins cannot.
        s.deleteOrder(o.id); assertTrue(db.orders().none { it.id == o.id })
    }

    @Test fun phoneValidationAndStructuredAccountFieldsPersist() {
        listOf("", "0917", "9175551234x", "12345678901").forEachIndexed { i, bad ->
            assertNotNull(s.signup("Bad $i", "bad$i@x.ph", bad, "secret1", Role.USER), "phone '$bad' must be rejected")
        }
        assertNull(s.signup("Valid Person", "valid@x.ph", "+63 917 555 0111", "secret1", Role.USER,
            firstName = "Valid", middleName = "Q", lastName = "Person",
            houseNo = "12", street = "Main St", municipality = "Bauan", city = "Batangas"))
        val created = db.findUser("valid@x.ph")!!
        assertEquals("+63 917 555 0111", created.phone)
        assertEquals("09175550111", phoneDigits(created.phone))
        assertEquals("Valid Q Person", created.fullName)
        assertEquals("Valid", created.firstName)
        assertEquals("Person", created.lastName)
        assertEquals("12, Main St, Bauan, Batangas", created.address)

        assertNotNull(s.updateProfile("Valid Q Person", "0917", null, null,
            "Valid", "Q", "Person", "12", "Main St", "Bauan", "Batangas"))
        assertEquals("+63 917 555 0111", db.findUser("valid@x.ph")!!.phone, "invalid phone must not overwrite the saved number")
        assertNull(s.updateProfile("Valid Q Person", "0917 222 3333", null, null,
            "Valid", "Q", "Person", "12", "Main St", "Bauan", "Batangas"))
        assertEquals("0917 222 3333", db.findUser("valid@x.ph")!!.phone)
    }

    @Test fun farmCapacityAndAvailabilityBlockOverbooking() {
        s.loginAs(Role.FARMER)
        val farmId = s.myFarmId!!
        val tour = s.myTours.first()
        val used = db.confirmedPaxForFarm(farmId)
        assertNull(s.saveFarmCapacity((used + 2).toString()))
        assertEquals(used + 2, s.capacityFor(farmId))
        assertEquals(AvailabilityStatus.AVAILABLE, s.availabilityFor(tour))

        s.newBookingActivity = tour.name
        s.newBookingCustomer = "Capacity One"
        s.newBookingPhone = "0917 444 5555"
        s.newBookingGuests = 2
        s.newBookingTime = s.availableSlotsFor("tour", tour.id).firstOrNull().orEmpty()
        s.saveFarmerBooking()
        assertNotNull(s.bookings.firstOrNull { it.customerName == "Capacity One" && it.participants == 2 })
        assertEquals(0, s.availablePaxFor(farmId))
        assertEquals(AvailabilityStatus.FULL, s.availabilityFor(tour))

        s.newBookingCustomer = "Capacity Two"
        s.newBookingPhone = "0917 444 6666"
        s.newBookingGuests = 1
        s.newBookingTime = s.availableSlotsFor("tour", tour.id).firstOrNull().orEmpty()
        s.saveFarmerBooking()
        assertNull(s.bookings.firstOrNull { it.customerName == "Capacity Two" }, "a full farm must refuse another booking")
        assertTrue(s.toastMessage.orEmpty().contains("slot(s) are available"))

        val error = s.saveFarmCapacity(used.toString())
        assertNotNull(error, "capacity cannot be lowered below confirmed pax")
        assertTrue(error!!.contains("confirmed pax"))
        assertEquals(used + 2, s.capacityFor(farmId))
    }

    @Test fun galleryUploadsAreCappedAtFiveAndSurviveRestart() {
        val files = List(7) { i ->
            File.createTempFile("gallery-$i-", ".jpg").apply { writeBytes(byteArrayOf(i.toByte(), 1, 2, 3)) }
        }
        try {
            val urls = s.storeUploads(files, 2)
            assertEquals(Database.MAX_IMAGES_PER_LISTING - 2, urls.size)
            val tour = s.tours.first()
            assertNull(s.saveListingImages("tour", tour.id.toLong(), urls))
            assertEquals(urls.toSet(), db.listingImages("tour", tour.id.toLong()).map { it.url }.toSet())

            val tooMany = (1..(Database.MAX_IMAGES_PER_LISTING + 1)).map { "file:rejected-$it.jpg" }
            val error = s.saveListingImages("tour", tour.id.toLong(), tooMany)
            assertNotNull(error)
            assertTrue(error!!.contains("at most ${Database.MAX_IMAGES_PER_LISTING}"))
            assertEquals(urls.toSet(), db.listingImages("tour", tour.id.toLong()).map { it.url }.toSet(),
                "a rejected gallery must not be partially applied")

            val restored = reopen()
            assertEquals(urls.toSet(), restored.db.listingImages("tour", tour.id.toLong()).map { it.url }.toSet())
            urls.forEach { File(it.removePrefix("file:")).delete() }
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test fun legacyDatabaseIsBackedUpAndMigratedWithoutLosingRecords() {
        val backupDir = File(file.absoluteFile.parentFile, "backups")
        val backupPrefix = "${file.nameWithoutExtension}-before-v2-"
        val backupsBefore = backupDir.listFiles()?.count { it.name.startsWith(backupPrefix) && it.extension == "db" } ?: 0
        val email = Role.USER.email
        val nameBefore = db.findUser(email)!!.fullName
        val bookingIds = db.bookings().map { it.id }.toSet()
        val orderIds = db.orders().map { it.id }.toSet()

        db.runSql("DELETE FROM settings WHERE key='${Database.MIGRATION_V2_MARKER}'")
        listOf("first_name", "last_name", "middle_name", "house_no", "street", "municipality", "city", "deleted_at")
            .forEach { db.runSql("ALTER TABLE users DROP COLUMN $it") }
        listOf("capacity", "deleted_at").forEach { db.runSql("ALTER TABLE farmers DROP COLUMN $it") }
        listOf("tours", "fruit_picking", "products").forEach { table ->
            listOf("deleted_at", "archived_before_delete").forEach { db.runSql("ALTER TABLE $table DROP COLUMN $it") }
        }
        db.close()

        val migrated = Database(file, adminSetup)
        db = migrated
        s = AppState(db, scope)
        val backupsAfter = backupDir.listFiles()?.count { it.name.startsWith(backupPrefix) && it.extension == "db" } ?: 0
        assertEquals(backupsBefore + 1, backupsAfter, "a pre-migration backup must be written exactly once")
        assertEquals("complete", db.runSql("SELECT value FROM settings WHERE key='${Database.MIGRATION_V2_MARKER}'").rows.single().single())
        assertEquals(nameBefore, db.findUser(email)!!.fullName)
        assertTrue(db.findUser(email)!!.firstName.isNotBlank())
        assertEquals(bookingIds, db.bookings().map { it.id }.toSet())
        assertEquals(orderIds, db.orders().map { it.id }.toSet())
    }
}
