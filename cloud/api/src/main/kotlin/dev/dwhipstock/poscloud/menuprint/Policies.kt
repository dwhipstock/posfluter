package dev.dwhipstock.poscloud.menuprint

/**
 * Rules, conditions and policies in the AI's copy ("Specials are available
 * for dine-in customers only", "while supplies last", "one per person"): the
 * venue never said them, so they are not printed. Sentence by sentence, in
 * the portal's five languages; what is left of a line is kept.
 */
object Policies {
    private val PATTERNS = listOf(
        // en
        """\bonly\b|\bavailable (for|to|on|at|until|from|with)\b|\bvalid\b|\bwhile (supplies|stocks?) last|\blimit(ed|s)?\b|\bmust\b|""" +
            """\brequired?\b|\brequires\b|\bexclud(es|ing)\b|\bdine[- ]?in\b|\btake[- ]?out\b|\btakeaway\b|\bto go\b|""" +
            """\bper (person|table|guest|customer|order)\b|\breservations?\b|\bbooking\b|\bphoto id\b|\bid required\b|\bwith id\b|""" +
            """\b(18|19|21)\s?\+|\bof age\b|\blegal (drinking )?age\b|\bcannot be combined\b|\bno substitutions\b|\bsubject to\b|""" +
            """\bterms\b|\bconditions apply\b|\bopening hours\b|\buntil close\b|\bdiscounts?\b|\bgratuity\b|\bservice charge\b|\bcash only\b""",
        // fr
        """\bseulement\b|\buniquement\b|\bvalables?\b|\bdisponibles? (pour|sur|jusqu)|\bjusqu'?à épuisement|\blimit(e|é|ée)s?\b|""" +
            """\bobligatoire\b|\bdoi(t|vent)\b|\bexclu|\bsur place\b|\bà emporter\b|\bpar (personne|table|client)\b|\bréservations?\b|""" +
            """\bpièce d'identité\b|\bnon cumulable\b|\bconditions\b|\bheures d'ouverture\b|\brabais\b|\bréductions?\b|\bpourboire\b""",
        // es
        """\bsolo\b|\bsólo\b|\búnicamente\b|\bválid[oa]s?\b|\bdisponibles? (para|en|hasta|solo)\b|\bhasta agotar|\blímite\b|""" +
            """\bobligatori[oa]\b|\bdebe(n)?\b|\bexclu(ye|ido)|\bpara llevar\b|\ben el local\b|\bpor (persona|mesa|cliente)\b|""" +
            """\breservas?\b|\bidentificación\b|\bno acumulable\b|\bcondiciones\b|\bhorario\b|\bdescuentos?\b|\bpropina\b""",
        // de
        """\bnur\b|\bgültig\b|\bverfügbar (für|bis|ab|nur)\b|\bsolange (der )?vorrat|\blimitiert\b|\bbegrenzt\b|\bmuss\b|\bmüssen\b|""" +
            """\berforderlich\b|\bausgenommen\b|\bausschließlich\b|\bvor ort\b|\bzum mitnehmen\b|\baußer haus\b|""" +
            """\bpro (person|tisch|gast)\b|\breservierung(en)?\b|\bausweis\b|\bnicht kombinierbar\b|\bbedingungen\b|""" +
            """\böffnungszeiten\b|\brabatt\b|\btrinkgeld\b|\bab (16|18|21)\b""",
        // af
        """\bslegs\b|\bnet vir\b|\bgeldig\b|\bbeskikbaar (vir|tot|net)\b|\bsolank (die )?voorraad|\bbeperk\b|\bmoet\b|\bvereis\b|""" +
            """\buitgesluit\b|\beet hier\b|\bwegneem\b|\bper (persoon|tafel|gas)\b|\bbesprekings?\b|\bidentiteit\b|\bvoorwaardes\b|""" +
            """\bopeningstye\b|\bafslag\b|\bfooitjie\b""",
    ).map { Regex(it, setOf(RegexOption.IGNORE_CASE)) }

    /** The text states a rule, a condition or a policy. */
    fun statesPolicy(text: String): Boolean = PATTERNS.any { it.containsMatchIn(text) }

    private val SENTENCE = Regex("""(?<=[.!?¡¿…])\s+""")

    /** [text] without its policy sentences; null when nothing is left. */
    fun withoutPolicies(text: String): String? =
        text.split(SENTENCE).filter { it.isNotBlank() && !statesPolicy(it) }.joinToString(" ").trim().ifEmpty { null }
}
