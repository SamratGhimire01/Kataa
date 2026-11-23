package app.kataa.core.categories

import app.kataa.core.db.SqlDriver

/** Starter categories. Users can rename, recolor, archive them, or add their own. */
object DefaultCategories {
    data class Seed(
        val slug: String,
        val name: String,
        val description: String,
        val icon: String,
        val color: String,
        val isPrivate: Boolean = false,
        /** Words that strongly suggest this category; matched against stemmed tokens. */
        val keywords: Set<String> = emptySet(),
    )

    val all: List<Seed> = listOf(
        Seed("items", "Where things are",
            "where I put or left a physical object: keys, wallet, passport, charger, glasses, remote, where the car is parked",
            "pin", "#FF8A3D",
            keywords = setOf("keys", "key", "wallet", "passport", "charger", "glasses", "remote", "parked", "drawer", "shelf", "cupboard")),
        Seed("education", "Education",
            "school, college, university, classes, courses, exams, assignments, homework, studying, lectures, books, teachers",
            "book", "#5AA9FF",
            keywords = setOf("exam", "assignment", "homework", "class", "lecture", "course", "semester", "teacher", "study", "college", "school", "quiz", "syllabus")),
        Seed("work", "Work",
            "job, office, meetings, clients, projects, deadlines, colleagues, manager, salary, presentations",
            "briefcase", "#9B8CFF",
            keywords = setOf("meeting", "client", "office", "deadline", "manager", "colleague", "project", "presentation", "standup", "salary")),
        Seed("health", "Health",
            "doctor, hospital, medicine, pills, symptoms, gym, workouts, diet, sleep, appointments",
            "heart", "#FF5C7A",
            keywords = setOf("doctor", "medicine", "pill", "tablet", "dose", "hospital", "gym", "workout", "symptom", "dentist", "clinic")),
        Seed("finance", "Money",
            "bank, payments, bills, rent, loans, expenses, budget, account numbers, prices, insurance, tax",
            "wallet", "#3DDC97",
            keywords = setOf("bank", "bill", "rent", "loan", "pay", "paid", "payment", "insurance", "tax", "invoice", "esewa", "khalti", "rupees", "rs")),
        Seed("shopping", "Shopping",
            "things to buy, groceries, orders, products, gifts, shopping list, supermarket",
            "cart", "#FFD24A",
            keywords = setOf("buy", "groceries", "grocery", "order", "milk", "eggs", "bread", "supermarket", "mart")),
        Seed("people", "People",
            "friends, family, birthdays, contacts, what someone said, what someone likes, gifts for someone",
            "person", "#FF9ECF",
            keywords = setOf("birthday", "anniversary", "friend", "mom", "dad", "brother", "sister", "wife", "husband")),
        Seed("ideas", "Ideas",
            "ideas, plans, thoughts, inspiration, things to try, goals, side projects",
            "bulb", "#B8F35A",
            keywords = setOf("idea", "maybe", "someday", "plan", "goal", "startup")),
        Seed("travel", "Travel",
            "trips, tickets, flights, buses, hotels, visas, places to visit, directions",
            "plane", "#4FD1E8",
            keywords = setOf("flight", "ticket", "hotel", "trip", "visa", "bus", "airport", "booking")),
        Seed("home", "Home",
            "house, rent, repairs, appliances, chores, plumber, electrician, family at home",
            "home", "#C9A27E",
            keywords = setOf("plumber", "electrician", "repair", "chores", "fridge", "washing", "landlord")),
        Seed("private", "Private",
            "passwords, PINs, codes, wifi passwords, lock combinations, secrets, ID numbers, personal documents",
            "lock", "#8E8E93", isPrivate = true,
            keywords = setOf("password", "pin", "passcode", "wifi", "otp", "combination", "secret")),
    )

    val keywordsBySlug: Map<String, Set<String>> = all.associate { it.slug to it.keywords }

    fun seed(db: SqlDriver, nowMs: Long) {
        all.forEachIndexed { i, s ->
            db.execute(
                """
                INSERT OR IGNORE INTO categories(slug, name, description, icon, color, is_system, is_private, sort_order, created_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
                """.trimIndent(),
                s.slug, s.name, s.description, s.icon, s.color, s.isPrivate, (i + 1) * 10, nowMs,
            )
        }
    }
}
