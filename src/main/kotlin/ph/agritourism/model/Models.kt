package ph.agritourism.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

enum class Role(val key: String, val displayName: String, val subtitle: String, val email: String) {
    ADMIN("admin", "Administrator", "Platform Manager", ""),
    USER("user", "Maria Santos", "Eco Tourist", "maria.santos@gmail.com"),
    FARMER("farmer", "Juan Dela Cruz", "Accredited Farmer", "juan.delacruz@sunnyvalefarm.ph");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: USER
    }
}

const val COD_PAYMENT_METHOD = "Cash on Delivery"
const val PAY_AT_FARM_PAYMENT_METHOD = "Pay at the Farm"

/** Parses ISO (2026-11-20) or display (Nov 20, 2026) dates. */
fun parseDate(d: String): java.time.LocalDate? {
    val t = d.trim()
    return runCatching { java.time.LocalDate.parse(t) }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(t, java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)) }.getOrNull()
}

/** Start time of a slot label, e.g. "08:00 AM - Morning Agro-Walk" -> "08:00 AM". */
fun slotTime(t: String): String = t.substringBefore(" - ").trim().uppercase()

private val SLOT_TIME_FORMATTER = DateTimeFormatterBuilder().parseCaseInsensitive()
    .appendPattern("h:mm a").toFormatter(Locale.ENGLISH)

fun parseSlotStart(label: String): LocalTime? =
    runCatching { LocalTime.parse(label.trim().substringBefore(" - "), SLOT_TIME_FORMATTER) }.getOrNull()

/** Parses a plain clock time such as "08:00 AM", "8:00 am", or "17:00". */
fun parseClockTime(text: String): LocalTime? {
    val t = text.trim()
    return runCatching { LocalTime.parse(t.uppercase(Locale.ENGLISH), SLOT_TIME_FORMATTER) }.getOrNull()
        ?: runCatching { LocalTime.parse(t, DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()
}

/** Formats a time as a 12-hour clock label such as "08:00 AM". */
fun formatClockTime(time: LocalTime): String =
    time.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH))

private fun parseOperatingHours(hours: String): Pair<LocalTime, LocalTime>? {
    val parts = hours.trim().split(Regex("\\s+(?:-|–|to)\\s+", RegexOption.IGNORE_CASE), limit = 2)
    if (parts.size != 2) return null
    val open = runCatching { LocalTime.parse(parts[0], SLOT_TIME_FORMATTER) }.getOrNull() ?: return null
    val close = runCatching { LocalTime.parse(parts[1], SLOT_TIME_FORMATTER) }.getOrNull() ?: return null
    return (open to close).takeIf { open.isBefore(close) }
}

fun parseDurationMinutes(duration: String): Long? {
    val parts = Regex("(\\d+)\\s*(hours?|hrs?|h|minutes?|mins?|m)", RegexOption.IGNORE_CASE)
        .findAll(duration)
        .toList()
    if (parts.isEmpty()) return null
    val minutes = parts.sumOf { match ->
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        if (match.groupValues[2].startsWith("h", true)) amount * 60 else amount
    }
    return minutes.takeIf { it > 0 }
}

const val NO_BOOKING_SCHEDULE_MESSAGE = "This farm has no available tour schedule. Please contact the farm before booking."
const val OUTSIDE_BOOKING_SCHEDULE_MESSAGE = "The selected time is outside the farm's available tour schedule."

/**
 * Time-slot labels contain a start time and a descriptive title. The next configured slot (or
 * closing time) bounds a tour slot; fruit-picking duration is stored separately and must fit
 * before both that boundary and closing time.
 */
