package app.kataa.core.nlu

/** Family and close people, as said in English and Nepali ("didi", "dai", "aama"). Rules only. */
object Relations {
    const val WORDS = "(?:mom|mum|mother|mommy|mummy|dad|father|daddy|papa|mamu|mama|aama|ama|baba|buwa|didi|dai|daju|bahini|bhai|sister|brother|wife|husband|son|daughter|uncle|aunt|aunty|auntie|grandma|grandpa|grandmother|grandfather|hajurba|hajuraama|boss|girlfriend|boyfriend|partner|cousin|nephew|niece|best friend|friend|fiance|fiancee)"

    /** "didi birthday", "dad phone number": something personal of someone close, said without the "'s". */
    val PERSONAL_FACT = Regex("^$WORDS(?:'s)? (?:birthday|bday|anniversary|wedding anniversary|phone|phone number|number|mobile|mobile number|address|home address|email|email address|name|full name|age|blood group|blood type|shoe size|size|passport number|account number|bank account|citizenship number|office|school)$")

    /** Just a person: "didi", "my dad". */
    val PERSON = Regex("^(?:my |our )?$WORDS$")
}
