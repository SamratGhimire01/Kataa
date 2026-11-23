package app.kataa.core.embed

/**
 * Search quality eval: realistic memories and the ways people ask for them later, mostly
 * paraphrased so keywords alone fail. Used to measure recall and to tune the "I don't know"
 * threshold for the real model. Extend this when a real user query fails.
 */
object SearchEval {
    val memories = listOf(
        /* 0 */ "My keys are in the blue drawer in the kitchen",
        /* 1 */ "Passport is in the brown suitcase on top of the wardrobe",
        /* 2 */ "Parked at level 3, pillar B12",
        /* 3 */ "Ramesh's wifi password is Sunflower99",
        /* 4 */ "Dentist appointment on the 20th at 4 pm with Dr. Shrestha",
        /* 5 */ "Lent 5000 rupees to Bikash, he will return it next month",
        /* 6 */ "Mom's blood pressure tablets: Amlodipine 5 mg, one every morning",
        /* 7 */ "The plumber's number is 98410 12345, his name is Hari",
        /* 8 */ "Sita recommended the book Atomic Habits",
        /* 9 */ "Physics exam is on Monday in room 204",
        /* 10 */ "Idea: an app that reminds you where you parked using the car's Bluetooth",
        /* 11 */ "The warranty card for the fridge is inside the manual folder",
        /* 12 */ "Spare house key is with the neighbour aunty upstairs",
        /* 13 */ "Gym locker combination is 3-17-29",
        /* 14 */ "Netflix subscription renews on the 5th of every month, 1499 rupees",
        /* 15 */ "Prakash's birthday is on March 14, he likes chocolate cake",
        /* 16 */ "Tailor said the suit will be ready on Friday after 3 pm",
        /* 17 */ "My blood group is O positive",
        /* 18 */ "Car insurance expires on 12 August, policy with Nepal Life",
        /* 19 */ "The good momo place is behind the Durbar Marg petrol pump",
        /* 20 */ "Laptop charger is in the black backpack front pocket",
        /* 21 */ "Bank locker number 214 at the New Road branch",
        /* 22 */ "Doctor said to avoid cold drinks for two weeks because of my throat",
        /* 23 */ "Electricity bill paid for October, 2340 rupees",
        /* 24 */ "Landlord wants the rent by the 7th, account number ends 4412",
        /* 25 */ "Professor said the assignment deadline moved to next Thursday",
        /* 26 */ "Bought a new mattress, receipt is in the email from Hukut",
        /* 27 */ "Grandma's medicine is kept in the top shelf of the steel cupboard",
        /* 28 */ "Movie recommendation from Anish: Interstellar",
        /* 29 */ "Daughter's school uniform size is 28",
        /* 30 */ "The scooter needs servicing after 3000 km, last done at 12400",
        /* 31 */ "Office wifi network is Kataa-5G and the password is on the whiteboard",
        /* 32 */ "Recipe tip from mom: add a pinch of sugar to the dal",
        /* 33 */ "Umbrella is in the car boot",
        /* 34 */ "My shoe size is 9 UK",
        /* 35 */ "The visa interview is on 3 February at 9 am at the US embassy",
        /* 36 */ "Gave my headphones to Rohan to try, he has to return them",
        /* 37 */ "Water purifier filter changed on 10 November",
        /* 38 */ "Locker key for the cycle is hanging behind the main door",
        /* 39 */ "Kiran owes me lunch because I won the bet about the football match",
    )

    /** (query, index of the memory that answers it). */
    val queries = listOf(
        "where are my keys" to 0, "where did I keep the house keys" to 0,
        "where is my passport" to 1, "where did I put my travel documents" to 1,
        "where did I park" to 2, "where is the car" to 2,
        "what is ramesh's wifi password" to 3, "internet password at Ramesh's house" to 3,
        "when is my dentist" to 4, "when do I see the doctor for my teeth" to 4,
        "who owes me money" to 5, "how much did I lend Bikash" to 5,
        "what medicine does mom take for blood pressure" to 6, "mom's bp pills name" to 6,
        "plumber phone number" to 7, "who can fix the leaking tap" to 7,
        "what book did Sita suggest" to 8, "book recommendation" to 8,
        "when is my physics test" to 9, "which room is the exam in" to 9,
        "that parking app idea" to 10, "business idea I had about cars" to 10,
        "where is the fridge warranty" to 11, "refrigerator guarantee papers" to 11,
        "who has the extra key to the house" to 12, "duplicate home key" to 12,
        "gym locker code" to 13, "what's the combination for my locker at the gym" to 13,
        "when does netflix renew" to 14, "how much do I pay for streaming" to 14,
        "when is Prakash's birthday" to 15, "what cake does Prakash like" to 15,
        "when will my suit be ready" to 16, "pick up from the tailor" to 16,
        "what is my blood type" to 17,
        "when does my car insurance expire" to 18, "vehicle policy renewal date" to 18,
        "where was that good momo restaurant" to 19, "dumplings place near the fuel station" to 19,
        "where is my laptop charger" to 20, "computer power adapter" to 20,
        "bank locker number" to 21, "safe deposit box at the bank" to 21,
        "what did the doctor say about my throat" to 22, "can I drink cold drinks" to 22,
        "did I pay the electricity bill" to 23, "how much was the power bill" to 23,
        "when is rent due" to 24, "landlord's account number" to 24,
        "when is the assignment due" to 25, "homework deadline" to 25,
        "mattress receipt" to 26, "where is the bill for the bed" to 26,
        "where is grandma's medicine" to 27,
        "what movie did Anish recommend" to 28, "film suggestion" to 28,
        "school uniform size" to 29, "what size clothes for my daughter's school" to 29,
        "when is the scooter service due" to 30, "bike maintenance kilometers" to 30,
        "office wifi" to 31,
        "mom's dal tip" to 32, "cooking advice from my mother" to 32,
        "where is the umbrella" to 33, "where's my umbrella" to 33,
        "what's my shoe size" to 34, "footwear size" to 34,
        "when is the visa interview" to 35, "embassy appointment" to 35,
        "who has my headphones" to 36, "where are my earphones" to 36,
        "when did I change the water filter" to 37,
        "where is the cycle lock key" to 38, "bicycle key" to 38,
        "who owes me lunch" to 39, "what did I win the bet for" to 39,
    )

    /**
     * Acceptable categories per memory (first is the best fit). Several memories honestly fit two
     * ("plumber's number": home or people), so any listed one counts.
     */
    val categories = listOf(
        "items,private", "items,private,travel", "items", "private", "health", "finance,people", "health,people", "home,people", "people,ideas,education",
        "education", "ideas", "items,home", "items,home", "private", "finance,shopping", "people", "shopping,people", "health,private", "finance,items,travel",
        "ideas,shopping,travel,people", "items", "finance,private", "health", "finance,home", "finance,home", "education", "shopping,finance,home", "health,items",
        "ideas,people", "shopping,education,people", "items,home,travel", "private,work", "home,people", "items", "shopping,health,people", "travel",
        "people,items", "home", "items,home", "people,finance",
    ).map { it.split(',') }

    /** Questions nothing above answers: Kataa should say "I don't know". */
    val unanswerable = listOf(
        "what is my aadhar number", "when is my sister's wedding", "where is my watch",
        "what's the gas cylinder dealer's number", "when does the library close", "what is my car's number plate",
        "who is my bank manager", "where did I keep my sunglasses", "what is the password for my laptop",
        "when is my next haircut", "what is the name of the new neighbour", "how much did the tv cost",
        "where is the remote", "when is the parent teacher meeting", "what is my pan number",
    )
}