fun bookingScheduleError(
    activityType: String,
    activityId: Int,
    selectedLabel: String,
    operatingHours: String?,
    duration: String?,
    slots: List<TimeSlot>,
): String? {
    val hours = operatingHours?.let(::parseOperatingHours) ?: return NO_BOOKING_SCHEDULE_MESSAGE
    val activitySlots = slots.filter { it.activityType == activityType && it.activityId == activityId }
    if (activitySlots.isEmpty()) return NO_BOOKING_SCHEDULE_MESSAGE
    val start = parseSlotStart(selectedLabel) ?: return OUTSIDE_BOOKING_SCHEDULE_MESSAGE
    if (activitySlots.none { parseSlotStart(it.label) == start }) return OUTSIDE_BOOKING_SCHEDULE_MESSAGE
    if (start.isBefore(hours.first) || !start.isBefore(hours.second)) return OUTSIDE_BOOKING_SCHEDULE_MESSAGE

    val nextStart = activitySlots.mapNotNull { parseSlotStart(it.label) }
        .filter { it.isAfter(start) && it.isBefore(hours.second) }
        .minOrNull()
    val slotEnd = nextStart?.let { minOf(it, hours.second) } ?: hours.second
    val availableMinutes = java.time.Duration.between(start, slotEnd).toMinutes()
    if (availableMinutes <= 0) return OUTSIDE_BOOKING_SCHEDULE_MESSAGE

    if (activityType == "orchard") {
        val durationMinutes = duration?.let(::parseDurationMinutes)
            ?: return "This fruit-picking experience has no valid duration and cannot be booked."
        if (durationMinutes > availableMinutes) return OUTSIDE_BOOKING_SCHEDULE_MESSAGE
    }
    return null
}

/**
 * Identifies "the same person at the same date and time". A customer may hold only one active
 * booking per slot. Registered tourists are matched by email; walk-ins by name + phone digits.
 */
fun Booking.slotKey(): String {
    val who = customerEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "tourist@agritourism.ph" } // old walk-in placeholder
        ?: "walkin:${customerName.trim().lowercase()}:${customerPhone.orEmpty().filter(Char::isDigit)}"
    return "$who|${parseDate(tourDate)?.toString() ?: tourDate.trim()}|${slotTime(tourTime)}"
}

/** [farmerId] links a farmer account to its farm in the farmers table. */
data class User(val id: Long, val fullName: String, val email: String, val phone: String, val role: Role, val farmerId: Int? = null, val address: String = "",
                /** Profile photo chosen by the user (blank = role default). */
                val avatarUrl: String = "",
                val isActive: Boolean = true,
                val createdAt: String = "")

@Serializable
enum class TourPriceStatus(val label: String) {
    VERIFIED("Published rate"),
    ESTIMATED("Estimated rate"),
    UNVERIFIED("Unverified rate"),
}

@Serializable
enum class TourPriceType(val label: String) {
    PER_PERSON("per person"),
    PER_GROUP("per group"),
    PER_PACKAGE("per package"),
}

@Serializable
data class FarmTour(
    val id: Int,
    val name: String,
    val location: String,
    val rating: Double,
    val reviewsCount: Int,
    val pricePerPerson: Int,
    val operatingHours: String,
    val amenities: List<String>,
    val activities: List<String>,
    val imageUrl: String,
    val description: String,
    val highlightTag: String,
    val farmerName: String,
    val thumbnails: List<String>,
    /** Owning farm (farmers.id); null = platform-managed. */
    val farmerId: Int? = null,
    /** Deleted by its owner: hidden from the catalog but kept so old bookings still resolve. */
    val archived: Boolean = false,
    val estimatedDuration: String = "",
    /** Retained for old databases; pricing status now describes evidence, not booking approval. */
    val priceApproved: Boolean = true,
    val suggestedPricePerPerson: Int? = null,
    val priceSuggestionBasis: String = "",
    val priceSuggestionSource: String = "",
    val priceStatus: TourPriceStatus = TourPriceStatus.UNVERIFIED,
    val priceType: TourPriceType = TourPriceType.PER_PERSON,
    val bookingEnabled: Boolean = true,
    val experienceType: String = "tour",
)

