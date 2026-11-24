package app.kataa.core.nlu

/** A real-world moment a reminder should fire at, found in an utterance. */
sealed interface MomentMatch {
    val range: IntRange

    /** Arrive at / leave a place. [ref] is resolved against saved places, place types, then chains. */
    data class Place(val ref: PlaceRef, val onExit: Boolean, override val range: IntRange) : MomentMatch

    /** "when I get in the car" (connect) / "when I get out of the car" (disconnect). */
    data class Bluetooth(val deviceAlias: String, val onDisconnect: Boolean, override val range: IntRange) : MomentMatch
}

sealed interface PlaceRef {
    /** "home", "office", or whatever name the user said; matched to saved places by label. */
    data class Named(val name: String) : PlaceRef
    /** "a pharmacy", "any supermarket": a kind of place (OSM-style tag value). */
    data class Kind(val placeType: String, val spoken: String) : PlaceRef
    /** "here", "this place": the device's current location. */
    data object Here : PlaceRef
}

/**
 * Finds "when I get home", "when I leave the office", "at Big Mart", "at any pharmacy",
 * "next time I'm here", "when I get in the car". Works on [Text.normalize]d text.
 */
object PlaceParser {
    /** Spoken kinds of places -> OSM-style type used in the store pack. */
    val PLACE_TYPES: Map<String, String> = mapOf(
        "pharmacy" to "pharmacy", "chemist" to "pharmacy", "medical" to "pharmacy", "medical shop" to "pharmacy", "drugstore" to "pharmacy",
        "supermarket" to "supermarket", "grocery" to "supermarket", "grocery store" to "supermarket", "mart" to "supermarket",
        "department store" to "department_store", "shop" to "convenience", "store" to "convenience", "convenience store" to "convenience",
        "petrol pump" to "fuel", "petrol station" to "fuel", "gas station" to "fuel", "fuel station" to "fuel",
        "bank" to "bank", "atm" to "atm", "hospital" to "hospital", "clinic" to "clinic", "gym" to "fitness_centre",
        "post office" to "post_office", "bakery" to "bakery", "hardware store" to "hardware", "bookstore" to "books",
        "book shop" to "books", "stationery" to "stationery", "stationery shop" to "stationery", "salon" to "hairdresser",
        "barber" to "hairdresser", "restaurant" to "restaurant", "cafe" to "cafe", "coffee shop" to "cafe",
        "temple" to "place_of_worship", "school" to "school", "college" to "college", "university" to "university",
        "airport" to "aerodrome", "bus park" to "bus_station", "bus station" to "bus_station", "market" to "marketplace",
        "mall" to "mall", "electronics shop" to "electronics", "mobile shop" to "mobile_phone", "butcher" to "butcher",
        "vegetable shop" to "greengrocer", "laundry" to "laundry", "library" to "library",
    )

    private val VEHICLES = mapOf("car" to "car", "bike" to "bike", "scooter" to "bike", "scooty" to "bike", "motorbike" to "bike")
    private val AUDIO = mapOf("earbuds" to "earbuds", "earphones" to "earbuds", "headphones" to "headphones", "airpods" to "earbuds", "buds" to "earbuds")

    private const val NAME = "([\\p{L}\\p{N}'&\\- ]+?)"
    private const val END = "(?=$| and | to | then | so | because | after | before | at \\d| tomorrow| today| tonight| on | in \\d| every |,| remind | please | can you | could you )"

    private val BT_IN = Regex("\\b(?:when|once|as soon as|next time) i (?:get|am|hop|step) (?:in|into|on) (?:my |the )?(car|bike|scooter|scooty|motorbike)\\b")
    private val BT_OUT = Regex("\\b(?:when|once|as soon as) i (?:get|step) (?:out of|off) (?:my |the )?(car|bike|scooter|scooty|motorbike)\\b")
    private val BT_AUDIO = Regex("\\b(?:when|once) i (?:put on|connect|plug in|wear) (?:my |the )?(earbuds|earphones|headphones|airpods|buds)\\b")
    private val LEAVE = Regex("\\b(?:when|once|as soon as|before) i (?:leave|exit|go out of|head out of|walk out of)(?: (?:the |my )?$NAME)?$END")
    private val LEAVE_BARE = Regex("\\bbefore i (?:go out|head out|leave)\\b")
    private val ARRIVE = Regex("\\b(?:when|once|as soon as|next time|whenever|the moment) i (?:get|reach|arrive|am|come|go|walk|enter)(?: (?:to|at|in|into|back to|back at|inside))? (?:the |my )?$NAME$END")
    private val HERE = Regex("\\b(?:here|this place|this spot)(?: next time)?\\b")
    private val AT = Regex("\\b(?:at|in|from) (?:(any|a|an|the) )?$NAME$END")

