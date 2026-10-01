package dev.dwhipstock.pos.aimenu

/**
 * The floor-plan AI's "skipped" lines (what the server dropped or fixed), in
 * the manager's language. Every line is the server's own fixed text — never
 * the model's words: an unknown id or shape is "unknown table", not the id the
 * model made up. The English line is the source; a line with no translation
 * stays English.
 */
internal object AiText {
    private class Rule(val en: Regex, val t: Map<String, String>)

    private fun rule(en: String, fr: String, es: String, de: String, af: String) =
        Rule(Regex("^$en$"), mapOf("fr" to fr, "es" to es, "de" to de, "af" to af))

    /** "table L-5: …", "new table 2: …", "object 3: …", "change 4: …" — the subject, then the reason. */
    private val SUBJECTS = listOf(
        rule("new table (.+)", "nouvelle table $1", "mesa nueva $1", "neuer Tisch $1", "nuwe tafel $1"),
        rule("new object (.+)", "nouvel élément $1", "elemento nuevo $1", "neues Element $1", "nuwe voorwerp $1"),
        rule("table (.+)", "table $1", "mesa $1", "Tisch $1", "tafel $1"),
        rule("object (.+)", "élément $1", "elemento $1", "Element $1", "voorwerp $1"),
        rule("change (.+)", "changement $1", "cambio $1", "Änderung $1", "verandering $1"),
    )

    private val REASONS = listOf(
        rule("unknown shape", "forme inconnue", "forma desconocida", "unbekannte Form", "onbekende vorm"),
        rule("unknown type", "type inconnu", "tipo desconocido", "unbekannter Typ", "onbekende soort"),
        rule("a custom object needs a plain name", "un élément personnalisé doit avoir un nom simple",
            "un elemento personalizado necesita un nombre sencillo", "ein eigenes Element braucht einen einfachen Namen",
            "’n pasgemaakte voorwerp het ’n gewone naam nodig"),
        rule("no free spot, it overlapped others", "aucune place libre, elle chevauchait les autres",
            "no había sitio libre, se superponía a otras", "kein freier Platz, überlappte andere",
            "geen vrye plek nie, dit het ander oorvleuel"),
        rule("not a floor-plan change", "pas un changement du plan de salle", "no es un cambio del plano",
            "keine Änderung am Raumplan", "nie ’n vloerplanverandering nie"),
    )

    private val LINES = listOf(
        rule("unknown table", "table inconnue", "mesa desconocida", "unbekannter Tisch", "onbekende tafel"),
        rule("unknown object", "élément inconnu", "elemento desconocido", "unbekanntes Element", "onbekende voorwerp"),
        rule("table (.+) has an open bill: not removed", "la table $1 a une addition ouverte : pas retirée",
            "la mesa $1 tiene una cuenta abierta: no se quitó", "Tisch $1 hat eine offene Rechnung: nicht entfernt",
            "tafel $1 het ’n oop rekening: nie verwyder nie"),
        rule("table (.+) has sub-tables: not removed", "la table $1 a des sous-tables : pas retirée",
            "la mesa $1 tiene submesas: no se quitó", "Tisch $1 hat Untertische: nicht entfernt",
            "tafel $1 het subtafels: nie verwyder nie"),
        rule("table (.+) has an open bill: not moved, reshaped or renumbered",
            "la table $1 a une addition ouverte : ni déplacée, ni modifiée, ni renumérotée",
            "la mesa $1 tiene una cuenta abierta: no se movió, cambió ni renumeró",
            "Tisch $1 hat eine offene Rechnung: nicht verschoben, umgeformt oder umnummeriert",
            "tafel $1 het ’n oop rekening: nie geskuif, verander of hernommer nie"),
        rule("renumbering skipped: two tables would share a number",
            "renumérotation ignorée : deux tables auraient le même numéro",
            "renumeración omitida: dos mesas tendrían el mismo número",
            "Umnummerierung übersprungen: zwei Tische hätten dieselbe Nummer",
            "hernommering oorgeslaan: twee tafels sou dieselfde nommer hê"),
        rule("(\\d+) table\\(s\\) over the (\\d+) limit dropped", "$1 table(s) au-delà de la limite de $2 ignorée(s)",
            "$1 mesa(s) por encima del límite de $2 omitida(s)", "$1 Tisch(e) über dem Limit von $2 weggelassen",
            "$1 tafel(s) oor die limiet van $2 weggelaat"),
        rule("(\\d+) object\\(s\\) over the (\\d+) limit dropped", "$1 élément(s) au-delà de la limite de $2 ignoré(s)",
            "$1 elemento(s) por encima del límite de $2 omitido(s)", "$1 Element(e) über dem Limit von $2 weggelassen",
            "$1 voorwerp(e) oor die limiet van $2 weggelaat"),
        rule("too many removals at once; at most (\\d+) per request", "trop de retraits à la fois ; au plus $1 par demande",
            "demasiadas eliminaciones a la vez; como máximo $1 por solicitud",
            "zu viele Entfernungen auf einmal; höchstens $1 pro Anfrage",
            "te veel verwyderings op een slag; hoogstens $1 per versoek"),
    )

    fun skips(lines: List<String>, lang: String?): List<String> = lines.map { skip(it, lang) }

    fun skip(line: String, lang: String?): String {
        val l = lang?.take(2)?.lowercase() ?: return line
        if (l == "en") return line
        translate(LINES, line, l)?.let { return it }
        val subject = line.substringBefore(": ", "")
        val reason = line.substringAfter(": ", "")
        if (subject.isEmpty() || reason.isEmpty()) return line
        val s = translate(SUBJECTS, subject, l) ?: return line
        val r = translate(REASONS, reason, l) ?: return line
        return "$s : $r".let { if (l == "fr") it else it.replace(" : ", ": ") }
    }

    private fun translate(rules: List<Rule>, text: String, lang: String): String? {
        val rule = rules.firstOrNull { it.en.matches(text) } ?: return null
        val template = rule.t[lang] ?: return null
        return rule.en.replace(text, template.replace("\\", "\\\\"))
    }
}