fun FarmTour.bookingTotal(participants: Int): Int =
    when (priceType) {
        TourPriceType.PER_PERSON -> pricePerPerson * participants
        TourPriceType.PER_GROUP, TourPriceType.PER_PACKAGE -> pricePerPerson
    }

@Serializable
data class FruitPickingItem(
    val id: Int,
    val title: String,
    val farmName: String,
    val location: String,
    val fruitName: String,
    val pricePerPerson: Int,
    val rating: Double,
    val reviewsCount: Int,
    val duration: String,
    val seasonalFruits: String,
    val imageUrl: String,
    val description: String,
    val seasonAvailability: String,
    val thumbnails: List<String> = emptyList(),
    val farmerId: Int? = null,
    val archived: Boolean = false,
)

@Serializable
data class Product(
    val id: Long,
    val name: String,
    val category: String,
    val price: Double,
    val unit: String,
    val discountPercent: Int,
    val stock: Int,
    val rating: Double,
    val reviewsCount: Int,
    val imageUrl: String,
    val description: String,
    val farmOrigin: String,
    val farmerId: Int? = null,
    /** Deleted by its owner: hidden from the market but kept so old orders still resolve. */
    val archived: Boolean = false,
) {
    val discountedPrice: Double get() = price * (1 - discountPercent / 100.0)
}

data class CartItem(val product: Product, val quantity: Int)

@Serializable
data class Booking(
    val id: Long,
    val bookingCode: String,
    val farmId: Int,
    val farmName: String,
    val customerName: String,
    val customerPhone: String? = null,
    val customerEmail: String? = null,
    val activity: String,
    val tourDate: String,
    val tourTime: String,
    val participants: Int,
    val totalAmount: Int,
    val paymentMethod: String? = null,
    val status: String,
    val attendanceStatus: String? = null,
    val assignedGuide: String? = null,
    val specialRequests: String? = null,
    val uploadedIdDoc: String? = null,
    val uploadedReceiptDoc: String? = null,
    val checkedInAt: String? = null,
    /** "tour" (farmId is a tours.id) or "orchard" (farmId is a fruit_picking.id). */
    val activityType: String = "tour",
)

data class BookingDateWindow(val first: LocalDate, val last: LocalDate) {
    fun contains(date: LocalDate) = !date.isBefore(first) && !date.isAfter(last)

    companion object {
        fun current(today: LocalDate = LocalDate.now()) =
            BookingDateWindow(today, LocalDate.of(today.year + 1, 12, 31))
    }
}

@Serializable
data class DeliveryCheckpoint(
    val title: String,
    val location: String,
    val timestamp: String,
    val completed: Boolean,
    val notes: String? = null,
)

@Serializable
data class OrderItem(
    val productName: String,
    val qty: Int,
    val unitPrice: Double,
    val productId: Long? = null,
    val productImageUrl: String = "",
    val sellerName: String = "",
)

@Serializable
data class FarmerOrder(
    val id: String,
    val orderNumber: String,
    val customerName: String,
    val customerPhone: String,
    val customerAddress: String,
    val items: List<OrderItem>,
    val totalAmount: Double,
    val paymentMethod: String,
    val paymentStatus: String,
    val fulfillmentStatus: String,
    val orderDate: String,
    val uploadedReceiptUrl: String,
    val specialDeliveryNotes: String? = null,
    val courierName: String? = null,
    val riderName: String? = null,
    val riderPhone: String? = null,
    val riderVehicle: String? = null,
    val currentLocation: String? = null,
    val estimatedArrival: String? = null,
    val trackingNumber: String? = null,
    val deliverySteps: List<DeliveryCheckpoint> = emptyList(),
    val isReceivedByUser: Boolean = false,
    val receivedAt: String? = null,
    val receivedByRecipient: String? = null,
    val customerEmail: String? = null,
)