    /**
     * Looks for a moment. [knownPlaceNames] (saved place labels, lowercase) lets "at office" or
     * "in Big Mart" be recognized even without "when I".
     */
    fun parse(text: String, knownPlaceNames: Set<String> = emptySet()): MomentMatch? {
        BT_OUT.find(text)?.let { return MomentMatch.Bluetooth(VEHICLES.getValue(it.groupValues[1]), onDisconnect = true, it.range) }
        BT_IN.find(text)?.let { return MomentMatch.Bluetooth(VEHICLES.getValue(it.groupValues[1]), onDisconnect = false, it.range) }
        BT_AUDIO.find(text)?.let { return MomentMatch.Bluetooth(AUDIO.getValue(it.groupValues[1]), onDisconnect = false, it.range) }

        LEAVE.find(text)?.let { m ->
            val name = m.groupValues[1].trim().ifEmpty { "home" }
            return MomentMatch.Place(refFor(name, null), onExit = true, m.range)
        }
        LEAVE_BARE.find(text)?.let { return MomentMatch.Place(PlaceRef.Named("home"), onExit = true, it.range) }

        ARRIVE.find(text)?.let { m ->
            val name = m.groupValues[1].trim()
            if (HERE.matches(name) || name == "here") return MomentMatch.Place(PlaceRef.Here, onExit = false, m.range)
            if (name.isNotEmpty() && name !in NOT_PLACES && (name in knownPlaceNames || !Relations.PERSON.matches(name))) return MomentMatch.Place(refFor(name, null), onExit = false, m.range)
        }
        Regex("\\bnext time i am here\\b|\\bwhen i am here (?:next|again)\\b|\\b(?:remind me )?here next time\\b").find(text)?.let {
            return MomentMatch.Place(PlaceRef.Here, onExit = false, it.range)
        }

        // "buy milk at Big Mart", "pick up medicine at any pharmacy", "in big mart"
        AT.findAll(text).toList().asReversed().forEach { m ->
            val det = m.groupValues[1]
            val name = m.groupValues[2].trim()
            if (name.isEmpty() || name in NOT_PLACES || name.split(' ').size > 4) return@forEach
            if (name.first().isDigit()) return@forEach // "at 5", "in 2 hours"
            if (name !in knownPlaceNames && Relations.PERSON.matches(name)) return@forEach // "from (my) didi" is a person
            val kind = typeOf(name)
            val known = name in knownPlaceNames
            // Things after "the/a" are usually objects ("in the drawer") unless they are a known kind or saved place.
            val looksLikePlace = kind != null || known || det.isEmpty() || det == "any"
            if (looksLikePlace && !TIME_WORDS.any { name.startsWith(it) }) {
                return MomentMatch.Place(refFor(name, det), onExit = false, m.range)
            }
        }
        return null
    }

    private fun refFor(name: String, det: String?): PlaceRef {
        typeOf(name)?.let { return PlaceRef.Kind(it, name) }
        return PlaceRef.Named(name)
    }

    fun typeOf(name: String): String? {
        val n = Text.stripDeterminers(name)
        return PLACE_TYPES[n] ?: PLACE_TYPES[Text.singular(n)]
    }

    private val NOT_PLACES = setOf(
        "it", "this", "that", "there", "them", "him", "her", "me", "least", "once", "all", "time", "bed", "sleep",
        "the morning", "morning", "evening", "night", "afternoon", "noon", "midnight", "lunch",
    )
    private val TIME_WORDS = listOf("morning", "evening", "night", "afternoon", "noon", "midnight", "the morning", "the evening", "the night", "half an hour", "an hour", "a minute", "the afternoon")
}
