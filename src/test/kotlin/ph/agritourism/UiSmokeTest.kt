package ph.agritourism

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ph.agritourism.data.AdminSetup
import ph.agritourism.data.Database
import ph.agritourism.model.Role
import ph.agritourism.ui.dialogs.DateField
import ph.agritourism.ui.screens.LoginPage
import ph.agritourism.ui.*
import java.io.File
import kotlin.test.*

/** Renders every page and overlay for every role headlessly to catch composition crashes. */
@OptIn(ExperimentalTestApi::class)
class UiSmokeTest {
    private val file = File.createTempFile("agri-ui", ".db").also { it.delete() }
    private val adminEmail = "admin-${java.util.UUID.randomUUID()}@test.invalid"
    private val adminPassword = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: Database

    @BeforeTest fun setUp() { db = Database(file, AdminSetup(adminEmail, adminPassword)) }
    @AfterTest fun tearDown() { scope.cancel(); db.close(); file.delete() }

    private fun AppState.loginAs(role: Role) {
        loginRole = role
        loginEmail = if (role == Role.ADMIN) adminEmail else role.email
        loginPassword = if (role == Role.ADMIN) adminPassword else Database.DEFAULT_PASSWORD
        assertTrue(login(), "login as $role failed: $loginError")
    }

    @Test fun loginPageHasNoRoleSelector() = runComposeUiTest {
        val s = AppState(db, scope)
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { LoginPage(s) } }
        // Login now auto-detects the role from the stored account, so no role picker is shown.
        onAllNodesWithText("Tourist").assertCountEquals(0)
        onAllNodesWithText("Farmer").assertCountEquals(0)
        onAllNodesWithText("Administrator").assertCountEquals(0)
    }

    private fun renderAll(role: Role?) = runComposeUiTest {
        val s = AppState(db, scope)
        if (role != null) s.loginAs(role)
        // Image shimmer animates forever, so drive the clock manually instead of waiting for idle.
        mainClock.autoAdvance = false
        fun step(action: () -> Unit) { runOnUiThread(action); mainClock.advanceTimeBy(100) }
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        mainClock.advanceTimeBy(100)
        for (p in Page.entries) {
            step { s.activePage = p }
        }
        if (s.isTourist) {
            ShowcaseCategory.entries.forEach { c -> step { s.openShowcase(c) } }
            step { s.activePage = Page.CART_CHECKOUT }
            for (n in 1..4) step { s.checkoutStep = n }
            step { s.checkoutStep = 1; s.isTourModalOpen = true }
            step { s.isTourModalOpen = false; s.isFruitModalOpen = true }
            step { s.isFruitModalOpen = false }
        }
        val b = s.visibleBookings.firstOrNull()
        val o = s.visibleOrders.firstOrNull()
        val overlays: List<() -> Unit> = listOfNotNull(
            { s.isNotificationsOpen = true },
            { s.roleFilteredNotifications.firstOrNull()?.let(s::openNotification) },
            { s.isProfileModalOpen = true },
            { s.isMockupDrawerOpen = true },
            { s.selectedFarmerForModal = s.farmers.first() },
            if (s.isStaff) ({ s.isAddProductModalOpen = true }) else null,
            s.farmProducts.firstOrNull()?.let { p -> { s.openEditProduct(p) } },
            s.myTours.firstOrNull()?.takeIf { s.isStaff }?.let { t -> { s.editingTour = t } },
            s.myOrchards.firstOrNull()?.takeIf { s.isStaff }?.let { o -> { s.editingOrchard = o } },
            { s.confirm("Delete?", "Sure?") {} },
            if (s.isStaff) ({ s.isAddBookingModalOpen = true }) else null,
            b?.let { { s.selectedBookingForView = it } },
            b?.let { { s.selectedBookingForTicket = it } },
            b?.let { { s.editingBooking = it } },
            b?.let { { s.reviewingBooking = it } },
            b?.let { { s.deletingBookingId = it.id } },
            b?.let { { s.printTourReceipt(it) } },
            o?.let { { s.selectedOrderForView = it } },
            o?.let { { s.printOrderReceipt(it) } },
            { s.catalogPreview = CatalogPreview(s.tours[0].imageUrl, "Tag", s.tours[0].name, s.tours[0].description, listOf("Price" to "1")) },
            if (s.isFarmer) ({ s.isAddTourModalOpen = true }) else null,
            if (s.isFarmer) ({ s.isAddOrchardModalOpen = true }) else null,
            if (s.isFarmer) ({ s.isEditStoryOpen = true }) else null,
        )
        overlays.forEach { open ->
            step { open() }
            step {
                s.isNotificationsOpen = false; s.selectedNotification = null; s.isProfileModalOpen = false
                s.isMockupDrawerOpen = false; s.selectedFarmerForModal = null; s.isAddProductModalOpen = false
                s.isAddBookingModalOpen = false; s.selectedBookingForView = null; s.selectedBookingForTicket = null
                s.editingBooking = null; s.reviewingBooking = null; s.deletingBookingId = null
                s.selectedOrderForView = null; s.selectedReceipt = null
                s.catalogPreview = null; s.isAddTourModalOpen = false; s.isAddOrchardModalOpen = false; s.isEditStoryOpen = false
            }
        }
        if (role != null) { step { s.signOut() } }
    }

    @Test fun guestScreensRender() = renderAll(null)
    @Test fun touristScreensRender() = renderAll(Role.USER)
    @Test fun farmerScreensRender() = renderAll(Role.FARMER)
    @Test fun adminScreensRender() = renderAll(Role.ADMIN)

    @Test fun loginPageRendersPublicRoleSelectorWithoutAdmin() = runComposeUiTest {
        val s = AppState(db, scope)
        s.activePage = Page.LOGIN
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("Welcome Back").assertCountEquals(1)
        onAllNodesWithText("Log in as").assertCountEquals(0)
        onAllNodesWithText("Administrator").assertCountEquals(0)
        onAllNodesWithText("TIP:").assertCountEquals(0)
    }

    @Test fun myPurchasesPageShowsTheCurrentTouristsOrders() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.USER) }
        s.activePage = Page.MY_PURCHASES
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("My Purchases").assertCountEquals(2)
        onAllNodesWithText("Marketplace orders placed by your account, with saved order details and current status.").assertCountEquals(1)
        assertTrue(s.visibleOrders.isNotEmpty())
        val item = s.visibleOrders.first().items.first()
        onNodeWithContentDescription(item.productName).assertExists()
    }

    @Test fun touristDashboardShowsCatalogCategories() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.USER) }
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("Fresh Produce from Local Farms").assertCountEquals(1)
        onAllNodesWithText("Tree-to-Basket Fruit Orchards").assertCountEquals(1)
    }

    @Test fun adminDashboardShowsStatsGraphAndUserInformationOnly() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.ADMIN) }
        val adminId = db.users().first { it.role == Role.ADMIN && it.email == adminEmail }.id
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("Platform Overview").assertCountEquals(1)
        onAllNodesWithText("Read-only monitoring of live agritourism records").assertCountEquals(1)
        onAllNodesWithText("Bookings Overview").assertCountEquals(1)
        onAllNodesWithText("Registered Users Over Time").assertCountEquals(1)
        onAllNodesWithText("User Information").assertCountEquals(1)
        onAllNodesWithText("User Accounts").assertCountEquals(0)
        listOf("Tour Name", "Farmer Name", "Farm Name", "Farm Location", "Availability", "Ratings", "Stock / Availability").forEach {
            onAllNodesWithText(it).assertCountEquals(0)
        }
        onAllNodesWithText("SQLite").assertCountEquals(0)
        // The footer legitimately shows the signed-in admin's own email; the account table must not list admins.
        onAllNodesWithText("Platform Manager · $adminEmail").assertCountEquals(0)
        onAllNodesWithTag("account-row-$adminId").assertCountEquals(0)
        onAllNodesWithText("Account Management").assertCountEquals(0)
        onAllNodesWithText("Catalog Management").assertCountEquals(0)
        assertEquals(db.users().count { it.createdAt.isNotBlank() }.toDouble(), s.registeredUsersOverTime.last().value)
    }

    @Test fun adminSidebarHasSeparateManagementPagesAndBottomLogout() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.ADMIN) }
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        mainClock.advanceTimeBy(100)
        onAllNodesWithText("Logout").assertCountEquals(0)
        onAllNodesWithText("Sign Out").assertCountEquals(0)
        onAllNodesWithText("Help & FAQs").assertCountEquals(0)
        onAllNodesWithText("FAQs").assertCountEquals(0)
        onNodeWithContentDescription("Log Out").assertExists()
        onNodeWithTag("admin-logout").assertExists()
        onNodeWithText("Tour").performClick()
        mainClock.advanceTimeBy(100)
        assertEquals(Page.ADMIN_TOURS, s.activePage)
        onNodeWithTag("admin-logout").performClick()
        mainClock.advanceTimeBy(100)
        assertFalse(s.isLoggedIn)
        assertEquals(Page.HOME, s.activePage)
    }

    @Test fun touristSidebarProfileIsClickableAndLogoutIsIconOnly() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.USER) }
        s.activePage = Page.DASHBOARD
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        mainClock.advanceTimeBy(100)

        onAllNodesWithText("Sign Out").assertCountEquals(0)
        onAllNodesWithText("Logout").assertCountEquals(0)
        onNodeWithContentDescription("Log Out").assertExists()
        onNodeWithTag("sidebar-logout").assertExists()

        onNodeWithTag("tourist-profile").performClick()
        mainClock.advanceTimeBy(300)
        onAllNodesWithText("Profile Information").assertCountEquals(1)
        onAllNodesWithText("Save Changes").assertCountEquals(1)
        val original = s.currentUser!!
        onNodeWithTag("profile-name").performTextClearance()
        onNodeWithTag("profile-name").performTextInput("Maria Updated")
        onNodeWithTag("profile-phone").performTextClearance()
        onNodeWithTag("profile-phone").performTextInput("0917 111 3333")
        onNodeWithTag("profile-location").performTextClearance()
        onNodeWithTag("profile-location").performTextInput("Cebu City")
        onNodeWithText("Save Changes").performClick()
        mainClock.advanceTimeBy(300)

        val saved = db.findUser(original.email)!!
        assertEquals("Maria Updated", saved.fullName)
        assertEquals("0917 111 3333", saved.phone)
        assertEquals("Cebu City", saved.address)
        assertEquals("Maria Updated", s.displayName)
        assertEquals("Cebu City", s.currentUser!!.address)
        assertFalse(s.isProfileModalOpen)

        onNodeWithTag("sidebar-logout").performClick()
        mainClock.advanceTimeBy(100)
        assertFalse(s.isLoggedIn)
        assertEquals(Page.HOME, s.activePage)
    }

    private fun assertProfileAndLogoutShareBottomRow(profileTag: String, logoutTag: String) = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(if (profileTag == "tourist-profile") Role.USER else Role.ADMIN) }
        s.activePage = Page.DASHBOARD
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        mainClock.advanceTimeBy(300)
        onAllNodesWithText("Log Out").assertCountEquals(0)
        onAllNodesWithText("Logout").assertCountEquals(0)
        onAllNodesWithText("Sign Out").assertCountEquals(0)
        onNodeWithContentDescription("Log Out").assertExists()
        val profile = onNodeWithTag(profileTag).fetchSemanticsNode().boundsInRoot
        val logout = onNodeWithTag(logoutTag).fetchSemanticsNode().boundsInRoot
        assertTrue(profile.right <= logout.left, "profile must be left of logout: $profile / $logout")
        assertTrue(kotlin.math.abs(profile.center.y - logout.center.y) < 5f,
            "profile and logout must share one row: $profile / $logout")
        onNodeWithTag(logoutTag).performClick()
        mainClock.advanceTimeBy(100)
        assertFalse(s.isLoggedIn)
        assertEquals(Page.HOME, s.activePage)
    }

    @Test fun adminProfileAndLogoutShareBottomRow() =
        assertProfileAndLogoutShareBottomRow("sidebar-profile", "admin-logout")

    @Test fun touristProfileAndLogoutShareBottomRow() =
        assertProfileAndLogoutShareBottomRow("tourist-profile", "sidebar-logout")

    @Test fun touristEmailEditPersistsAcrossLogoutAndLogin() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.USER) }
        val originalEmail = s.userEmail
        val ownedOrdersBefore = db.orders().count { it.customerEmail.equals(originalEmail, true) }
        val newEmail = "tourist.updated.${java.util.UUID.randomUUID().toString().take(6)}@example.ph"
        s.activePage = Page.DASHBOARD
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        mainClock.advanceTimeBy(300)
        onNodeWithTag("tourist-profile").performClick()
        mainClock.advanceTimeBy(300)
        onNodeWithTag("profile-email").performTextClearance()
        onNodeWithTag("profile-email").performTextInput(newEmail)
        onNodeWithTag("profile-name").performTextClearance()
        onNodeWithTag("profile-name").performTextInput("Ana Updated")
        onNodeWithText("Save Changes").performClick()
        mainClock.advanceTimeBy(300)

        assertNull(db.findUser(originalEmail))
        val saved = assertNotNull(db.findUser(newEmail))
        assertEquals("Ana Updated", saved.fullName)
        assertEquals(newEmail, s.userEmail)
        assertEquals(ownedOrdersBefore, db.orders().count { it.customerEmail.equals(newEmail, true) })
        assertEquals(0, db.orders().count { it.customerEmail.equals(originalEmail, true) })

        onNodeWithTag("sidebar-logout").performClick()
        mainClock.advanceTimeBy(100)
        assertFalse(s.isLoggedIn)
        s.loginEmail = newEmail
        s.loginPassword = Database.DEFAULT_PASSWORD
        assertTrue(s.login(), s.loginError ?: "login with the updated email failed")
        assertEquals("Ana Updated", s.displayName)
        s.activePage = Page.DASHBOARD
        mainClock.advanceTimeBy(300)
        onNodeWithTag("tourist-profile").performClick()
        mainClock.advanceTimeBy(300)
        onNodeWithTag("profile-email").assertTextEquals(newEmail)
        onNodeWithTag("profile-name").assertTextEquals("Ana Updated")
        onNodeWithTag("profile-location").assertTextEquals(saved.address)
    }

    @Test fun adminManagementPagesShowOnlyTheirOwnTextTableColumns() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.ADMIN) }
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }

        fun show(page: Page, headers: List<String>, forbidden: List<String>) {
            runOnUiThread { s.activePage = page }
            mainClock.advanceTimeBy(1000) // let the AnimatedContent page transition finish
            headers.forEach { onAllNodesWithText(it).assertCountEquals(1) }
            forbidden.forEach { onAllNodesWithText(it).assertCountEquals(0) }
        }

        show(
            Page.ADMIN_TOURS,
            listOf("Tour Name", "Farmer Name", "Farm Name", "Farm Location", "Description", "Availability", "Ratings", "Status"),
            listOf("Activity Name", "Product Name", "Price", "Stock / Availability"),
        )
        show(
            Page.ADMIN_FRUIT_PICKING,
            listOf("Activity Name", "Farmer Name", "Farm Name", "Farm Location", "Description", "Availability", "Ratings", "Status"),
            listOf("Tour Name", "Product Name", "Price", "Stock / Availability"),
        )
        show(
            Page.ADMIN_PRODUCTS,
            listOf("Product Name", "Farmer Name", "Farm Name", "Farm Location", "Description", "Price", "Stock / Availability", "Ratings", "Status"),
            listOf("Tour Name", "Activity Name", "Availability"),
        )
    }

    @Test fun adminTablesUseStoredRatingsAndStatusFields() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.ADMIN) }
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }

        fun render(page: Page) {
            runOnUiThread { s.activePage = page }
            mainClock.advanceTimeBy(1000) // let the AnimatedContent page transition finish
        }

        render(Page.ADMIN_TOURS)
        val unratedTours = s.allTours.count { it.reviewsCount <= 0 || it.rating <= 0 }
        onAllNodesWithText("No Ratings").assertCountEquals(unratedTours)
        onAllNodesWithText("Archived").assertCountEquals(s.allTours.count { it.archived })

        render(Page.ADMIN_FRUIT_PICKING)
        val unratedOrchards = s.allOrchards.count { it.reviewsCount <= 0 || it.rating <= 0 }
        onAllNodesWithText("No Ratings").assertCountEquals(unratedOrchards)
        onAllNodesWithText("Archived").assertCountEquals(s.allOrchards.count { it.archived })

        render(Page.ADMIN_PRODUCTS)
        val unratedProducts = s.allProducts.count { it.reviewsCount <= 0 || it.rating <= 0 }
        onAllNodesWithText("No Ratings").assertCountEquals(unratedProducts)
        onAllNodesWithText("Archived").assertCountEquals(s.allProducts.count { it.archived })
        onAllNodesWithText("Out of Stock").assertCountEquals(s.allProducts.count { it.stock <= 0 })
    }

    @Test fun adminUserInformationTableHasOnlyRequiredColumnsAndDeleteAction() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.ADMIN) }
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        listOf("Name", "Email", "Phone Number", "Location", "Role", "Status").forEach {
            onAllNodesWithText(it).assertCountEquals(1)
        }
        onAllNodesWithText("View").assertCountEquals(0)
        onAllNodesWithText("Edit").assertCountEquals(0)
        onAllNodesWithText("Delete").assertCountEquals(s.userAccounts.count { it.role != Role.ADMIN })
        onAllNodesWithText("Not Provided").assertCountEquals(s.userAccounts.count { it.role != Role.ADMIN && it.phone.isBlank() })
    }

    @Test fun farmerDashboardShowsItsDatabaseSalesGraphAndBookingTables() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.FARMER) }
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("Farm Sales & Revenue Graph").assertCountEquals(1)
        onAllNodesWithText("Tour Bookings").assertCountEquals(1)
        onAllNodesWithText("Fruit Picking Bookings").assertCountEquals(1)
        onAllNodesWithText("My Farm Products").assertCountEquals(0)
        listOf("Date", "Time", "Guest", "Pax", "Amount", "Status").forEach { onAllNodesWithText(it).assertCountEquals(2) }
        val farmId = assertNotNull(s.myFarmId)
        assertTrue(s.monthlyRevenue.isNotEmpty())
        assertTrue(s.allProducts.any { it.farmerId == farmId } || s.allTours.any { it.farmerId == farmId })
    }

    @Test fun openingPageUsesExploreMoreAndGoesDirectlyToLogin() = runComposeUiTest {
        val s = AppState(db, scope)
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("Browse farm destinations, local farm products, and fruit orchards (picture & description view)").assertCountEquals(0)
        onAllNodesWithText("Go to Login Page to Explore More").assertCountEquals(0)
        onAllNodesWithText("Go to Log in page to explore more").assertCountEquals(0)
        onAllNodesWithText("Dashboard").assertCountEquals(0)
        onAllNodesWithText("Opening Page").assertCountEquals(0)
        onAllNodesWithText("Explore").assertCountEquals(0)
        onAllNodesWithText("Help & FAQs").assertCountEquals(0)
        onAllNodesWithText("FAQs").assertCountEquals(0)
        onAllNodesWithText("Explore More").assertCountEquals(1)
        onNodeWithText("AgriTourism Philippines").assertExists()
        onNodeWithText("Log In").assertExists()
        onNodeWithText("Sign Up").assertExists()
        mainClock.advanceTimeBy(300)
        onNodeWithText("Explore More").performClick()
        mainClock.advanceTimeBy(100)
        assertEquals(Page.LOGIN, s.activePage)
    }

    @Test fun adminHomeHidesFaqSection() = runComposeUiTest {
        val admin = AppState(db, scope).also { it.loginAs(Role.ADMIN); it.activePage = Page.HOME }
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(admin) } }
        onAllNodesWithText("Help & FAQs").assertCountEquals(0)
        onAllNodesWithText("Answers to common questions about booking, payment and accounts").assertCountEquals(0)
    }

    @Test fun touristHomeKeepsFaqSection() = runComposeUiTest {
        val tourist = AppState(db, scope).also { it.loginAs(Role.USER); it.activePage = Page.HOME }
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(tourist) } }
        assertTrue(tourist.faqs.isNotEmpty())
        onAllNodesWithText("Answers to common questions about booking, payment and accounts").assertCountEquals(1)
    }

    @Test fun productDetailModalUsesDatabaseFieldsAndReturnsToListing() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.USER) }
        val product = s.products.first()
        val farm = s.farmers.firstOrNull { it.id == product.farmerId }
        s.activePage = Page.MARKET
        s.selectedProductForView = product
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("Product Name: ${product.name}").assertCountEquals(1)
        onAllNodesWithText("Description: ${product.description}").assertCountEquals(1)
        onAllNodesWithText("Farm Name: ${farm?.farmName ?: product.farmOrigin}").assertCountEquals(1)
        onAllNodesWithText("Back").assertCountEquals(1)
        onAllNodesWithContentDescription("Back").assertCountEquals(0)
        mainClock.advanceTimeBy(300) // let the modal entrance animation finish before clicking
        onNodeWithText("Back").performClick()
        assertNull(s.selectedProductForView)
        assertEquals(Page.MARKET, s.activePage)
    }

    @Test fun adminBookingPageHasNoMutationControls() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.ADMIN) }
        s.activePage = Page.MY_BOOKINGS
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        mainClock.advanceTimeBy(100)
        onAllNodesWithText("Update").assertCountEquals(0)
        onAllNodesWithText("Cancel").assertCountEquals(0)
        onAllNodesWithContentDescription("Delete booking from history").assertCountEquals(0)
        onAllNodesWithText("Gate Check-In").assertCountEquals(0)
    }

    @Test fun farmerDeletionDialogShowsTheExactRequiredMessage() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.ADMIN) }
        s.pendingConfirm = AppState.Confirm(
            "Delete Farmer Account?",
            "Deleting this Farmer account will also permanently remove the associated farm information, products, produce, tours, fruit-picking listings, schedules, and other related active data. Historical transaction records may be retained when necessary.",
            "Delete Account", {}, "Cancel",
        )
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("Delete Farmer Account?").assertCountEquals(1)
        onAllNodesWithText("Deleting this Farmer account will also permanently remove the associated farm information, products, produce, tours, fruit-picking listings, schedules, and other related active data. Historical transaction records may be retained when necessary.").assertCountEquals(1)
        onAllNodesWithText("Cancel").assertCountEquals(1)
        onAllNodesWithText("Delete Account").assertCountEquals(1)
    }

    @Test fun globalSearchKeepsAcceptingTextAfterResultsAppear() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.USER) }
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        val search = onNodeWithTag("global-search")
        search.performClick()
        search.performTextInput("sun")
        mainClock.advanceTimeBy(100)
        assertEquals("sun", s.searchQuery)
        search.performTextInput("nyvale")
        mainClock.advanceTimeBy(100)
        assertEquals("sunnyvale", s.searchQuery)
        search.assertTextEquals("sunnyvale")
        assertTrue(s.searchResults.any { it.title.contains("Sunnyvale", ignoreCase = true) })
        onNodeWithContentDescription("Clear search").performClick()
        mainClock.advanceTimeBy(100)
        assertTrue(s.searchQuery.isEmpty())
        search.performTextInput("no matching farm")
        mainClock.advanceTimeBy(100)
        onAllNodesWithText("No results found").assertCountEquals(1)
    }

    @Test fun tourPassOmitsQrAndKeepsSavePrintAndCloseActions() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.USER) }
        s.selectedBookingForTicket = s.bookings.first()
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("Official Digital Tour Pass & E-Ticket").assertCountEquals(1)
        onAllNodesWithText("Save Ticket").assertCountEquals(1)
        onAllNodesWithText("Print Ticket").assertCountEquals(1)
        onAllNodesWithText("Close").assertCountEquals(2) // modal header and bottom action are both text-only
        onAllNodesWithContentDescription("Close").assertCountEquals(0)
        onAllNodesWithText("AgriTourism QR Check-in").assertCountEquals(0)
    }

    @Test fun tourDateFieldOpensCalendarPicker() = runComposeUiTest {
        val date = mutableStateOf("")
        setContent { MaterialTheme(colorScheme = AppColors) { DateField("", date.value, { date.value = it }) } }
        onAllNodesWithText("Select a date").assertCountEquals(1)
        onNodeWithText("Select a date").performClick()
        onAllNodesWithText("Select").assertCountEquals(1)
        onNodeWithText("Select").performClick()
        assertTrue(date.value.isNotBlank())
    }

    @Test fun estimatedTourDetailsShowPriceEvidenceAndBookingAvailability() = runComposeUiTest {
        val s = AppState(db, scope)
        val tour = s.tours.first { it.priceStatus == ph.agritourism.model.TourPriceStatus.ESTIMATED }
        s.selectedTour = tour
        s.isTourModalOpen = true
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("New listing").assertCountEquals(1)
        onAllNodesWithText("Estimated Rate").assertCountEquals(1)
        if (tour.bookingEnabled) {
            assertTrue(s.availableSlotsFor("tour", tour.id).isNotEmpty())
        } else {
            onAllNodesWithText("Bookings are currently unavailable for this tour.").assertCountEquals(1)
        }
    }

    @Test fun successfulPurchaseShowsSavedOrderConfirmation() = runComposeUiTest {
        val s = AppState(db, scope).also { it.loginAs(Role.USER) }
        s.activePage = Page.CART_CHECKOUT
        s.checkoutStep = 3
        s.placeOrder()
        val order = assertNotNull(s.purchaseConfirmationOrder)
        assertTrue(db.orders().any { it.id == order.id })
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("Purchase Successful!").assertCountEquals(1)
        onAllNodesWithText("Order reference: ${order.orderNumber}").assertCountEquals(1)
        onAllNodesWithText(order.items.first().productName).assertCountEquals(1)
        onAllNodesWithText("Cash on Delivery").assertCountEquals(1)
        onAllNodesWithText("View Order Details").assertCountEquals(1)
        assertTrue(onAllNodesWithText("Back").fetchSemanticsNodes().isNotEmpty())
        onAllNodesWithContentDescription("Back").assertCountEquals(0)
    }

    @Test fun loginPageUsesUpdatedLoginLabelAndTextOnlyBack() = runComposeUiTest {
        val s = AppState(db, scope).also { it.activePage = Page.LOGIN }
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onAllNodesWithText("Log In to Dashboard").assertCountEquals(0)
        onAllNodesWithText("Welcome Back").assertCountEquals(1)
        onAllNodesWithText("Log In").assertCountEquals(2)
        onAllNodesWithText("Back").assertCountEquals(1)
        onAllNodesWithContentDescription("Back").assertCountEquals(0)
        onNodeWithTag("back-to-opening").assertExists()
        onAllNodesWithText("Tip: press Enter to log in").assertCountEquals(0)
    }

    private fun assertBackReturnsToOpening(page: Page) = runComposeUiTest {
        val s = AppState(db, scope).also { it.activePage = page }
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = AppColors) { AppShell(s) } }
        onNodeWithTag("back-to-opening").assertExists()
        onAllNodesWithText("Back").assertCountEquals(1)
        onAllNodesWithContentDescription("Back").assertCountEquals(0)
        onNodeWithTag("back-to-opening").performClick()
        mainClock.advanceTimeBy(100)
        assertEquals(Page.HOME, s.activePage)
        assertTrue(onAllNodesWithText("AgriTourism Philippines").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun loginBackButtonReturnsToOpeningPage() = assertBackReturnsToOpening(Page.LOGIN)

    @Test fun signupBackButtonReturnsToOpeningPage() = assertBackReturnsToOpening(Page.SIGNUP)
}
