package app.kataa.ui

import app.kataa.R

/**
 * Picks the 3D object shown for a memory: first from the words in it ("keys" -> key), then from its
 * category, then a notepad. Objects are Microsoft Fluent Emoji 3D (MIT license).
 */
object ObjectIcons {
    private val byWord: List<Pair<Set<String>, Int>> = listOf(
        setOf("key", "keys", "keychain") to R.drawable.o3d_key,
        setOf("car", "parked", "park", "parking", "vehicle") to R.drawable.o3d_automobile,
        setOf("bike", "bicycle", "scooter", "scooty", "motorbike", "cycle") to R.drawable.o3d_bicycle,
        setOf("glasses", "specs", "spectacles", "sunglasses") to R.drawable.o3d_glasses,
        setOf("wallet", "purse", "bag") to R.drawable.o3d_purse,
        setOf("phone", "mobile") to R.drawable.o3d_mobile_phone,
        setOf("charger", "cable", "plug", "adapter") to R.drawable.o3d_electric_plug,
        setOf("laptop", "computer", "macbook") to R.drawable.o3d_laptop,
        setOf("headphones", "headphone", "earphones", "earbuds", "airpods") to R.drawable.o3d_headphone,
        setOf("passport", "licence", "license", "citizenship", "id", "document", "documents", "certificate") to R.drawable.o3d_identification_card,
        setOf("atm", "debit", "credit", "card") to R.drawable.o3d_credit_card,
        setOf("medicine", "medicines", "pill", "pills", "tablet", "tablets", "dose", "vitamin", "vitamins") to R.drawable.o3d_pill,
        setOf("milk") to R.drawable.o3d_glass_of_milk,
        setOf("umbrella") to R.drawable.o3d_umbrella,
        setOf("remote", "tv", "television") to R.drawable.o3d_television,
        setOf("gift", "present", "birthday") to R.drawable.o3d_wrapped_gift,
        setOf("book", "books", "notebook", "exam", "assignment", "homework") to R.drawable.o3d_books,
        setOf("money", "cash", "rupees", "rs", "salary", "loan") to R.drawable.o3d_money_bag,
        setOf("buy", "groceries", "grocery", "shopping") to R.drawable.o3d_shopping_cart,
        setOf("password", "pin", "wifi", "passcode", "otp") to R.drawable.o3d_locked,
        setOf("idea", "ideas") to R.drawable.o3d_light_bulb,
        setOf("flight", "ticket", "trip", "visa", "airport") to R.drawable.o3d_airplane,
    )

    private val byCategory = mapOf(
        "items" to R.drawable.o3d_round_pushpin,
        "education" to R.drawable.o3d_books,
        "work" to R.drawable.o3d_briefcase,
        "health" to R.drawable.o3d_pill,
        "finance" to R.drawable.o3d_money_bag,
        "shopping" to R.drawable.o3d_shopping_cart,
        "people" to R.drawable.o3d_busts_in_silhouette,
        "ideas" to R.drawable.o3d_light_bulb,
        "travel" to R.drawable.o3d_airplane,
        "home" to R.drawable.o3d_house,
        "private" to R.drawable.o3d_locked,
    )

    val reminder = R.drawable.o3d_alarm_clock
    val notFound = R.drawable.o3d_magnifying_glass
    val note = R.drawable.o3d_spiral_notepad

    fun forCategory(slug: String): Int = byCategory[slug] ?: note

    fun forText(text: String, categorySlugs: List<String> = emptyList()): Int {
        val words = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        for ((keys, res) in byWord) if (words.any { it in keys }) return res
        for (slug in categorySlugs) byCategory[slug]?.let { return it }
        return note
    }
}