@Serializable
data class CustomerReview(
    val id: Int,
    val author: String,
    val roleTag: String,
    val rating: Int,
    val date: String,
    val farmOrCrop: String,
    val comment: String,
    val userEmail: String? = null,
    val bookingId: Long? = null,
    /** What was reviewed: "tour" / "orchard" plus its id, or null for general reviews. */
    val targetType: String? = null,
    val targetId: Int? = null,
)

@Serializable
data class FarmerProfile(
    val id: Int,
    val name: String,
    val roleTitle: String,
    val farmName: String,
    val location: String,
    val province: String,
    val yearsExperience: Int,
    val photoUrl: String,
    val farmBannerUrl: String,
    val storyQuote: String,
    val fullBiography: String,
    val farmingPhilosophy: String,
    val certifications: List<String>,
    val sustainablePractices: List<String>,
    val specialtyProduce: List<String>,
    val productIds: List<Long>,
    val tourId: Int,
    val rating: Double,
    val reviewsCount: Int,
    val generation: String,
    val hectares: String,
    val gallery: List<String>,
)

@Serializable
data class AppNotification(
    val id: Long,
    val title: String,
    val desc: String,
    val fullDetail: String,
    val time: String,
    val read: Boolean,
    val type: String,
    val targetRole: String,
    val bookingId: Long? = null,
    val attendanceStatus: String? = null,
    val recipientEmail: String? = null,
    val orderId: String? = null,
    val eventKey: String? = null,
)

data class ReceiptItem(val name: String, val quantity: Int, val unitPrice: Double, val total: Double)

data class Receipt(
    val receiptNumber: String,
    val issueDate: String,
    val customerName: String,
    val customerPhone: String,
    val customerAddress: String,
    val farmName: String,
    val hostFarmerName: String,
    val items: List<ReceiptItem>,
    val subtotal: Double,
    val deliveryFee: Double,
    val totalAmount: Double,
    val paymentMethod: String,
    val paymentStatus: String,
    val authReference: String,
    val type: String,
)

@Serializable
data class AppImages(
    val globalFarmBackground: String,
    val farmLifeBannerPhoto: String,
    val mariaAvatar: String,
    val juanAvatar: String,
    val adminAvatar: String,
    val receiptSamplePhoto: String,
    val defaultProductFallbackImage: String,
    val farmerProfileBanner: String,
    val presetMangoImage: String,
    val presetTomatoImage: String,
    val presetPapayaImage: String,
    val presetStrawberryImage: String,
    val presetHoneyImage: String,
    val presetEggplantImage: String,
)

@Serializable
data class SeedData(
    val farmers: List<FarmerProfile>,
    val tours: List<FarmTour>,
    val fruitPicking: List<FruitPickingItem>,
    val products: List<Product>,
    val bookings: List<Booking>,
    val orders: List<FarmerOrder>,
    val reviews: List<CustomerReview>,
    val notifications: List<AppNotification>,
    val config: AppImages,
    val users: List<SeedUser> = emptyList(),
    val guides: List<Guide> = emptyList(),
    val couriers: List<Courier> = emptyList(),
    val timeSlots: List<TimeSlot> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val faqs: List<Faq> = emptyList(),
)

/** A help-center question shown on the opening page and the Help page. */
@Serializable
data class Faq(val question: String, val answer: String, val id: Int = 0)

@Serializable
data class SeedUser(val fullName: String, val email: String, val phone: String, val role: String, val address: String = "", val farmerId: Int? = null)

/** A tour guide working at a partner farm. */
@Serializable
data class Guide(val id: Int, val farmerId: Int?, val name: String)

/** A delivery partner that can be assigned to produce orders. */
@Serializable
data class Courier(val id: Int, val company: String, val riderName: String, val riderPhone: String, val vehicle: String, val hub: String)

/** A bookable time slot for a tour ("tour") or orchard ("orchard"). */
@Serializable
data class TimeSlot(val activityType: String, val activityId: Int, val label: String, val id: Int = 0)
