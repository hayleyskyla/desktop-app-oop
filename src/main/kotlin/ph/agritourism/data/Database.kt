package ph.agritourism.data

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ph.agritourism.model.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalTime

class AdminSetup internal constructor(val email: String, val password: String)

private fun readAdminSetupFromEnvironment(): AdminSetup? {
    val email = System.getenv("AGRITOURISM_ADMIN_EMAIL")?.trim().orEmpty()
    val password = System.getenv("AGRITOURISM_ADMIN_PASSWORD").orEmpty()
    if (email.isBlank() && password.isBlank()) return null
    require(email.isNotBlank() && password.isNotBlank()) {
        "Set both AGRITOURISM_ADMIN_EMAIL and AGRITOURISM_ADMIN_PASSWORD to configure the administrator."
    }
    return AdminSetup(email, password)
}

/**
 * SQLite3 persistence layer. Replaces the old browser localStorage + Java/XAMPP MySQL backend.
 * The database file lives in ~/.agritourism/agritourism.db (override with AGRITOURISM_DB)
 * and is seeded from seed.json on first run.
 */
class Database(
    val file: File = defaultFile(),
    private val adminSetup: AdminSetup? = readAdminSetupFromEnvironment(),
) {

    val conn: Connection
    private var txDepth = 0

    init {
        file.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.createStatement().use {
            it.execute("PRAGMA foreign_keys = ON")
            it.execute("PRAGMA journal_mode = WAL")
        }
        backupBeforeMigration()
        createSchema()
        if (count("products") == 0 && count("tours") == 0) seed()
        // Fresh databases are seeded after migrate(); backfill their demo accounts and galleries too.
        backfillSplitNamesAndAddresses()
        importLegacyGalleryImages()
        refreshSeedImages()
        syncBundledTours()
        seedReferenceData()
        configureUploadedTourSchedules()
        // Older non-admin demo accounts had no password; never grant a default password to admins.
        exec("UPDATE users SET password_hash=? WHERE role<>'admin' AND (password_hash IS NULL OR password_hash='')", hashPassword(DEFAULT_PASSWORD))
        configureAdminAccount(adminSetup)
        removeObsoleteBookingNotifications()
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val strList = ListSerializer(String.serializer())
        private val longList = ListSerializer(Long.serializer())

        fun defaultFile(): File =
            System.getenv("AGRITOURISM_DB")?.let(::File)
                ?: File(System.getProperty("user.home"), ".agritourism/agritourism.db")

        fun loadSeed(): SeedData {
            val text = Database::class.java.getResource("/seed.json")!!.readText()
            return json.decodeFromString(SeedData.serializer(), text)
        }

        const val DEFAULT_PASSWORD = "password"
        const val MIGRATION_V2_MARKER = "migration.v2.complete"
        const val MAX_IMAGES_PER_LISTING = 5
        const val DEFAULT_FARM_CAPACITY = 100

        /** Salted PBKDF2 hash stored as "salt:hash" (hex). */
        fun hashPassword(password: String, salt: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }): String {
            val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 65_536, 256)
            val hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return salt.hex() + ":" + hash.hex()
        }

        fun verifyPassword(password: String, stored: String?): Boolean {
            val salt = stored?.substringBefore(":", "")?.takeIf { it.isNotEmpty() } ?: return false
            val bytes = salt.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return java.security.MessageDigest.isEqual(hashPassword(password, bytes).toByteArray(), stored.toByteArray())
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

        fun List<String>.enc() = json.encodeToString(strList, this)
        fun String.decList(): List<String> = json.decodeFromString(strList, this)
    }

    // ------------------------------------------------------------------ schema
    private fun createSchema() {
        val ddl = listOf(
            """CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                full_name TEXT NOT NULL,
                email TEXT NOT NULL UNIQUE,
                phone TEXT,
                password_hash TEXT,
                role TEXT NOT NULL CHECK (role IN ('user','farmer','admin')),
                is_active INTEGER NOT NULL DEFAULT 1,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                first_name TEXT, middle_name TEXT NOT NULL DEFAULT '', last_name TEXT,
                house_no TEXT NOT NULL DEFAULT '', street TEXT NOT NULL DEFAULT '',
                municipality TEXT NOT NULL DEFAULT '', city TEXT NOT NULL DEFAULT '',
                deleted_at TEXT)""",
            """CREATE TABLE IF NOT EXISTS farmers (
                id INTEGER PRIMARY KEY, name TEXT, role_title TEXT, farm_name TEXT, location TEXT,
                province TEXT, years_experience INTEGER, photo_url TEXT, farm_banner_url TEXT,
                story_quote TEXT, full_biography TEXT, farming_philosophy TEXT, certifications TEXT,
                sustainable_practices TEXT, specialty_produce TEXT, product_ids TEXT, tour_id INTEGER,
                rating REAL, reviews_count INTEGER, generation TEXT, hectares TEXT, gallery TEXT,
                capacity INTEGER NOT NULL DEFAULT 100, deleted_at TEXT)""",
            """CREATE TABLE IF NOT EXISTS tours (
                id INTEGER PRIMARY KEY, name TEXT, location TEXT, rating REAL, reviews_count INTEGER,
                price_per_person INTEGER, operating_hours TEXT, amenities TEXT, activities TEXT,
                image_url TEXT, description TEXT, highlight_tag TEXT, farmer_name TEXT, thumbnails TEXT,
                estimated_duration TEXT NOT NULL DEFAULT '', price_approved INTEGER NOT NULL DEFAULT 1,
                suggested_price_per_person INTEGER, price_suggestion_basis TEXT NOT NULL DEFAULT '',
                price_suggestion_source TEXT NOT NULL DEFAULT '',
                price_status TEXT NOT NULL DEFAULT 'UNVERIFIED',
                price_type TEXT NOT NULL DEFAULT 'PER_PERSON',
                booking_enabled INTEGER NOT NULL DEFAULT 1,
                experience_type TEXT NOT NULL DEFAULT 'tour',
                deleted_at TEXT, archived_before_delete INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS fruit_picking (
                id INTEGER PRIMARY KEY, title TEXT, farm_name TEXT, location TEXT, fruit_name TEXT,
                price_per_person INTEGER, rating REAL, reviews_count INTEGER, duration TEXT,
                seasonal_fruits TEXT, image_url TEXT, description TEXT, season_availability TEXT, thumbnails TEXT,
                deleted_at TEXT, archived_before_delete INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS products (
                id INTEGER PRIMARY KEY, name TEXT NOT NULL, category TEXT, price REAL, unit TEXT,
                discount_percent INTEGER, stock INTEGER, rating REAL, reviews_count INTEGER,
                image_url TEXT, description TEXT, farm_origin TEXT, sort_order INTEGER,
                deleted_at TEXT, archived_before_delete INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS listing_images (
                id INTEGER PRIMARY KEY AUTOINCREMENT, owner_type TEXT NOT NULL, owner_id INTEGER NOT NULL,
                url TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0, is_cover INTEGER NOT NULL DEFAULT 0,
                deleted_at TEXT)""",
            """CREATE TABLE IF NOT EXISTS bookings (
                id INTEGER PRIMARY KEY, booking_code TEXT, farm_id INTEGER, farm_name TEXT,
                customer_name TEXT, customer_phone TEXT, customer_email TEXT, activity TEXT,
                tour_date TEXT, tour_time TEXT, participants INTEGER, total_amount INTEGER,
                payment_method TEXT, status TEXT, attendance_status TEXT, assigned_guide TEXT,
                special_requests TEXT, uploaded_id_doc TEXT, uploaded_receipt_doc TEXT,
                checked_in_at TEXT, sort_order INTEGER)""",
            """CREATE TABLE IF NOT EXISTS orders (
                id TEXT PRIMARY KEY, order_number TEXT, customer_name TEXT, customer_phone TEXT,
                customer_address TEXT, total_amount REAL, payment_method TEXT, payment_status TEXT,
                fulfillment_status TEXT, order_date TEXT, uploaded_receipt_url TEXT,
                special_delivery_notes TEXT, courier_name TEXT, rider_name TEXT, rider_phone TEXT,
                rider_vehicle TEXT, current_location TEXT, estimated_arrival TEXT, tracking_number TEXT,
                is_received_by_user INTEGER DEFAULT 0, received_at TEXT, received_by_recipient TEXT,
                sort_order INTEGER)""",
            """CREATE TABLE IF NOT EXISTS order_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id TEXT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
                product_name TEXT, qty INTEGER, unit_price REAL,
                product_image_url TEXT NOT NULL DEFAULT '', seller_name TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE IF NOT EXISTS delivery_steps (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id TEXT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
                step_index INTEGER, title TEXT, location TEXT, timestamp TEXT, completed INTEGER, notes TEXT)""",
            """CREATE TABLE IF NOT EXISTS notifications (
                id INTEGER PRIMARY KEY, title TEXT, description TEXT, full_detail TEXT, time_label TEXT,
                is_read INTEGER, type TEXT, target_role TEXT, booking_id INTEGER, attendance_status TEXT,
                sort_order INTEGER, order_id TEXT, event_key TEXT)""",
            """CREATE TABLE IF NOT EXISTS reviews (
                id INTEGER PRIMARY KEY, author TEXT, role_tag TEXT, rating INTEGER, review_date TEXT,
                farm_or_crop TEXT, comment TEXT)""",
            """CREATE TABLE IF NOT EXISTS user_cart (
                user_email TEXT NOT NULL,
                product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
                quantity INTEGER NOT NULL, sort_order INTEGER,
                PRIMARY KEY (user_email, product_id))""",
            """CREATE TABLE IF NOT EXISTS user_favorites (
                user_email TEXT NOT NULL, item_id INTEGER NOT NULL,
                PRIMARY KEY (user_email, item_id))""",
            """CREATE TABLE IF NOT EXISTS guides (
                id INTEGER PRIMARY KEY AUTOINCREMENT, farmer_id INTEGER, name TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS couriers (
                id INTEGER PRIMARY KEY AUTOINCREMENT, company TEXT NOT NULL, rider_name TEXT NOT NULL,
                rider_phone TEXT, vehicle TEXT, hub TEXT)""",
            """CREATE TABLE IF NOT EXISTS time_slots (
                id INTEGER PRIMARY KEY AUTOINCREMENT, activity_type TEXT NOT NULL, activity_id INTEGER NOT NULL,
                label TEXT NOT NULL, sort_order INTEGER)""",
            """CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS faqs (
                id INTEGER PRIMARY KEY AUTOINCREMENT, question TEXT NOT NULL, answer TEXT NOT NULL, sort_order INTEGER)""",
        )
        conn.createStatement().use { st -> ddl.forEach(st::execute) }
        migrate()
        exec("CREATE INDEX IF NOT EXISTS idx_listing_images_owner ON listing_images(owner_type, owner_id, sort_order)")
    }

    /**
     * Copies an existing database before any additive migration runs. The backup lives beside the
     * database in `backups/` and is never overwritten, so old records remain recoverable.
     */
    private fun backupBeforeMigration() {
        if (!file.exists() || file.length() == 0L) return
        val pending = runCatching {
            query("SELECT value FROM settings WHERE key=?", MIGRATION_V2_MARKER) { it.getString(1) }.firstOrNull() != "complete"
        }.getOrDefault(true)
        if (!pending) return
        runCatching { conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
        val dir = File(file.absoluteFile.parentFile, "backups").apply { mkdirs() }
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val dest = File(dir, "${file.nameWithoutExtension}-before-v2-$stamp.db")
        if (!dest.exists()) {
            runCatching {
                file.copyTo(dest, overwrite = false)
                listOf("-wal", "-shm").forEach { suffix ->
                    File(file.absolutePath + suffix).takeIf { it.exists() }?.let {
                        it.copyTo(File(dest.absolutePath + suffix), overwrite = true)
                    }
                }
            }.onFailure { println("Database backup failed: ${it.message}") }
        }
    }

    private fun columns(table: String): Set<String> =
        query("PRAGMA table_info($table)") { it.getString("name") }.toSet()

    private fun tableExists(name: String) =
        query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", name) { 1 }.isNotEmpty()

    /** Upgrades databases created by older versions (shared cart/favorites, orders without an owner). */
    private fun migrate() {
        if ("address" !in columns("users")) exec("ALTER TABLE users ADD COLUMN address TEXT")
        if ("avatar_url" !in columns("users")) exec("ALTER TABLE users ADD COLUMN avatar_url TEXT")
        if ("is_active" !in columns("users")) exec("ALTER TABLE users ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1")
        if ("created_at" !in columns("users")) exec("ALTER TABLE users ADD COLUMN created_at TEXT")
        if ("estimated_duration" !in columns("tours")) exec("ALTER TABLE tours ADD COLUMN estimated_duration TEXT NOT NULL DEFAULT ''")
        if ("price_approved" !in columns("tours")) exec("ALTER TABLE tours ADD COLUMN price_approved INTEGER NOT NULL DEFAULT 1")
        if ("suggested_price_per_person" !in columns("tours")) exec("ALTER TABLE tours ADD COLUMN suggested_price_per_person INTEGER")
        if ("price_suggestion_basis" !in columns("tours")) exec("ALTER TABLE tours ADD COLUMN price_suggestion_basis TEXT NOT NULL DEFAULT ''")
        if ("price_suggestion_source" !in columns("tours")) exec("ALTER TABLE tours ADD COLUMN price_suggestion_source TEXT NOT NULL DEFAULT ''")
        if ("price_status" !in columns("tours")) exec("ALTER TABLE tours ADD COLUMN price_status TEXT NOT NULL DEFAULT 'UNVERIFIED'")
        if ("price_type" !in columns("tours")) exec("ALTER TABLE tours ADD COLUMN price_type TEXT NOT NULL DEFAULT 'PER_PERSON'")
        if ("booking_enabled" !in columns("tours")) exec("ALTER TABLE tours ADD COLUMN booking_enabled INTEGER NOT NULL DEFAULT 1")
        if ("experience_type" !in columns("tours")) exec("ALTER TABLE tours ADD COLUMN experience_type TEXT NOT NULL DEFAULT 'tour'")
        // Payment proof photos are no longer used; the old sample receipt showed a fixed amount.
        exec("UPDATE orders SET uploaded_receipt_url='' WHERE uploaded_receipt_url LIKE '%receipt.png'")
        if ("customer_email" !in columns("orders")) exec("ALTER TABLE orders ADD COLUMN customer_email TEXT")
        if ("recipient_email" !in columns("notifications")) {
            exec("ALTER TABLE notifications ADD COLUMN recipient_email TEXT")
            // Farmer alerts go to the farm they concern; the old generic ones were about the demo farmer's farm.
            exec("UPDATE notifications SET recipient_email=? WHERE target_role='farmer'", Role.FARMER.email)
        }
        listOf("users", "tours", "fruit_picking", "products").forEach { t ->
            if ("farmer_id" !in columns(t)) exec("ALTER TABLE $t ADD COLUMN farmer_id INTEGER")
        }
        listOf("tours", "fruit_picking", "products").forEach { t ->
            if ("archived" !in columns(t)) exec("ALTER TABLE $t ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        }
        if ("product_id" !in columns("order_items")) exec("ALTER TABLE order_items ADD COLUMN product_id INTEGER")
        if ("product_image_url" !in columns("order_items")) exec("ALTER TABLE order_items ADD COLUMN product_image_url TEXT NOT NULL DEFAULT ''")
        if ("seller_name" !in columns("order_items")) exec("ALTER TABLE order_items ADD COLUMN seller_name TEXT NOT NULL DEFAULT ''")
        if ("order_id" !in columns("notifications")) exec("ALTER TABLE notifications ADD COLUMN order_id TEXT")
        if ("event_key" !in columns("notifications")) exec("ALTER TABLE notifications ADD COLUMN event_key TEXT")
        exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_notifications_event_recipient ON notifications(event_key, COALESCE(recipient_email,'')) WHERE event_key IS NOT NULL")
        exec("""UPDATE order_items SET product_image_url=COALESCE(
                (SELECT image_url FROM products WHERE products.id=order_items.product_id), '')
                WHERE product_image_url=''""")
        exec("""UPDATE order_items SET seller_name=COALESCE(
                (SELECT farmers.farm_name FROM products JOIN farmers ON farmers.id=products.farmer_id
                 WHERE products.id=order_items.product_id),
                (SELECT farm_origin FROM products WHERE products.id=order_items.product_id), '')
                WHERE seller_name=''""")
        // ---- v2 schema must exist before any DAO helper below queries farmers or listings ----
        val userCols = columns("users")
        listOf("first_name", "last_name").forEach { c ->
            if (c !in userCols) exec("ALTER TABLE users ADD COLUMN $c TEXT")
        }
        listOf("middle_name", "house_no", "street", "municipality", "city").forEach { c ->
            if (c !in userCols) exec("ALTER TABLE users ADD COLUMN $c TEXT NOT NULL DEFAULT ''")
        }
        if ("deleted_at" !in userCols) exec("ALTER TABLE users ADD COLUMN deleted_at TEXT")
        val farmerCols = columns("farmers")
        if ("capacity" !in farmerCols) exec("ALTER TABLE farmers ADD COLUMN capacity INTEGER NOT NULL DEFAULT 100")
        if ("deleted_at" !in farmerCols) exec("ALTER TABLE farmers ADD COLUMN deleted_at TEXT")
        listOf("tours", "fruit_picking", "products").forEach { t ->
            val cols = columns(t)
            if ("deleted_at" !in cols) exec("ALTER TABLE $t ADD COLUMN deleted_at TEXT")
            if ("archived_before_delete" !in cols) exec("ALTER TABLE $t ADD COLUMN archived_before_delete INTEGER NOT NULL DEFAULT 0")
        }
        exec("UPDATE farmers SET capacity=100 WHERE capacity IS NULL OR capacity < 1")
        backfillSplitNamesAndAddresses()

        exec("UPDATE users SET farmer_id=1 WHERE farmer_id IS NULL AND lower(email)=lower(?)", Role.FARMER.email)
        assignFarmOwnership()
        linkOrderItems()
        exec("""UPDATE order_items SET product_image_url=COALESCE(
                (SELECT image_url FROM products WHERE products.id=order_items.product_id), '')
                WHERE product_image_url=''""")
        exec("""UPDATE order_items SET seller_name=COALESCE(
                (SELECT farmers.farm_name FROM products JOIN farmers ON farmers.id=products.farmer_id
                 WHERE products.id=order_items.product_id),
                (SELECT farm_origin FROM products WHERE products.id=order_items.product_id), '')
                WHERE seller_name=''""")
        val bookingCols = columns("bookings")
        if ("activity_type" !in bookingCols) {
            exec("ALTER TABLE bookings ADD COLUMN activity_type TEXT NOT NULL DEFAULT 'tour'")
            classifyBookings()
        }
        if ("slot_key" !in bookingCols) exec("ALTER TABLE bookings ADD COLUMN slot_key TEXT")
        exec("UPDATE bookings SET payment_method=? WHERE payment_method IS NULL OR payment_method<>?", PAY_AT_FARM_PAYMENT_METHOD, PAY_AT_FARM_PAYMENT_METHOD)
        exec("UPDATE orders SET payment_method=? WHERE payment_method IS NULL OR payment_method<>?", COD_PAYMENT_METHOD, COD_PAYMENT_METHOD)
        // Keep legacy orders but collapse delivery milestones into the supported marketplace states.
        exec("""UPDATE orders SET fulfillment_status = CASE fulfillment_status
            WHEN 'Pending Packing' THEN 'Pending'
            WHEN 'Packed' THEN 'Confirmed'
            WHEN 'Dispatched' THEN 'Confirmed'
            WHEN 'Out for Delivery' THEN 'Confirmed'
            WHEN 'Delivered' THEN 'Confirmed'
            ELSE fulfillment_status END
            WHERE fulfillment_status IN ('Pending Packing', 'Packed', 'Dispatched', 'Out for Delivery', 'Delivered')""")
        // Fill slot keys for older rows; legacy duplicates get a unique suffix so the index can be built.
        val seen = mutableSetOf<String>()
        bookings().forEach { b ->
            var key = b.slotKey()
            if (b.status != "Cancelled" && !seen.add(key)) key += "#legacy-${b.id}"
            exec("UPDATE bookings SET slot_key=? WHERE id=?", key, b.id)
        }
        // A customer can hold only one active (non-cancelled) booking per date + time slot.
        exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_bookings_active_slot ON bookings(slot_key) WHERE status <> 'Cancelled'")
        val reviewCols = columns("reviews")
        if ("user_email" !in reviewCols) exec("ALTER TABLE reviews ADD COLUMN user_email TEXT")
        if ("booking_id" !in reviewCols) exec("ALTER TABLE reviews ADD COLUMN booking_id INTEGER")
        if ("target_type" !in reviewCols) exec("ALTER TABLE reviews ADD COLUMN target_type TEXT")
        if ("target_id" !in reviewCols) exec("ALTER TABLE reviews ADD COLUMN target_id INTEGER")
        // One review per booking.
        exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_reviews_booking ON reviews(booking_id)")
        linkSeedReviews()
        localizeImages()
        // Link existing orders to the account with the same name.
        exec("""UPDATE orders SET customer_email=(SELECT email FROM users WHERE lower(users.full_name)=lower(orders.customer_name) LIMIT 1)
                WHERE customer_email IS NULL""")
        exec("""UPDATE notifications SET recipient_email=(SELECT customer_email FROM bookings WHERE bookings.id=notifications.booking_id)
                WHERE recipient_email IS NULL AND booking_id IS NOT NULL AND target_role='tourist'""")
        // The old single shared cart/favorites belonged to the demo tourist.
        val owner = Role.USER.email
        if (tableExists("cart_items")) {
            exec("INSERT OR IGNORE INTO user_cart (user_email, product_id, quantity, sort_order) SELECT ?, product_id, quantity, sort_order FROM cart_items", owner)
            exec("DROP TABLE cart_items")
        }
        if (tableExists("favorites")) {
            exec("INSERT OR IGNORE INTO user_favorites (user_email, item_id) SELECT ?, item_id FROM favorites", owner)
            exec("DROP TABLE favorites")
        }

        // ---- v2 gallery import runs after image URLs are localised ----
        importLegacyGalleryImages()
        setSetting(MIGRATION_V2_MARKER, "complete")
    }

    /** Splits legacy full names/addresses into the new required components without losing the originals. */
    private fun backfillSplitNamesAndAddresses() {
        query("SELECT id, full_name, address FROM users WHERE first_name IS NULL OR first_name=''") { r ->
            Triple(r.getLong(1), r.str("full_name").orEmpty(), r.str("address").orEmpty())
        }.forEach { (id, name, address) ->
            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val first = parts.firstOrNull().orEmpty()
            val last = if (parts.size > 1) parts.last() else ""
            val middle = if (parts.size > 2) parts.drop(1).dropLast(1).joinToString(" ") else ""
            val addr = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val house = addr.getOrNull(0).orEmpty()
            val street = addr.getOrNull(1).orEmpty()
            val municipality = addr.getOrNull(2).orEmpty()
            val city = addr.getOrNull(3) ?: addr.getOrNull(2).orEmpty()
            exec("UPDATE users SET first_name=?, middle_name=?, last_name=?, house_no=?, street=?, municipality=?, city=? WHERE id=?",
                first, middle, last, house, street, municipality, city, id)
        }
    }

    /** Seeds the multi-image table from galleries already stored in the legacy columns. */
    private fun importLegacyGalleryImages() {
        tours().forEach { t ->
            if (query("SELECT 1 FROM listing_images WHERE owner_type='tour' AND owner_id=? LIMIT 1", t.id) { 1 }.isEmpty()) {
                val urls = (listOf(t.imageUrl) + t.thumbnails).filter { it.isNotBlank() }.distinct().take(5)
                urls.forEachIndexed { i, url -> insertListingImage("tour", t.id.toLong(), url, i, i == 0) }
            }
        }
        fruitPicking().forEach { f ->
            if (query("SELECT 1 FROM listing_images WHERE owner_type='orchard' AND owner_id=? LIMIT 1", f.id) { 1 }.isEmpty()) {
                val urls = (listOf(f.imageUrl) + f.thumbnails).filter { it.isNotBlank() }.distinct().take(5)
                urls.forEachIndexed { i, url -> insertListingImage("orchard", f.id.toLong(), url, i, i == 0) }
            }
        }
        products().forEach { p ->
            if (query("SELECT 1 FROM listing_images WHERE owner_type='product' AND owner_id=? LIMIT 1", p.id) { 1 }.isEmpty()
                && p.imageUrl.isNotBlank()) insertListingImage("product", p.id, p.imageUrl, 0, true)
        }
    }

    private fun configureAdminAccount(setup: AdminSetup?) = tx {
        val admins = query("SELECT * FROM users WHERE role='admin' ORDER BY id", map = ::mapUser)
        admins.forEach { admin ->
            val stored = query("SELECT password_hash FROM users WHERE id=?", admin.id) { it.getString(1) }.firstOrNull()
            if (verifyPassword(DEFAULT_PASSWORD, stored)) {
                val disabledPassword = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()
                exec("UPDATE users SET password_hash=? WHERE id=?", hashPassword(disabledPassword), admin.id)
            }
        }

        if (setup != null) {
            val email = setup.email.trim()
            require(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) {
                "AGRITOURISM_ADMIN_EMAIL must be a valid email address."
            }
            require(setup.password.length >= 12) {
                "AGRITOURISM_ADMIN_PASSWORD must contain at least 12 characters."
            }
            val matchingAdmin = admins.firstOrNull { it.email.equals(email, ignoreCase = true) }
            val emailOwner = findUser(email)
            check(emailOwner == null || emailOwner.role == Role.ADMIN) {
                "AGRITOURISM_ADMIN_EMAIL is already assigned to a non-administrator account."
            }
            if (matchingAdmin != null) {
                exec("UPDATE users SET password_hash=? WHERE id=?", hashPassword(setup.password), matchingAdmin.id)
            } else {
                exec("INSERT INTO users (full_name, email, phone, password_hash, role) VALUES (?,?,?,?,?)",
                    "Administrator", email, "", hashPassword(setup.password), Role.ADMIN.key)
            }
        }
    }

    /**
     * Retires the two generic "Booking"/"Tour Booking Confirmed" notices. Real booking updates
     * (acceptance, decline, attendance, cancellation) are kept and relabelled as booking updates.
     */
    private fun removeObsoleteBookingNotifications() = tx {
        exec("DELETE FROM notifications WHERE title IN ('Booking', 'Tour Booking Confirmed')")
        exec("UPDATE notifications SET type='booking_update' WHERE type='booking'")
    }

    /** Older databases point at remote (some now dead) image URLs; swap them for the bundled copies. */
    private fun localizeImages() {
        val cols = listOf("farmers" to "photo_url", "farmers" to "farm_banner_url", "tours" to "image_url", "tours" to "thumbnails",
            "fruit_picking" to "image_url", "fruit_picking" to "thumbnails", "products" to "image_url", "orders" to "uploaded_receipt_url")
        val stale = cols.any { (t, c) -> query("SELECT 1 FROM $t WHERE $c LIKE '%images.unsplash.com%' OR $c LIKE '%api.qrserver.com%' LIMIT 1") { 1 }.isNotEmpty() }
        if (!stale) return
        val text = Database::class.java.getResource("/images/legacy-urls.json")?.readText() ?: return
        val map = json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), text)
        map.forEach { (old, new) -> cols.forEach { (t, c) -> exec("UPDATE $t SET $c=REPLACE($c, ?, ?) WHERE instr($c, ?) > 0", old, new, old) } }
    }

    /** Re-syncs bundled catalog photos with seed.json once (older seeds paired items with the wrong pictures). Uploaded photos are kept. */
    private fun refreshSeedImages() {
        val version = 2
        if (query("PRAGMA user_version") { it.getInt(1) }.first() >= version) return
        val s = loadSeed()
        tx {
            s.products.forEach { exec("UPDATE products SET image_url=? WHERE id=? AND image_url LIKE 'res:%'", it.imageUrl, it.id) }
            s.tours.forEach { exec("UPDATE tours SET image_url=?, thumbnails=? WHERE id=? AND image_url LIKE 'res:%'", it.imageUrl, it.thumbnails.enc(), it.id) }
            s.fruitPicking.forEach { exec("UPDATE fruit_picking SET image_url=?, thumbnails=? WHERE id=? AND image_url LIKE 'res:%'", it.imageUrl, it.thumbnails.enc(), it.id) }
            s.farmers.forEach {
                exec("UPDATE farmers SET photo_url=?, farm_banner_url=?, gallery=? WHERE id=? AND farm_banner_url LIKE 'res:%'",
                    it.photoUrl, it.farmBannerUrl, it.gallery.enc(), it.id)
            }
        }
        conn.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }

    /** Adds or refreshes exact-name bundled destinations while preserving row identities and schedules. */
    private fun syncBundledTours() {
        val version = 6
        val sourceVersion = query("PRAGMA user_version") { it.getInt(1) }.first()
        if (sourceVersion >= version) return
        val firstImport = sourceVersion < 3
        val bundledTours = loadSeed().tours.filter { it.suggestedPricePerPerson != null }
        tx {
            bundledTours.forEach { bundled ->
                val existing = tours().firstOrNull { it.name == bundled.name }
                if (existing == null) {
                    createTour(bundled.copy(
                        id = 0,
                        farmerId = null,
                        archived = false,
                        pricePerPerson = bundled.suggestedPricePerPerson ?: bundled.pricePerPerson,
                        priceApproved = true,
                    ))
                } else {
                    updateTour(existing.copy(
                        location = bundled.location,
                        pricePerPerson = if (firstImport || existing.pricePerPerson <= 0) {
                            bundled.suggestedPricePerPerson ?: bundled.pricePerPerson
                        } else {
                            existing.pricePerPerson
                        },
                        operatingHours = existing.operatingHours.ifBlank { bundled.operatingHours },
                        amenities = bundled.amenities,
                        activities = bundled.activities,
                        imageUrl = bundled.imageUrl,
                        description = bundled.description,
                        highlightTag = bundled.highlightTag,
                        farmerName = bundled.farmerName,
                        thumbnails = bundled.thumbnails,
                        estimatedDuration = bundled.estimatedDuration,
                        priceApproved = true,
                        suggestedPricePerPerson = bundled.suggestedPricePerPerson,
                        priceSuggestionBasis = bundled.priceSuggestionBasis,
                        priceSuggestionSource = bundled.priceSuggestionSource,
                        priceStatus = bundled.priceStatus,
                        priceType = bundled.priceType,
                    ))
                }
            }
        }
        conn.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }

    /** Fills guides, couriers, time slots and settings from seed.json when missing (new or upgraded databases). */
    private fun seedReferenceData() {
        val s = loadSeed()
        tx {
            if (count("guides") == 0) s.guides.forEach { exec("INSERT INTO guides (id, farmer_id, name) VALUES (?,?,?)", it.id, it.farmerId, it.name) }
            if (count("couriers") == 0) s.couriers.forEach {
                exec("INSERT INTO couriers (id, company, rider_name, rider_phone, vehicle, hub) VALUES (?,?,?,?,?,?)",
                    it.id, it.company, it.riderName, it.riderPhone, it.vehicle, it.hub)
            }
            if (count("faqs") == 0) s.faqs.forEachIndexed { i, f ->
                exec("INSERT INTO faqs (question, answer, sort_order) VALUES (?,?,?)", f.question, f.answer, i)
            }
            exec(
                "UPDATE faqs SET answer=? WHERE lower(question)=lower(?)",
                "Choose a date and available time slot, then pay at the farm. Your ticket appears under My Bookings.",
                "How do I book a farm tour or fruit picking?",
            )
            val paymentQuestion = "What payment methods are accepted?"
            val paymentAnswer = "For farm product orders, customers can use Cash on Delivery (COD), where available. For tour bookings, payment is made directly at the farm through Pay at the Farm."
            val paymentFaqId = query(
                "SELECT id FROM faqs WHERE lower(question)=lower(?) OR lower(question)=lower(?) ORDER BY id LIMIT 1",
                "How do I pay?", paymentQuestion,
            ) { it.getInt(1) }.firstOrNull()
            if (paymentFaqId == null) {
                exec("INSERT INTO faqs (question, answer, sort_order) VALUES (?,?,?)", paymentQuestion, paymentAnswer, count("faqs"))
            } else {
                exec("UPDATE faqs SET question=?, answer=? WHERE id=?", paymentQuestion, paymentAnswer, paymentFaqId)
                exec(
                    "DELETE FROM faqs WHERE id<>? AND lower(question)=lower(?)",
                    paymentFaqId, paymentQuestion,
                )
            }
            if (count("time_slots") == 0) s.timeSlots.forEachIndexed { i, t ->
                exec("INSERT INTO time_slots (activity_type, activity_id, label, sort_order) VALUES (?,?,?,?)", t.activityType, t.activityId, t.label, i)
            }
            // New keys are added on upgrade; values already in the database are never overwritten.
            val images = json.encodeToJsonElement(AppImages.serializer(), s.config).jsonObject.mapValues { it.value.jsonPrimitive.content }
            (s.settings + images.mapKeys { "image.${it.key}" }).forEach { (k, v) -> exec("INSERT OR IGNORE INTO settings (key, value) VALUES (?,?)", k, v) }
            s.users.filter { it.address.isNotBlank() }.forEach {
                exec("UPDATE users SET address=? WHERE lower(email)=lower(?) AND (address IS NULL OR address='')", it.address, it.email)
            }
        }
    }

    /**
     * Configures daily operating hours and hourly booking starts for the seven bundled tours that
     * previously had no schedule. Uses a settings marker rather than PRAGMA user_version, which is
     * already owned by bundled-tour synchronization.
     */
    private fun configureUploadedTourSchedules() {
        val marker = "migration.uploaded_tour_daily_schedule.v1"
        if (settings()[marker] == "complete") return
        val names = listOf(
            "Bantug Lake Ranch",
            "Burnham Park",
            "Chocolate Hills",
            "Garin Farm",
            "Northern Blossom Flower Farm",
            "Sagada Hanging Coffins",
            "SVD Laudato Si' Farm",
        )
        val starts = listOf(
            "07:00 AM", "08:00 AM", "09:00 AM", "10:00 AM", "11:00 AM",
            "12:00 PM", "01:00 PM", "02:00 PM", "03:00 PM", "04:00 PM",
        )
        tx {
            names.forEach { name ->
                val matches = query("SELECT id FROM tours WHERE name=?", name) { it.getInt(1) }
                check(matches.size == 1) { "Expected exactly one bundled tour named \"$name\"; found ${matches.size}." }
                val id = matches.single()
                exec("UPDATE tours SET operating_hours='7:00 AM - 5:00 PM', booking_enabled=1, archived=0 WHERE id=?", id)
                exec("DELETE FROM time_slots WHERE activity_type='tour' AND activity_id=?", id)
                starts.forEachIndexed { index, start ->
                    val end = starts.getOrNull(index + 1) ?: "05:00 PM"
                    exec(
                        "INSERT INTO time_slots (activity_type, activity_id, label, sort_order) VALUES ('tour',?,?,?)",
                        id, "$start - $end", index,
                    )
                }
            }
            setSetting(marker, "complete")
        }
    }

    fun settings(): Map<String, String> = query("SELECT key, value FROM settings") { it.getString(1) to it.getString(2) }.toMap()
    fun setSetting(key: String, value: String) = exec("INSERT INTO settings (key, value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)

    /** Bundled image settings (keys "image.<name>") as the typed config the UI uses. */
    fun images(): AppImages {
        val obj = kotlinx.serialization.json.JsonObject(settings().filterKeys { it.startsWith("image.") }
            .map { (k, v) -> k.removePrefix("image.") to kotlinx.serialization.json.JsonPrimitive(v) }.toMap())
        return runCatching { json.decodeFromJsonElement(AppImages.serializer(), obj) }.getOrElse { loadSeed().config }
    }

    fun guides(): List<Guide> = query("SELECT * FROM guides ORDER BY id") { r -> Guide(r.getInt("id"), r.getInt("farmer_id").takeIf { !r.wasNull() }, r.str("name")!!) }
    fun couriers(): List<Courier> = query("SELECT * FROM couriers ORDER BY id") { r ->
        Courier(r.getInt("id"), r.str("company")!!, r.str("rider_name")!!, r.str("rider_phone") ?: "", r.str("vehicle") ?: "", r.str("hub") ?: "")
    }
    fun timeSlots(): List<TimeSlot> = query("SELECT * FROM time_slots ORDER BY sort_order, id") { r ->
        TimeSlot(r.str("activity_type")!!, r.getInt("activity_id"), r.str("label")!!, r.getInt("id"))
    }

    fun faqs(): List<Faq> = query("SELECT * FROM faqs ORDER BY sort_order, id") { r -> Faq(r.str("question")!!, r.str("answer")!!, r.getInt("id")) }

    fun addGuide(farmerId: Int?, name: String) = exec("INSERT INTO guides (farmer_id, name) VALUES (?,?)", farmerId, name.trim())
    fun deleteGuide(id: Int) = exec("DELETE FROM guides WHERE id=?", id)
    fun addCourier(c: Courier) = exec("INSERT INTO couriers (company, rider_name, rider_phone, vehicle, hub) VALUES (?,?,?,?,?)",
        c.company.trim(), c.riderName.trim(), c.riderPhone.trim(), c.vehicle.trim(), c.hub.trim())
    fun deleteCourier(id: Int) = exec("DELETE FROM couriers WHERE id=?", id)
    fun addTimeSlot(type: String, activityId: Int, label: String) =
        exec("INSERT INTO time_slots (activity_type, activity_id, label, sort_order) VALUES (?,?,?,(SELECT COALESCE(MAX(sort_order),0)+1 FROM time_slots))",
            type, activityId, label.trim())
    fun deleteTimeSlot(id: Int) = exec("DELETE FROM time_slots WHERE id=?", id)

    /**
     * Syncs an activity's time slots to exactly [desiredLabels] (deduplicated by start time),
     * preserving any existing slot whose start time is in [preserveStarts] (e.g. an active booking)
     * so confirmed reservations keep their time when operating hours change. Slots are re-sorted
     * chronologically so the UI and booking flow list them in order.
     */
    fun replaceTimeSlots(type: String, activityId: Int, desiredLabels: List<String>, preserveStarts: Set<String> = emptySet()) = tx {
        val existing = timeSlots().filter { it.activityType == type && it.activityId == activityId }
        val existingByStart = existing.associateBy { slotTime(it.label) }
        val desiredByStart = desiredLabels.associateBy { slotTime(it) }
        existing.filter { slotTime(it.label) !in desiredByStart && slotTime(it.label) !in preserveStarts }
            .forEach { exec("DELETE FROM time_slots WHERE id=?", it.id) }
        desiredByStart.forEach { (start, label) ->
            if (start !in existingByStart) {
                exec("INSERT INTO time_slots (activity_type, activity_id, label, sort_order) VALUES (?,?,?,(SELECT COALESCE(MAX(sort_order),0)+1 FROM time_slots))",
                    type, activityId, label)
            }
        }
        timeSlots().filter { it.activityType == type && it.activityId == activityId }
            .sortedBy { parseSlotStart(it.label) ?: LocalTime.MAX }
            .forEachIndexed { i, slot -> exec("UPDATE time_slots SET sort_order=? WHERE id=?", i, slot.id) }
    }

    private fun seed() {
        val s = loadSeed()
        tx {
            s.farmers.forEach(::saveFarmer)
            s.tours.forEach(::saveTour)
            s.fruitPicking.forEach(::saveFruit)
            s.products.forEachIndexed { i, p -> saveProduct(p, i) }
            s.bookings.forEachIndexed { i, b -> saveBooking(b, i, validateSchedule = false) }
            s.orders.forEachIndexed { i, o -> saveOrder(o, i) }
            s.notifications.forEachIndexed { i, n -> saveNotification(n, i) }
            s.reviews.forEach(::saveReview)
            assignFarmOwnership()
            linkOrderItems()
            classifyBookings()
            linkSeedReviews()
            s.users.forEach { u ->
                createUser(u.fullName, u.email, u.phone, DEFAULT_PASSWORD, u.role)
                exec("UPDATE users SET address=?, farmer_id=? WHERE lower(email)=lower(?)", u.address, u.farmerId, u.email)
            }
            // Demo tourist's initial cart (mangoes x2, honey x1, banana chips x1) and favorites.
            val maria = Role.USER.email
            listOf(6 to 2, 8 to 1, 9 to 1).forEachIndexed { i, (idx, q) -> setCartItem(maria, s.products[idx].id, q, i) }
            listOf(1L, 2L, 3L).forEach { setFavorite(maria, it, true) }
            exec("UPDATE orders SET customer_email=? WHERE customer_name='Maria Santos'", maria)
            exec("UPDATE notifications SET recipient_email=? WHERE target_role='tourist'", maria)
            exec("UPDATE notifications SET recipient_email=? WHERE target_role='farmer'", Role.FARMER.email)
            exec("UPDATE users SET farmer_id=1 WHERE email=?", Role.FARMER.email)
        }
    }

    // ------------------------------------------------------------------ helpers
    /** Runs block in a transaction; nested calls join the outer transaction. */
    fun <T> tx(block: () -> T): T {
        if (txDepth > 0) { txDepth++; try { return block() } finally { txDepth-- } }
        conn.autoCommit = false
        txDepth = 1
        try {
            val r = block(); conn.commit(); return r
        } catch (e: Throwable) {
            conn.rollback(); throw e
        } finally {
            txDepth = 0
            conn.autoCommit = true
        }
    }

    private fun count(table: String): Int =
        conn.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $table").use { r -> r.next(); r.getInt(1) } }

    private fun exec(sql: String, vararg args: Any?) = conn.prepareStatement(sql).use { it.bind(args); it.executeUpdate() }

    private fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        conn.prepareStatement(sql).use { ps ->
            ps.bind(args)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    private fun PreparedStatement.bind(args: Array<out Any?>) = args.forEachIndexed { i, a ->
        when (a) {
            null -> setObject(i + 1, null)
            is Boolean -> setInt(i + 1, if (a) 1 else 0)
            else -> setObject(i + 1, a)
        }
    }

    private fun ResultSet.str(c: String): String? = getString(c)
    private fun ResultSet.longOrNull(c: String): Long? = getLong(c).let { if (wasNull()) null else it }

    private fun insertSql(table: String, cols: List<String>) =
        "INSERT OR REPLACE INTO $table (${cols.joinToString()}) VALUES (${cols.joinToString { "?" }})"

    private fun existingSort(table: String, id: Any): Int? =
        query("SELECT sort_order FROM $table WHERE id=?", id) { it.getInt(1) }.firstOrNull()

    fun minSort(table: String): Int =
        conn.createStatement().use { it.executeQuery("SELECT COALESCE(MIN(sort_order),0) FROM $table").use { r -> r.next(); r.getInt(1) } }

    /** Stops at JSON/SQL quoting characters but keeps spaces used by legacy uploaded filenames. */
    private val fileReference = Regex("file:[^\"'\\]\\[,]+")

    private fun fileRefsIn(sql: String, vararg args: Any?): Set<String> =
        query(sql, *args) { r ->
            (1..r.metaData.columnCount).joinToString(" ") { r.getString(it).orEmpty() }
        }.flatMap { row -> fileReference.findAll(row).map { it.value.trim() }.toList() }.toSet()

    /**
     * Deletes uploaded images that no remaining row (including historical orders) still uses.
     * Returns paths that could not be deleted so the caller can report an incomplete cleanup.
     */
    private fun deleteUnreferencedUploads(candidates: Set<String>): List<String> {
        val referenced = listOf(
            "SELECT photo_url, farm_banner_url, gallery FROM farmers",
            "SELECT image_url, thumbnails FROM tours",
            "SELECT image_url, thumbnails FROM fruit_picking",
            "SELECT image_url FROM products",
            "SELECT avatar_url FROM users",
            "SELECT uploaded_receipt_url FROM orders",
            "SELECT product_image_url FROM order_items",
            "SELECT uploaded_id_doc, uploaded_receipt_doc FROM bookings",
            "SELECT url FROM listing_images",
        ).flatMap(::fileRefsIn).toSet()
        val uploadRoot = File(file.absoluteFile.parentFile, "uploads").absolutePath
        return candidates.asSequence()
            .filter { it.startsWith("file:") }
            .filter { url ->
                val path = url.removePrefix("file:")
                path.startsWith(uploadRoot, ignoreCase = true) && url !in referenced
            }
            .map { it.removePrefix("file:") }
            .map(::File)
            .filter(File::exists)
            .mapNotNull { f -> if (f.delete() || !f.exists()) null else f.absolutePath }
            .toList()
    }

    // ------------------------------------------------------------------ users
    private fun mapUser(r: ResultSet) = User(r.getLong("id"), r.str("full_name")!!, r.str("email")!!, r.str("phone") ?: "",
        Role.of(r.str("role")!!), r.getInt("farmer_id").takeIf { !r.wasNull() }, r.str("address") ?: "", r.str("avatar_url") ?: "",
        r.getInt("is_active") == 1, r.str("created_at") ?: "",
        r.str("first_name").orEmpty(), r.str("middle_name").orEmpty(), r.str("last_name").orEmpty(),
        r.str("house_no").orEmpty(), r.str("street").orEmpty(), r.str("municipality").orEmpty(), r.str("city").orEmpty(),
        r.str("deleted_at"))

    /** The account that manages a farm, if any. */
    fun farmerAccount(farmerId: Int): User? =
        query("SELECT * FROM users WHERE farmer_id=? AND role='farmer' AND deleted_at IS NULL LIMIT 1", farmerId, map = ::mapUser).firstOrNull()

    /** Creates a farm profile for a newly registered farmer and links the account to it. */
    fun createFarm(userId: Long, ownerName: String, farmName: String, province: String, fallbackImage: String, bannerImage: String = fallbackImage): Int = tx {
        val id = query("SELECT COALESCE(MAX(id),0)+1 FROM farmers") { it.getInt(1) }.first()
        saveFarmer(FarmerProfile(id, ownerName.trim(), "Farm Owner", farmName.trim(), province.trim(), province.trim(), 0,
            fallbackImage, bannerImage, "", "", "", emptyList(), emptyList(), emptyList(), emptyList(), 0, 0.0, 0,
            "1st generation", "—", emptyList()))
        exec("UPDATE users SET farmer_id=? WHERE id=?", id, userId)
        id
    }

    fun findUser(email: String): User? =
        query("SELECT * FROM users WHERE lower(email)=lower(?) AND deleted_at IS NULL", email.trim(), map = ::mapUser).firstOrNull()

    fun users(): List<User> = query("SELECT * FROM users WHERE deleted_at IS NULL ORDER BY role, lower(full_name), id", map = ::mapUser)

    /** Includes soft-deleted accounts; only admin Trash Bin screens should use this. */
    fun allUsers(): List<User> = query("SELECT * FROM users ORDER BY role, lower(full_name), id", map = ::mapUser)

    fun setUserActive(id: Long, active: Boolean) =
        exec("UPDATE users SET is_active=? WHERE id=?", active, id)

    fun activeAdminCount(): Int =
        query("SELECT COUNT(*) FROM users WHERE role='admin' AND is_active=1") { it.getInt(1) }.first()

    /** Inserts a new account; returns null if the email is already registered. */
    fun createUser(
        fullName: String, email: String, phone: String, password: String, role: String,
        firstName: String = "", middleName: String = "", lastName: String = "",
        houseNo: String = "", street: String = "", municipality: String = "", city: String = "",
    ): User? {
        require(role == Role.USER.key || role == Role.FARMER.key) {
            "Administrator accounts must be configured by the system owner."
        }
        if (findUser(email) != null) return null
        val first = firstName.trim().ifBlank { fullName.trim().substringBefore(" ") }
        val last = lastName.trim().ifBlank {
            val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size > 1) parts.last() else ""
        }
        val composed = listOf(first, middleName.trim(), last).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { fullName.trim() }
        exec("""INSERT INTO users (full_name, email, phone, password_hash, role, first_name, middle_name, last_name,
                house_no, street, municipality, city, address) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            composed, email.trim(), phone.trim(), hashPassword(password), role, first, middleName.trim(), last,
            houseNo.trim(), street.trim(), municipality.trim(), city.trim(),
            listOf(houseNo, street, municipality, city).filter { it.isNotBlank() }.joinToString(", "))
        return findUser(email)
    }

    /** Returns the user if the email/password pair matches a stored account. */
    fun authenticate(email: String, password: String): User? {
        val hash = query("SELECT password_hash FROM users WHERE lower(email)=lower(?)", email.trim()) { it.getString(1) }.firstOrNull()
        val user = findUser(email)
        return user?.takeIf { it.isActive && verifyPassword(password, hash) }
    }

    fun updateUserProfile(
        id: Long, fullName: String, phone: String,
        firstName: String = "", middleName: String = "", lastName: String = "",
        houseNo: String = "", street: String = "", municipality: String = "", city: String = "",
    ) {
        val first = firstName.trim().ifBlank { fullName.trim().substringBefore(" ") }
        val last = lastName.trim().ifBlank {
            val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size > 1) parts.last() else ""
        }
        // Keep the supplied display name verbatim. Older callers pass a free-form name (e.g.
        // "Ana R. Reyes") with no structured parts; rebuilding it from first/last would drop
        // the middle initial. Structured forms already compose the same string before calling us.
        val composed = fullName.trim().ifBlank {
            listOf(first, middleName.trim(), last).filter { it.isNotBlank() }.joinToString(" ")
        }
        val hasAddress = listOf(houseNo, street, municipality, city).any { it.isNotBlank() }
        val addressText = listOf(houseNo, street, municipality, city).filter { it.isNotBlank() }.joinToString(", ")
        if (hasAddress) {
            exec("""UPDATE users SET full_name=?, phone=?, first_name=?, middle_name=?, last_name=?,
                    house_no=?, street=?, municipality=?, city=?, address=? WHERE id=?""",
                composed, phone.trim(), first, middleName.trim(), last, houseNo.trim(), street.trim(),
                municipality.trim(), city.trim(), addressText, id)
        } else {
            exec("""UPDATE users SET full_name=?, phone=?, first_name=?, middle_name=?, last_name=?,
                    house_no=?, street=?, municipality=?, city=? WHERE id=?""",
                composed, phone.trim(), first, middleName.trim(), last, houseNo.trim(), street.trim(),
                municipality.trim(), city.trim(), id)
        }
    }

    fun updateUserAvatar(id: Long, url: String) = exec("UPDATE users SET avatar_url=? WHERE id=?", url, id)

    fun updateUserAddress(id: Long, address: String) = exec("UPDATE users SET address=? WHERE id=?", address.trim(), id)

    /**
     * Updates the sign-in email while keeping the account's own bookings, orders, cart,
     * favorites, reviews and notifications linked to it. Returns false for an invalid or
     * already registered email so one tourist can never take over another account's records.
     */
    fun updateUserEmail(id: Long, newEmail: String): Boolean = tx {
        val email = newEmail.trim().lowercase()
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) return@tx false
        val current = query("SELECT email FROM users WHERE id=?", id) { it.getString(1) }.firstOrNull()
            ?: return@tx false
        if (current.equals(email, ignoreCase = true)) return@tx true
        if (findUser(email) != null) return@tx false
        exec("UPDATE users SET email=? WHERE id=?", email, id)
        exec("UPDATE bookings SET customer_email=? WHERE lower(customer_email)=lower(?)", email, current)
        exec("UPDATE orders SET customer_email=? WHERE lower(customer_email)=lower(?)", email, current)
        exec("UPDATE notifications SET recipient_email=? WHERE lower(COALESCE(recipient_email,''))=lower(?)", email, current)
        exec("UPDATE user_cart SET user_email=? WHERE lower(user_email)=lower(?)", email, current)
        exec("UPDATE user_favorites SET user_email=? WHERE lower(user_email)=lower(?)", email, current)
        exec("UPDATE reviews SET user_email=? WHERE lower(COALESCE(user_email,''))=lower(?)", email, current)
        true
    }

    fun userCount(): Int = query("SELECT COUNT(*) FROM users WHERE deleted_at IS NULL") { it.getInt(1) }.first()

    /** Deletes a tourist account while keeping its historical bookings and orders intact. */
    fun deleteTouristAccount(userId: Long): Boolean = tx {
        val user = query("SELECT * FROM users WHERE id=?", userId, map = ::mapUser).firstOrNull() ?: return@tx false
        if (user.role != Role.USER) return@tx false
        exec("DELETE FROM notifications WHERE recipient_email=?", user.email)
        exec("DELETE FROM user_cart WHERE user_email=?", user.email)
        exec("DELETE FROM user_favorites WHERE user_email=?", user.email)
        exec("DELETE FROM users WHERE id=?", userId)
        true
    }

    fun userById(id: Long): User? =
        query("SELECT * FROM users WHERE id=?", id, map = ::mapUser).firstOrNull()

    // -------------------------------------------------------------- listing images (max 5 each)
    fun listingImages(ownerType: String, ownerId: Long, includeDeleted: Boolean = false): List<ListingImage> =
        query("SELECT * FROM listing_images WHERE owner_type=? AND owner_id=? ${if (includeDeleted) "" else "AND deleted_at IS NULL"} ORDER BY sort_order, id",
            ownerType, ownerId) { r ->
            ListingImage(r.getLong("id"), ownerType, ownerId, r.str("url")!!, r.getInt("sort_order"),
                r.getInt("is_cover") == 1, r.str("deleted_at"))
        }

    internal fun insertListingImage(type: String, ownerId: Long, url: String, order: Int, cover: Boolean) =
        exec("INSERT INTO listing_images (owner_type, owner_id, url, sort_order, is_cover) VALUES (?,?,?,?,?)",
            type, ownerId, url, order, cover)

    /** Replaces the gallery in place: unchanged URLs keep their original row ids and cover state. */
    fun replaceListingImages(type: String, ownerId: Long, urls: List<String>, coverIndex: Int = 0) = tx {
        require(type in setOf("tour", "orchard", "product", "farmer_photo", "farm_cover", "farmer_gallery")) {
            "Unsupported image owner: $type"
        }
        require(urls.size <= MAX_IMAGES_PER_LISTING) { "A listing can have at most $MAX_IMAGES_PER_LISTING images." }
        val desired = urls.filter { it.isNotBlank() }.distinct().take(MAX_IMAGES_PER_LISTING)
        val existing = listingImages(type, ownerId, includeDeleted = true)
        val reused = mutableSetOf<Long>()
        desired.forEachIndexed { index, url ->
            val match = existing.firstOrNull { it.url == url && it.id !in reused }
            if (match != null) {
                reused += match.id
                exec("UPDATE listing_images SET sort_order=?, is_cover=?, deleted_at=NULL WHERE id=?", index, index == coverIndex, match.id)
            } else insertListingImage(type, ownerId, url, index, index == coverIndex)
        }
        existing.filter { it.id !in reused }.forEach { exec("DELETE FROM listing_images WHERE id=?", it.id) }
        val cover = desired.getOrNull(coverIndex).orEmpty()
        val gallery = desired.filterIndexed { i, _ -> i != coverIndex }
        when (type) {
            "tour" -> exec("UPDATE tours SET image_url=?, thumbnails=? WHERE id=?", cover, gallery.enc(), ownerId)
            "orchard" -> exec("UPDATE fruit_picking SET image_url=?, thumbnails=? WHERE id=?", cover, gallery.enc(), ownerId)
            "product" -> exec("UPDATE products SET image_url=? WHERE id=?", cover, ownerId)
            "farmer_photo" -> exec("UPDATE farmers SET photo_url=? WHERE id=?", cover, ownerId)
            "farm_cover" -> exec("UPDATE farmers SET farm_banner_url=? WHERE id=?", cover, ownerId)
            "farmer_gallery" -> exec("UPDATE farmers SET gallery=? WHERE id=?", gallery.enc(), ownerId)
        }
        listingImages(type, ownerId)
    }

    // ------------------------------------------------------------------ farm capacity
    fun farmCapacity(farmerId: Int?): Int =
        if (farmerId == null) DEFAULT_FARM_CAPACITY
        else query("SELECT capacity FROM farmers WHERE id=?", farmerId) { it.getInt(1) }.firstOrNull() ?: DEFAULT_FARM_CAPACITY

    /** Pax held by every active (non-cancelled) tour/orchard booking belonging to a farm. */
    fun confirmedPaxForFarm(farmerId: Int?): Int {
        if (farmerId == null) return 0
        val tourIds = query("SELECT id FROM tours WHERE farmer_id=?", farmerId) { it.getInt(1) }
        val orchardIds = query("SELECT id FROM fruit_picking WHERE farmer_id=?", farmerId) { it.getInt(1) }
        if (tourIds.isEmpty() && orchardIds.isEmpty()) return 0
        return bookings().filter {
            it.status != "Cancelled" &&
                (if (it.activityType == "orchard") it.farmId in orchardIds else it.farmId in tourIds)
        }.sumOf { it.participants.coerceAtLeast(0) }
    }

    /** Remaining pax a new booking may use for this farm. */
    fun availablePaxForFarm(farmerId: Int?): Int = farmCapacity(farmerId) - confirmedPaxForFarm(farmerId)

    /** A farm's capacity can never be set below the pax it has already confirmed. */
    fun setFarmCapacity(farmerId: Int, capacity: Int) = tx {
        require(capacity > 0) { "Capacity must be a positive whole number." }
        val used = confirmedPaxForFarm(farmerId)
        require(capacity >= used) { "Capacity cannot be lower than the $used confirmed pax." }
        exec("UPDATE farmers SET capacity=? WHERE id=?", capacity, farmerId)
    }

    // -------------------------------------------------------- farmer trash bin (reversible delete)
    /** Moves a farmer and every linked listing/image to Trash without deleting any record. */
    fun softDeleteFarmerAccount(userId: Long): Boolean = tx {
        val user = userById(userId) ?: return@tx false
        if (user.role != Role.FARMER || user.deletedAt != null) return@tx false
        val now = java.time.Instant.now().toString()
        exec("UPDATE users SET deleted_at=? WHERE id=?", now, userId)
        val farmerId = user.farmerId ?: return@tx true
        exec("UPDATE farmers SET deleted_at=? WHERE id=?", now, farmerId)
        listOf("tours", "fruit_picking", "products").forEach { table ->
            exec("UPDATE $table SET archived_before_delete=archived, archived=1, deleted_at=? WHERE farmer_id=? AND deleted_at IS NULL", now, farmerId)
        }
        exec("""UPDATE listing_images SET deleted_at=? WHERE owner_type IN ('tour','orchard','product','farmer_photo','farm_cover','farmer_gallery')
            AND (owner_id=? OR owner_id IN (SELECT id FROM tours WHERE farmer_id=?)
            OR owner_id IN (SELECT id FROM fruit_picking WHERE farmer_id=?)
            OR owner_id IN (SELECT id FROM products WHERE farmer_id=?))""",
            now, farmerId, farmerId, farmerId, farmerId)
        true
    }

    /** Restores every original row and id; nothing is duplicated or recreated. */
    fun restoreFarmerAccount(userId: Long): Boolean = tx {
        val user = userById(userId) ?: return@tx false
        if (user.role != Role.FARMER || user.deletedAt == null) return@tx false
        exec("UPDATE users SET deleted_at=NULL WHERE id=?", userId)
        val farmerId = user.farmerId ?: return@tx true
        exec("UPDATE farmers SET deleted_at=NULL WHERE id=?", farmerId)
        listOf("tours", "fruit_picking", "products").forEach { table ->
            exec("UPDATE $table SET archived=archived_before_delete, deleted_at=NULL, archived_before_delete=0 WHERE farmer_id=? AND deleted_at IS NOT NULL", farmerId)
        }
        exec("""UPDATE listing_images SET deleted_at=NULL WHERE owner_type IN ('tour','orchard','product','farmer_photo','farm_cover','farmer_gallery')
            AND (owner_id=? OR owner_id IN (SELECT id FROM tours WHERE farmer_id=?)
            OR owner_id IN (SELECT id FROM fruit_picking WHERE farmer_id=?)
            OR owner_id IN (SELECT id FROM products WHERE farmer_id=?))""",
            farmerId, farmerId, farmerId, farmerId)
        true
    }

    /**
     * Admin-facing delete: permanently removes the farmer account and every record it owns.
     * The Trash Bin remains available for accounts that were moved there by the older flow.
     */
    fun deleteFarmerAccount(userId: Long): Boolean = deleteFarmerAccountCascade(userId).accountDeleted

    fun deletedFarmers(): List<TrashFarmer> =
        query("SELECT * FROM users WHERE role='farmer' AND deleted_at IS NOT NULL ORDER BY deleted_at DESC, id", map = ::mapUser).map { user ->
            val farmerId = user.farmerId
            val farm = farmerId?.let { id -> allFarmers().firstOrNull { it.id == id } }
            val tours = if (farmerId == null) emptyList() else tours().filter { it.farmerId == farmerId }
            val orchards = if (farmerId == null) emptyList() else fruitPicking().filter { it.farmerId == farmerId }
            val products = if (farmerId == null) emptyList() else products().filter { it.farmerId == farmerId }
            val images = buildList {
                if (farmerId != null) {
                    addAll(listingImages("farmer_photo", farmerId.toLong(), includeDeleted = true))
                    addAll(listingImages("farm_cover", farmerId.toLong(), includeDeleted = true))
                }
                tours.forEach { addAll(listingImages("tour", it.id.toLong(), includeDeleted = true)) }
                orchards.forEach { addAll(listingImages("orchard", it.id.toLong(), includeDeleted = true)) }
                products.forEach { addAll(listingImages("product", it.id, includeDeleted = true)) }
            }
            TrashFarmer(user, farm, tours, orchards, products, images, user.deletedAt!!)
        }

    /**
     * Removes the farmer, its farm profile, every listing it owns and every dependent record in one
     * transaction. Uploaded files are cleaned up only after the commit, and only when no remaining
     * row (including another farmer's listing or a historical order) still references them.
     * Bookings and orders stay as historical transaction records.
     */
    fun deleteFarmerAccountCascade(userId: Long): FarmerDeletionResult {
        val uploads = mutableSetOf<String>()
        val deleted = tx {
            val user = userById(userId) ?: return@tx false
            if (user.role != Role.FARMER) return@tx false
            user.farmerId?.let { farmerId ->
                val tourIds = query("SELECT id FROM tours WHERE farmer_id=?", farmerId) { it.getInt(1) }
                val orchardIds = query("SELECT id FROM fruit_picking WHERE farmer_id=?", farmerId) { it.getInt(1) }
                val productIds = query("SELECT id FROM products WHERE farmer_id=?", farmerId) { it.getLong(1) }

                // Collect every file URL before any row disappears so the cleanup can be exact.
                uploads += fileRefsIn("SELECT photo_url, farm_banner_url, gallery FROM farmers WHERE id=?", farmerId)
                uploads += fileRefsIn("SELECT image_url, thumbnails FROM tours WHERE farmer_id=?", farmerId)
                uploads += fileRefsIn("SELECT image_url, thumbnails FROM fruit_picking WHERE farmer_id=?", farmerId)
                uploads += fileRefsIn("SELECT image_url FROM products WHERE farmer_id=?", farmerId)
                uploads += listingImages("farmer_photo", farmerId.toLong(), true).map { it.url }
                uploads += listingImages("farm_cover", farmerId.toLong(), true).map { it.url }
                uploads += listingImages("farmer_gallery", farmerId.toLong(), true).map { it.url }
                tourIds.forEach { uploads += listingImages("tour", it.toLong(), true).map { img -> img.url } }
                orchardIds.forEach { uploads += listingImages("orchard", it.toLong(), true).map { img -> img.url } }
                productIds.forEach { uploads += listingImages("product", it, true).map { img -> img.url } }

                exec("DELETE FROM listing_images WHERE owner_id=? AND owner_type IN ('farmer_photo','farm_cover','farmer_gallery')", farmerId)
                tourIds.forEach { exec("DELETE FROM listing_images WHERE owner_type='tour' AND owner_id=?", it) }
                orchardIds.forEach { exec("DELETE FROM listing_images WHERE owner_type='orchard' AND owner_id=?", it) }
                productIds.forEach { exec("DELETE FROM listing_images WHERE owner_type='product' AND owner_id=?", it) }

                // Listings themselves are removed; bookings/orders remain as historical records.
                exec("DELETE FROM tours WHERE farmer_id=?", farmerId)
                exec("DELETE FROM fruit_picking WHERE farmer_id=?", farmerId)
                exec("DELETE FROM products WHERE farmer_id=?", farmerId)

                tourIds.forEach {
                    exec("DELETE FROM time_slots WHERE activity_type='tour' AND activity_id=?", it)
                    exec("DELETE FROM user_favorites WHERE item_id=?", it.toLong())
                    exec("DELETE FROM reviews WHERE target_type='tour' AND target_id=?", it)
                }
                orchardIds.forEach {
                    exec("DELETE FROM time_slots WHERE activity_type='orchard' AND activity_id=?", it)
                    exec("DELETE FROM user_favorites WHERE item_id=?", it.toLong() + 100)
                    exec("DELETE FROM reviews WHERE target_type='orchard' AND target_id=?", it)
                }
                productIds.forEach { exec("DELETE FROM user_cart WHERE product_id=?", it) }

                exec("DELETE FROM guides WHERE farmer_id=?", farmerId)
                exec("DELETE FROM farmers WHERE id=?", farmerId)
            }
            uploads += fileRefsIn("SELECT avatar_url FROM users WHERE id=?", userId)
            exec("DELETE FROM notifications WHERE recipient_email=?", user.email)
            exec("DELETE FROM user_cart WHERE user_email=?", user.email)
            exec("DELETE FROM user_favorites WHERE user_email=?", user.email)
            exec("DELETE FROM users WHERE id=?", userId)
            true
        }
        if (!deleted) return FarmerDeletionResult(false)
        return FarmerDeletionResult(true, deleteUnreferencedUploads(uploads))
    }

    /** Trash Bin action: only an account already in Trash can be emptied permanently. */
    fun permanentlyDeleteFarmerAccount(userId: Long): Boolean {
        val user = userById(userId) ?: return false
        if (user.role != Role.FARMER || user.deletedAt == null) return false
        return deleteFarmerAccountCascade(userId).accountDeleted
    }

    // ------------------------------------------------------------------ farmers / tours / fruit
    private fun saveFarmer(f: FarmerProfile) = exec(
        insertSql("farmers", listOf("id", "name", "role_title", "farm_name", "location", "province", "years_experience",
            "photo_url", "farm_banner_url", "story_quote", "full_biography", "farming_philosophy", "certifications",
            "sustainable_practices", "specialty_produce", "product_ids", "tour_id", "rating", "reviews_count",
            "generation", "hectares", "gallery", "capacity", "deleted_at")),
        f.id, f.name, f.roleTitle, f.farmName, f.location, f.province, f.yearsExperience, f.photoUrl, f.farmBannerUrl,
        f.storyQuote, f.fullBiography, f.farmingPhilosophy, f.certifications.enc(), f.sustainablePractices.enc(),
        f.specialtyProduce.enc(), json.encodeToString(longList, f.productIds), f.tourId, f.rating, f.reviewsCount,
        f.generation, f.hectares, f.gallery.enc(), f.capacity.coerceAtLeast(1), f.deletedAt,
    )

    fun updateFarmerPhoto(id: Int, url: String) = exec("UPDATE farmers SET photo_url=? WHERE id=?", url, id)
    fun updateFarmerBanner(id: Int, url: String) = exec("UPDATE farmers SET farm_banner_url=? WHERE id=?", url, id)
    fun updateFarmerStory(id: Int, storyQuote: String, biography: String) =
        exec("UPDATE farmers SET story_quote=?, full_biography=? WHERE id=?", storyQuote.trim(), biography.trim(), id)

    fun updateFarmerDetails(id: Int, ownerName: String, farmName: String, location: String, province: String): Int = tx {
        val updated = exec(
            "UPDATE farmers SET name=?, farm_name=?, location=?, province=? WHERE id=?",
            ownerName.trim(), farmName.trim(), location.trim(), province.trim(), id,
        )
        if (updated == 0) return@tx 0
        exec("UPDATE users SET full_name=? WHERE farmer_id=? AND role='farmer'", ownerName.trim(), id)
        exec("UPDATE tours SET farmer_name=?, location=? WHERE farmer_id=?", ownerName.trim(), location.trim(), id)
        exec("UPDATE fruit_picking SET farm_name=?, location=? WHERE farmer_id=?", farmName.trim(), location.trim(), id)
        exec("UPDATE products SET farm_origin=? WHERE farmer_id=?", "$farmName (${province.trim()})", id)
        updated
    }

    /** Adds a tour owned by a farm; the farm's main tour is set if it has none. Returns the new id. */
    fun createTour(t: FarmTour): Int = tx {
        val id = query("SELECT COALESCE(MAX(id),0)+1 FROM tours") { it.getInt(1) }.first()
        saveTour(t.copy(id = id))
        t.farmerId?.let { exec("UPDATE farmers SET tour_id=? WHERE id=? AND (tour_id IS NULL OR tour_id=0)", id, it) }
        id
    }

    /** Adds a fruit-picking orchard owned by a farm. Returns the new id. */
    fun createOrchard(f: FruitPickingItem): Int = tx {
        val id = query("SELECT COALESCE(MAX(id),0)+1 FROM fruit_picking") { it.getInt(1) }.first()
        saveFruit(f.copy(id = id))
        id
    }

    /** Short farm label such as "Sunnyvale Farm" (first two words of the farm name). */
    private fun farmKey(farmName: String) = farmName.split(" ").take(2).joinToString(" ")

    /**
     * Links catalog rows to their farm: a farmer's tour (farmers.tour_id), orchards and products whose
     * farm name/origin starts with the farm's name. Unmatched rows stay platform-managed (admin only).
     */
    private fun assignFarmOwnership() = farmers().forEach { f ->
        val key = farmKey(f.farmName) + "%"
        if (f.tourId > 0) exec("UPDATE tours SET farmer_id=? WHERE id=? AND farmer_id IS NULL", f.id, f.tourId)
        exec("UPDATE fruit_picking SET farmer_id=? WHERE farmer_id IS NULL AND farm_name LIKE ?", f.id, key)
        exec("UPDATE products SET farmer_id=? WHERE farmer_id IS NULL AND farm_origin LIKE ?", f.id, key)
    }

    /** Older orders stored only product names; match each to the catalog product sharing the most words. */
    private fun linkOrderItems() {
        val words = { t: String -> t.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet() }
        val catalog = products().map { it.id to words(it.name) }
        query("SELECT id, product_name FROM order_items WHERE product_id IS NULL") { it.getLong(1) to it.getString(2) }.forEach { (rowId, name) ->
            val w = words(name)
            val best = catalog.map { (pid, pw) -> pid to (w intersect pw).size }.filter { it.second >= 2 }.maxByOrNull { it.second }
            if (best != null) exec("UPDATE order_items SET product_id=? WHERE id=?", best.first, rowId)
        }
    }

    private fun farmerRows(includeDeleted: Boolean = false): List<FarmerProfile> = query(
        "SELECT * FROM farmers ${if (includeDeleted) "" else "WHERE deleted_at IS NULL"} ORDER BY id"
    ) { r ->
        FarmerProfile(
            r.getInt("id"), r.str("name")!!, r.str("role_title")!!, r.str("farm_name")!!, r.str("location")!!,
            r.str("province")!!, r.getInt("years_experience"), r.str("photo_url")!!, r.str("farm_banner_url")!!,
            r.str("story_quote")!!, r.str("full_biography")!!, r.str("farming_philosophy")!!,
            r.str("certifications")!!.decList(), r.str("sustainable_practices")!!.decList(),
            r.str("specialty_produce")!!.decList(), json.decodeFromString(longList, r.str("product_ids")!!),
            r.getInt("tour_id"), r.getDouble("rating"), r.getInt("reviews_count"), r.str("generation")!!,
            r.str("hectares")!!, r.str("gallery")!!.decList(),
            r.getInt("capacity").takeIf { !r.wasNull() } ?: Database.DEFAULT_FARM_CAPACITY, r.str("deleted_at"),
        )
    }

    fun farmers(): List<FarmerProfile> = farmerRows(false)

    /** Includes farmers in the Trash Bin; only admin screens should use this. */
    fun allFarmers(): List<FarmerProfile> = farmerRows(true)

    private fun saveTour(t: FarmTour) = exec(
        insertSql("tours", listOf("id", "name", "location", "rating", "reviews_count", "price_per_person",
            "operating_hours", "amenities", "activities", "image_url", "description", "highlight_tag", "farmer_name",
            "thumbnails", "farmer_id", "archived", "estimated_duration", "price_approved", "suggested_price_per_person",
            "price_suggestion_basis", "price_suggestion_source", "price_status", "price_type", "booking_enabled", "experience_type")),
        t.id, t.name, t.location, t.rating, t.reviewsCount, t.pricePerPerson, t.operatingHours, t.amenities.enc(),
        t.activities.enc(), t.imageUrl, t.description, t.highlightTag, t.farmerName, t.thumbnails.enc(), t.farmerId, t.archived,
        t.estimatedDuration, t.priceApproved, t.suggestedPricePerPerson, t.priceSuggestionBasis, t.priceSuggestionSource,
        t.priceStatus.name, t.priceType.name, t.bookingEnabled, t.experienceType,
    )

    /** Updates tour details in place while preserving ratings, reviews and booking references. */
    fun updateTour(t: FarmTour) = exec(
        """UPDATE tours SET name=?, location=?, price_per_person=?, operating_hours=?, amenities=?, activities=?,
           image_url=?, description=?, highlight_tag=?, farmer_name=?, thumbnails=?, estimated_duration=?, farmer_id=?,
           price_approved=?, suggested_price_per_person=?, price_suggestion_basis=?, price_suggestion_source=?,
           price_status=?, price_type=?, booking_enabled=?, experience_type=?
           WHERE id=?""",
        t.name, t.location, t.pricePerPerson, t.operatingHours, t.amenities.enc(), t.activities.enc(), t.imageUrl,
        t.description, t.highlightTag, t.farmerName, t.thumbnails.enc(), t.estimatedDuration, t.farmerId, t.priceApproved,
        t.suggestedPricePerPerson, t.priceSuggestionBasis, t.priceSuggestionSource, t.priceStatus.name, t.priceType.name,
        t.bookingEnabled, t.experienceType, t.id,
    )

    /** Soft-deletes (or restores) a tour, orchard or product so history that points at it keeps working. */
    fun setArchived(table: String, id: Long, archived: Boolean) {
        require(table in setOf("tours", "fruit_picking", "products"))
        exec("UPDATE $table SET archived=? WHERE id=?", archived, id)
        if (table == "products" && archived) exec("DELETE FROM user_cart WHERE product_id=?", id)
    }

    fun tours(): List<FarmTour> = query("SELECT * FROM tours ORDER BY id") { r ->
        val suggestedPrice = r.getInt("suggested_price_per_person").takeUnless { r.wasNull() }
        val priceStatus = runCatching { TourPriceStatus.valueOf(r.str("price_status") ?: "") }
            .getOrDefault(TourPriceStatus.UNVERIFIED)
        val priceType = runCatching { TourPriceType.valueOf(r.str("price_type") ?: "") }
            .getOrDefault(TourPriceType.PER_PERSON)
        FarmTour(
            r.getInt("id"), r.str("name")!!, r.str("location")!!, r.getDouble("rating"), r.getInt("reviews_count"),
            r.getInt("price_per_person"), r.str("operating_hours")!!, r.str("amenities")!!.decList(),
            r.str("activities")!!.decList(), r.str("image_url")!!, r.str("description")!!, r.str("highlight_tag")!!,
            r.str("farmer_name")!!, r.str("thumbnails")!!.decList(), r.getInt("farmer_id").takeIf { !r.wasNull() },
            r.getInt("archived") == 1, r.str("estimated_duration") ?: "", r.getInt("price_approved") == 1,
            suggestedPrice, r.str("price_suggestion_basis") ?: "", r.str("price_suggestion_source") ?: "",
            priceStatus, priceType, r.getInt("booking_enabled") == 1, r.str("experience_type") ?: "tour",
        )
    }

    private fun saveFruit(f: FruitPickingItem) = exec(
        insertSql("fruit_picking", listOf("id", "title", "farm_name", "location", "fruit_name", "price_per_person",
            "rating", "reviews_count", "duration", "seasonal_fruits", "image_url", "description", "season_availability", "thumbnails", "farmer_id", "archived")),
        f.id, f.title, f.farmName, f.location, f.fruitName, f.pricePerPerson, f.rating, f.reviewsCount, f.duration,
        f.seasonalFruits, f.imageUrl, f.description, f.seasonAvailability, f.thumbnails.enc(), f.farmerId, f.archived,
    )

    /** Updates an orchard's editable fields (keeps rating, reviews and owner). */
    fun updateOrchard(f: FruitPickingItem) = exec(
        "UPDATE fruit_picking SET title=?, farm_name=?, location=?, fruit_name=?, seasonal_fruits=?, price_per_person=?, duration=?, season_availability=?, image_url=?, description=?, farmer_id=? WHERE id=?",
        f.title, f.farmName, f.location, f.fruitName, f.seasonalFruits, f.pricePerPerson, f.duration, f.seasonAvailability,
        f.imageUrl, f.description, f.farmerId, f.id,
    )

    fun fruitPicking(): List<FruitPickingItem> = query("SELECT * FROM fruit_picking ORDER BY id") { r ->
        FruitPickingItem(
            r.getInt("id"), r.str("title")!!, r.str("farm_name")!!, r.str("location")!!, r.str("fruit_name")!!,
            r.getInt("price_per_person"), r.getDouble("rating"), r.getInt("reviews_count"), r.str("duration")!!,
            r.str("seasonal_fruits")!!, r.str("image_url")!!, r.str("description")!!, r.str("season_availability")!!,
            r.str("thumbnails")!!.decList(), r.getInt("farmer_id").takeIf { !r.wasNull() },
            r.getInt("archived") == 1,
        )
    }

    // ------------------------------------------------------------------ products
    fun saveProduct(p: Product, sortOrder: Int) = exec(
        insertSql("products", listOf("id", "name", "category", "price", "unit", "discount_percent", "stock", "rating",
            "reviews_count", "image_url", "description", "farm_origin", "sort_order", "farmer_id", "archived")),
        p.id, p.name, p.category, p.price, p.unit, p.discountPercent, p.stock, p.rating, p.reviewsCount, p.imageUrl,
        p.description, p.farmOrigin, sortOrder, p.farmerId, p.archived,
    )

    /** Updates a product's editable fields in place (keeps sort order, rating and cart/order links). */
    fun updateProduct(p: Product) = exec(
        "UPDATE products SET name=?, category=?, price=?, unit=?, discount_percent=?, stock=?, image_url=?, description=?, farm_origin=?, farmer_id=? WHERE id=?",
        p.name, p.category, p.price, p.unit, p.discountPercent, p.stock, p.imageUrl, p.description, p.farmOrigin, p.farmerId, p.id,
    )

    fun updateStock(productId: Long, stock: Int) = exec("UPDATE products SET stock=? WHERE id=?", stock, productId)

    fun products(): List<Product> = query("SELECT * FROM products ORDER BY sort_order, id") { r ->
        Product(
            r.getLong("id"), r.str("name")!!, r.str("category")!!, r.getDouble("price"), r.str("unit")!!,
            r.getInt("discount_percent"), r.getInt("stock"), r.getDouble("rating"), r.getInt("reviews_count"),
            r.str("image_url")!!, r.str("description")!!, r.str("farm_origin")!!, r.getInt("farmer_id").takeIf { !r.wasNull() },
            r.getInt("archived") == 1,
        )
    }

    // ------------------------------------------------------------------ bookings
    /** Thrown when a booking would give a customer two active reservations in the same slot. */
    class DoubleBookingException(val existing: Booking) : RuntimeException("Double booking with #${existing.bookingCode}")
    class InvalidBookingScheduleException(message: String) : RuntimeException(message)
    class InvalidBookingDateException : RuntimeException("Choose a booking date from today through December 31 of next year.")
    class InvalidBookingPaymentMethodException : RuntimeException("Farm bookings must use Pay at the Farm.")
    class InvalidTourPriceException : RuntimeException("This tour has an invalid price.")
    class CapacityExceededException(message: String) : RuntimeException(message)

    private fun farmerIdForBooking(b: Booking): Int? = when (b.activityType) {
        "tour" -> tours().firstOrNull { it.id == b.farmId }?.farmerId
        "orchard" -> fruitPicking().firstOrNull { it.id == b.farmId }?.farmerId
        else -> null
    }

    fun bookingScheduleError(activityType: String, activityId: Int, label: String): String? {
        val slots = timeSlots()
        val activity = when (activityType) {
            "tour" -> tours().firstOrNull { it.id == activityId && !it.archived }
            "orchard" -> {
                val orchard = fruitPicking().firstOrNull { it.id == activityId && !it.archived }
                val farmTour = orchard?.farmerId?.let { farmerId ->
                    val farmer = farmers().firstOrNull { it.id == farmerId }
                    farmer?.tourId?.takeIf { it > 0 }?.let { tourId -> tours().firstOrNull { it.id == tourId } }
                        ?: tours().firstOrNull { it.farmerId == farmerId }
                } ?: orchard?.let { item ->
                    tours().firstOrNull { it.name.startsWith(item.farmName, ignoreCase = true) }
                }
                farmTour
            }
            else -> null
        }
        val saved = query("SELECT value FROM settings WHERE key=?", "slot_config.$activityType.$activityId") { it.getString(1) }
            .firstOrNull()?.split("|")?.takeIf { it.size >= 2 }
        val hours = saved?.let { "${it[0]} - ${it[1]}" } ?: activity?.operatingHours
        val duration = if (activityType == "orchard") fruitPicking().firstOrNull { it.id == activityId && !it.archived }?.duration else null
        return bookingScheduleError(activityType, activityId, label, hours, duration, slots)
    }

    fun bookingScheduleError(booking: Booking): String? =
        bookingScheduleError(booking.activityType, booking.farmId, booking.tourTime)

    /** Another active booking by the same customer at the same date and time, if any. */
    fun conflictingBooking(b: Booking): Booking? {
        if (b.status == "Cancelled") return null
        val id = query("SELECT id FROM bookings WHERE slot_key=? AND status <> 'Cancelled' AND id <> ?", b.slotKey(), b.id) { it.getLong(1) }.firstOrNull()
        return id?.let { cid -> bookings().firstOrNull { it.id == cid } }
    }

    fun saveBooking(b: Booking, sortOrder: Int? = null, validateSchedule: Boolean = true) {
        if (b.paymentMethod != PAY_AT_FARM_PAYMENT_METHOD) throw InvalidBookingPaymentMethodException()
        if (b.participants < 1) throw IllegalArgumentException("Number of persons (pax) must be a positive whole number.")
        val existingDate = query("SELECT tour_date FROM bookings WHERE id=? AND booking_code=?", b.id, b.bookingCode) { it.getString(1) }.firstOrNull()
        val existingBooking = existingDate != null
        if (b.status != "Cancelled") {
            val farmerId = farmerIdForBooking(b)
            val previous = if (existingBooking) bookings().firstOrNull { it.id == b.id } else null
            val ownPax = if (previous != null && previous.status != "Cancelled" && farmerIdForBooking(previous) == farmerId) previous.participants else 0
            val capacity = farmCapacity(farmerId)
            val available = capacity - (confirmedPaxForFarm(farmerId) - ownPax)
            if (available < b.participants) {
                throw CapacityExceededException("Only $available slot(s) are available for this farm. Reduce the number of persons.")
            }
        }
        if (validateSchedule && (!existingBooking || existingDate != b.tourDate)) {
            val date = runCatching { LocalDate.parse(b.tourDate) }.getOrNull()
            if (date == null || !BookingDateWindow.current().contains(date)) throw InvalidBookingDateException()
        }
        when (b.activityType) {
            "tour" -> {
                val tour = tours().firstOrNull { it.id == b.farmId && (existingBooking || !it.archived) }
                    ?: throw InvalidBookingScheduleException("This tour is unavailable.")
                if (!existingBooking && !tour.bookingEnabled) throw InvalidBookingScheduleException("Bookings for this tour are currently unavailable.")
                if (tour.pricePerPerson < 0) throw InvalidTourPriceException()
            }
            "orchard" -> {
                val orchard = fruitPicking().firstOrNull { it.id == b.farmId && (existingBooking || !it.archived) }
                    ?: throw InvalidBookingScheduleException("This fruit-picking experience is unavailable.")
            }
        }
        if (validateSchedule) bookingScheduleError(b)?.let { throw InvalidBookingScheduleException(it) }
        conflictingBooking(b)?.let { throw DoubleBookingException(it) }
        val order = sortOrder ?: existingSort("bookings", b.id) ?: (minSort("bookings") - 1)
        val cols = listOf("id", "booking_code", "farm_id", "farm_name", "customer_name", "customer_phone",
            "customer_email", "activity", "tour_date", "tour_time", "participants", "total_amount", "payment_method",
            "status", "attendance_status", "assigned_guide", "special_requests", "uploaded_id_doc",
            "uploaded_receipt_doc", "checked_in_at", "sort_order", "activity_type", "slot_key")
        // Upsert on id only: INSERT OR REPLACE would silently delete a row that clashes on the slot index.
        exec(
            "INSERT INTO bookings (${cols.joinToString()}) VALUES (${cols.joinToString { "?" }}) " +
                "ON CONFLICT(id) DO UPDATE SET ${cols.drop(1).joinToString { "$it=excluded.$it" }}",
            b.id, b.bookingCode, b.farmId, b.farmName, b.customerName, b.customerPhone, b.customerEmail, b.activity,
            b.tourDate, b.tourTime, b.participants, b.totalAmount, b.paymentMethod, b.status, b.attendanceStatus,
            b.assignedGuide, b.specialRequests, b.uploadedIdDoc, b.uploadedReceiptDoc, b.checkedInAt, order,
            b.activityType, b.slotKey(),
        )
    }

    /** Older rows have no activity type: fruit-picking/harvest bookings point at fruit_picking ids. */
    private fun classifyBookings() = exec(
        """UPDATE bookings SET activity_type='orchard'
           WHERE activity LIKE '%Picking%' OR activity LIKE '%Harvest%'
              OR activity IN (SELECT title FROM fruit_picking WHERE fruit_picking.id=bookings.farm_id)""",
    )

    /** The bundled sample reviews are about specific tours. */
    private fun linkSeedReviews() {
        listOf(1 to 1, 2 to 2, 3 to 3).forEach { (reviewId, tourId) ->
            exec("UPDATE reviews SET target_type='tour', target_id=? WHERE id=? AND target_type IS NULL AND booking_id IS NULL", tourId, reviewId)
        }
    }

    fun deleteBooking(id: Long) = exec("DELETE FROM bookings WHERE id=?", id)

    fun deleteNotificationsForBooking(bookingId: Long) = exec("DELETE FROM notifications WHERE booking_id=?", bookingId)

    fun bookings(): List<Booking> = query("SELECT * FROM bookings ORDER BY sort_order, id") { r ->
        Booking(
            r.getLong("id"), r.str("booking_code")!!, r.getInt("farm_id"), r.str("farm_name")!!, r.str("customer_name")!!,
            r.str("customer_phone"), r.str("customer_email"), r.str("activity")!!, r.str("tour_date")!!,
            r.str("tour_time")!!, r.getInt("participants"), r.getInt("total_amount"), r.str("payment_method"),
            r.str("status")!!, r.str("attendance_status"), r.str("assigned_guide"), r.str("special_requests"),
            r.str("uploaded_id_doc"), r.str("uploaded_receipt_doc"), r.str("checked_in_at"),
            r.str("activity_type") ?: "tour",
        )
    }

    // ------------------------------------------------------------------ orders
    class StockUnavailableException(message: String) : RuntimeException(message)

    fun saveOrder(o: FarmerOrder, sortOrder: Int? = null) = tx {
        if (o.paymentMethod != COD_PAYMENT_METHOD) throw IllegalArgumentException("Marketplace orders must use Cash on Delivery.")
        require(o.fulfillmentStatus in setOf("Pending", "Confirmed", "Cancelled")) {
            "Marketplace orders must use Pending, Confirmed, or Cancelled status."
        }
        val order = sortOrder ?: existingSort("orders", o.id) ?: (minSort("orders") - 1)
        exec("DELETE FROM order_items WHERE order_id=?", o.id)
        exec("DELETE FROM delivery_steps WHERE order_id=?", o.id)
        exec(
            insertSql("orders", listOf("id", "order_number", "customer_name", "customer_phone", "customer_address",
                "total_amount", "payment_method", "payment_status", "fulfillment_status", "order_date",
                "uploaded_receipt_url", "special_delivery_notes", "courier_name", "rider_name", "rider_phone",
                "rider_vehicle", "current_location", "estimated_arrival", "tracking_number", "is_received_by_user",
                "received_at", "received_by_recipient", "sort_order", "customer_email")),
            o.id, o.orderNumber, o.customerName, o.customerPhone, o.customerAddress, o.totalAmount, o.paymentMethod,
            o.paymentStatus, o.fulfillmentStatus, o.orderDate, o.uploadedReceiptUrl, o.specialDeliveryNotes,
            o.courierName, o.riderName, o.riderPhone, o.riderVehicle, o.currentLocation, o.estimatedArrival,
            o.trackingNumber, o.isReceivedByUser, o.receivedAt, o.receivedByRecipient, order, o.customerEmail,
        )
        o.items.forEach {
            exec(
                "INSERT INTO order_items (order_id, product_name, qty, unit_price, product_id, product_image_url, seller_name) VALUES (?,?,?,?,?,?,?)",
                o.id, it.productName, it.qty, it.unitPrice, it.productId, it.productImageUrl, it.sellerName,
            )
        }
        o.deliverySteps.forEachIndexed { i, s ->
            exec("INSERT INTO delivery_steps (order_id, step_index, title, location, timestamp, completed, notes) VALUES (?,?,?,?,?,?,?)",
                o.id, i, s.title, s.location, s.timestamp, s.completed, s.notes)
        }
    }

    /** Reserves all order stock and saves the order atomically to avoid overselling. */
    fun saveOrderWithStock(o: FarmerOrder, sortOrder: Int? = null) = tx {
        require(o.fulfillmentStatus == "Pending") { "New marketplace orders must start as Pending." }
        val grouped = o.items.groupBy { item ->
            item.productId ?: throw IllegalArgumentException("Marketplace order items must reference a product.")
        }
        grouped.forEach { (productId, items) ->
            val quantity = items.sumOf { it.qty }
            require(quantity > 0) { "Marketplace order quantities must be positive." }
            val changed = exec(
                "UPDATE products SET stock=stock-? WHERE id=? AND archived=0 AND stock>=?",
                quantity, productId, quantity,
            )
            if (changed != 1) {
                val available = query("SELECT stock FROM products WHERE id=?", productId) { it.getInt(1) }.firstOrNull() ?: 0
                throw StockUnavailableException("Only $available unit(s) remain for ${items.first().productName}. Please update your cart.")
            }
        }
        saveOrder(o, sortOrder)
    }

    /** Applies a one-way marketplace status change; cancellation restores reserved inventory once. */
    fun transitionOrderStatus(id: String, status: String, allowConfirmedCancellation: Boolean = false): FarmerOrder? = tx {
        require(status == "Confirmed" || status == "Cancelled") { "Unsupported marketplace order status: $status" }
        val current = query("SELECT fulfillment_status, is_received_by_user FROM orders WHERE id=?", id) {
            it.getString(1) to (it.getInt(2) == 1)
        }.firstOrNull()
            ?: return@tx null
        val allowed = when (status) {
            "Confirmed" -> current.first == "Pending" && !current.second
            "Cancelled" -> (current.first == "Pending" || allowConfirmedCancellation && current.first == "Confirmed") && !current.second
            else -> false
        }
        if (!allowed || exec(
                "UPDATE orders SET fulfillment_status=? WHERE id=? AND fulfillment_status=? AND is_received_by_user=0",
                status, id, current.first,
            ) != 1)
            return@tx null
        if (status == "Cancelled") {
            query(
                "SELECT product_id, SUM(qty) FROM order_items WHERE order_id=? AND product_id IS NOT NULL GROUP BY product_id",
                id,
            ) { it.getLong(1) to it.getInt(2) }.forEach { (productId, quantity) ->
                exec("UPDATE products SET stock=stock+? WHERE id=?", quantity, productId)
            }
        }
        orders().firstOrNull { it.id == id }
    }

    fun updatePendingOrderAddress(id: String, customerEmail: String, address: String): FarmerOrder? = tx {
        val normalized = address.trim()
        require(normalized.length in 5..300) { "Enter a delivery address between 5 and 300 characters." }
        if (exec(
                """UPDATE orders SET customer_address=?
                   WHERE id=? AND lower(customer_email)=lower(?) AND fulfillment_status='Pending' AND is_received_by_user=0""",
                normalized, id, customerEmail.trim(),
            ) != 1)
            return@tx null
        orders().firstOrNull { it.id == id }
    }

    fun orders(): List<FarmerOrder> {
        val items = query("SELECT * FROM order_items ORDER BY id") { r ->
            r.str("order_id")!! to OrderItem(
                r.str("product_name")!!, r.getInt("qty"), r.getDouble("unit_price"), r.longOrNull("product_id"),
                r.str("product_image_url") ?: "", r.str("seller_name") ?: "",
            )
        }.groupBy({ it.first }, { it.second })
        val steps = query("SELECT * FROM delivery_steps ORDER BY order_id, step_index") { r ->
            r.str("order_id")!! to DeliveryCheckpoint(r.str("title")!!, r.str("location")!!, r.str("timestamp")!!, r.getInt("completed") == 1, r.str("notes"))
        }.groupBy({ it.first }, { it.second })
        return query("SELECT * FROM orders ORDER BY sort_order") { r ->
            val id = r.str("id")!!
            FarmerOrder(
                id, r.str("order_number")!!, r.str("customer_name")!!, r.str("customer_phone")!!,
                r.str("customer_address")!!, items[id].orEmpty(), r.getDouble("total_amount"), r.str("payment_method")!!,
                r.str("payment_status")!!, r.str("fulfillment_status")!!, r.str("order_date")!!,
                r.str("uploaded_receipt_url")!!, r.str("special_delivery_notes"), r.str("courier_name"),
                r.str("rider_name"), r.str("rider_phone"), r.str("rider_vehicle"), r.str("current_location"),
                r.str("estimated_arrival"), r.str("tracking_number"), steps[id].orEmpty(),
                r.getInt("is_received_by_user") == 1, r.str("received_at"), r.str("received_by_recipient"),
                r.str("customer_email"),
            )
        }
    }

    /** Only cancelled orders can be permanently removed; active and completed history is retained. */
    fun deleteOrder(id: String): Boolean = tx {
        val status = query("SELECT fulfillment_status FROM orders WHERE id=?", id) { it.getString(1) }.firstOrNull()
            ?: return@tx false
        if (!status.equals("Cancelled", ignoreCase = true)) return@tx false
        exec("DELETE FROM delivery_steps WHERE order_id=?", id)
        exec("DELETE FROM order_items WHERE order_id=?", id)
        exec("DELETE FROM notifications WHERE order_id=?", id)
        exec("DELETE FROM orders WHERE id=?", id)
        true
    }

    // ------------------------------------------------------------------ notifications
    fun saveNotification(n: AppNotification, sortOrder: Int? = null): Boolean {
        val order = sortOrder ?: existingSort("notifications", n.id) ?: (minSort("notifications") - 1)
        val cols = listOf("id", "title", "description", "full_detail", "time_label", "is_read", "type",
            "target_role", "booking_id", "attendance_status", "sort_order", "recipient_email", "order_id", "event_key")
        val values = arrayOf<Any?>(
            n.id, n.title, n.desc, n.fullDetail, n.time, n.read, n.type, n.targetRole, n.bookingId, n.attendanceStatus, order,
            n.recipientEmail, n.orderId, n.eventKey,
        )
        if (n.eventKey == null) return exec(insertSql("notifications", cols), *values) == 1

        val existingId = query(
            "SELECT id FROM notifications WHERE event_key=? AND COALESCE(recipient_email,'')=COALESCE(?, '')",
            n.eventKey, n.recipientEmail,
        ) { it.getLong(1) }.firstOrNull()
        if (existingId != null) {
            if (existingId != n.id) return false
            return exec(
                """UPDATE notifications SET title=?, description=?, full_detail=?, time_label=?, is_read=?, type=?,
                   target_role=?, booking_id=?, attendance_status=?, sort_order=?, recipient_email=?, order_id=?, event_key=?
                   WHERE id=?""",
                n.title, n.desc, n.fullDetail, n.time, n.read, n.type, n.targetRole, n.bookingId, n.attendanceStatus,
                order, n.recipientEmail, n.orderId, n.eventKey, n.id,
            ) == 1
        }
        return exec("INSERT OR IGNORE INTO notifications (${cols.joinToString()}) VALUES (${cols.joinToString { "?" }})", *values) == 1
    }

    fun notifications(): List<AppNotification> = query("SELECT * FROM notifications ORDER BY sort_order") { r ->
        AppNotification(
            r.getLong("id"), r.str("title")!!, r.str("description")!!, r.str("full_detail")!!, r.str("time_label")!!,
            r.getInt("is_read") == 1, r.str("type")!!, r.str("target_role")!!, r.longOrNull("booking_id"),
            r.str("attendance_status"), r.str("recipient_email"), r.str("order_id"), r.str("event_key"),
        )
    }

    // ------------------------------------------------------------------ reviews
    fun saveReview(v: CustomerReview) = exec(
        insertSql("reviews", listOf("id", "author", "role_tag", "rating", "review_date", "farm_or_crop", "comment",
            "user_email", "booking_id", "target_type", "target_id")),
        v.id, v.author, v.roleTag, v.rating, v.date, v.farmOrCrop, v.comment, v.userEmail, v.bookingId, v.targetType, v.targetId,
    )

    fun nextReviewId(): Int = query("SELECT COALESCE(MAX(id),0)+1 FROM reviews") { it.getInt(1) }.first()

    /** Adds a review and folds its rating into the reviewed tour/orchard's average. */
    fun addReview(v: CustomerReview) = tx {
        if (v.bookingId != null && query("SELECT 1 FROM reviews WHERE booking_id=?", v.bookingId) { 1 }.isNotEmpty())
            error("This booking has already been reviewed.")
        saveReview(v)
        val table = when (v.targetType) { "tour" -> "tours"; "orchard" -> "fruit_picking"; else -> null }
        if (table != null && v.targetId != null) exec(
            "UPDATE $table SET rating=ROUND((rating*reviews_count + ?)*1.0/(reviews_count+1), 1), reviews_count=reviews_count+1 WHERE id=?",
            v.rating, v.targetId,
        )
    }

    private fun ratingTable(v: CustomerReview) = when (v.targetType) { "tour" -> "tours"; "orchard" -> "fruit_picking"; else -> null }

    /** Changes a review and shifts the reviewed item's average by the rating difference. */
    fun updateReview(v: CustomerReview, rating: Int, comment: String) = tx {
        exec("UPDATE reviews SET rating=?, comment=? WHERE id=?", rating, comment, v.id)
        val table = ratingTable(v)
        if (table != null && v.targetId != null) exec(
            "UPDATE $table SET rating=ROUND(rating + (? - ?)*1.0/MAX(reviews_count,1), 1) WHERE id=?", rating, v.rating, v.targetId,
        )
    }

    /** Deletes a review and removes its rating from the reviewed item's average. */
    fun deleteReview(v: CustomerReview) = tx {
        exec("DELETE FROM reviews WHERE id=?", v.id)
        val table = ratingTable(v)
        if (table != null && v.targetId != null) exec(
            """UPDATE $table SET rating=CASE WHEN reviews_count<=1 THEN 0 ELSE ROUND((rating*reviews_count - ?)*1.0/(reviews_count-1), 1) END,
               reviews_count=MAX(reviews_count-1,0) WHERE id=?""", v.rating, v.targetId,
        )
    }

    fun reviews(): List<CustomerReview> = query("SELECT * FROM reviews ORDER BY id") { r ->
        CustomerReview(r.getInt("id"), r.str("author")!!, r.str("role_tag")!!, r.getInt("rating"),
            r.str("review_date")!!, r.str("farm_or_crop")!!, r.str("comment")!!,
            r.str("user_email"), r.longOrNull("booking_id"), r.str("target_type"), r.getInt("target_id").takeIf { !r.wasNull() })
    }

    // ------------------------------------------------------------------ cart / favorites
    fun setCartItem(user: String, productId: Long, quantity: Int, sortOrder: Int) {
        if (quantity <= 0) exec("DELETE FROM user_cart WHERE user_email=? AND product_id=?", user, productId)
        else exec("INSERT OR REPLACE INTO user_cart (user_email, product_id, quantity, sort_order) VALUES (?,?,?,?)", user, productId, quantity, sortOrder)
    }

    fun clearCart(user: String) = exec("DELETE FROM user_cart WHERE user_email=?", user)

    fun cart(user: String): List<Pair<Long, Int>> =
        query("SELECT product_id, quantity FROM user_cart WHERE user_email=? ORDER BY sort_order", user) { it.getLong(1) to it.getInt(2) }

    fun setFavorite(user: String, id: Long, fav: Boolean) {
        if (fav) exec("INSERT OR IGNORE INTO user_favorites (user_email, item_id) VALUES (?,?)", user, id)
        else exec("DELETE FROM user_favorites WHERE user_email=? AND item_id=?", user, id)
    }

    fun favorites(user: String): Set<Long> = query("SELECT item_id FROM user_favorites WHERE user_email=?", user) { it.getLong(1) }.toSet()

    // ------------------------------------------------------------------ SQL studio
    data class QueryResult(val columns: List<String>, val rows: List<List<String>>, val message: String)

    fun runSql(sql: String): QueryResult = conn.createStatement().use { st ->
        val hasRs = st.execute(sql)
        if (!hasRs) return QueryResult(emptyList(), emptyList(), "${st.updateCount} row(s) affected")
        st.resultSet.use { rs ->
            val md = rs.metaData
            val cols = (1..md.columnCount).map { md.getColumnLabel(it) }
            val rows = buildList { while (rs.next() && size < 500) add(cols.indices.map { rs.getString(it + 1) ?: "NULL" }) }
            QueryResult(cols, rows, "${rows.size} row(s)")
        }
    }

    fun tableNames(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { it.getString(1) }

    fun schemaSql(): String =
        query("SELECT sql FROM sqlite_master WHERE type='table' AND sql IS NOT NULL ORDER BY name") { it.getString(1) + ";" }
            .joinToString("\n\n")

    fun close() = conn.close()
}
