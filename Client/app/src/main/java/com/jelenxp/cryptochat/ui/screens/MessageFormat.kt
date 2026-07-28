package com.jelenxp.cryptochat.ui.screens

/**
 * Lehké formátování textu zprávy (markdown-lite): `*tučně*`, `_kurzíva_`,
 * `~škrtnuté~`, `` `mono` ``. Značky se z výsledku ODSTRANÍ a obsah mezi nimi
 * dostane styl - jako v jiných messengerech.
 *
 * **Proč čistá funkce (bez Compose):** rozhodování „kde začíná/končí styl" je
 * netriviální a podle projektové politiky nesmí být schované v `@Composable`.
 * [parse] vrací holý text ([Parsed.plain]) + rozsahy stylů ([Parsed.spans])
 * s indexy DO TOHO HOLÉHO textu; převod na `AnnotatedString` (Compose) dělá až
 * obrazovka. Díky tomu jde parser otestovat na čistém JVM.
 *
 * **Nic se neposílá jinak:** formátuje se jen ZOBRAZENÍ. Po síti i do historie
 * jde pořád syrový text se značkami, takže starší verze appky ukáže hvězdičky -
 * čistá degradace, žádná změna protokolu ani schopnost.
 *
 * **Hranice slova (proti falešným trefám):** značka otevírá jen na začátku slova
 * (před ní není písmeno/číslice) a zavírá jen na konci slova - takže `snake_case`
 * ani `2*2` se omylem nezformátují. Obsah nesmí začínat/končit mezerou.
 * Vnořování se neřeší (obsah mezi značkami se bere doslova).
 */
object MessageFormat {

    /** Styl jednoho úseku textu. */
    enum class Style { BOLD, ITALIC, STRIKE, CODE }

    /** Rozsah `[start, end)` v [Parsed.plain] se stylem [style]. */
    data class Span(val start: Int, val end: Int, val style: Style)

    /** Výsledek: holý text bez značek a rozsahy stylů nad ním. */
    data class Parsed(val plain: String, val spans: List<Span>)

    private fun styleFor(c: Char): Style? = when (c) {
        '*' -> Style.BOLD
        '_' -> Style.ITALIC
        '~' -> Style.STRIKE
        '`' -> Style.CODE
        else -> null
    }

    private fun isBoundary(c: Char): Boolean = !c.isLetterOrDigit()

    /**
     * Rozparsuje [text] na holý text + rozsahy stylů. Když text žádné použitelné
     * značky nemá, [Parsed.plain] == [text] a [Parsed.spans] je prázdný.
     */
    fun parse(text: String): Parsed {
        val sb = StringBuilder(text.length)
        val spans = ArrayList<Span>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val style = styleFor(c)
            // Otevírá jen na hranici slova (před značkou není písmeno/číslice).
            val opens = style != null && (i == 0 || isBoundary(text[i - 1]))
            if (opens) {
                val close = findClose(text, c, i + 1)
                if (close != -1) {
                    val contentStart = sb.length
                    sb.append(text, i + 1, close)   // obsah bez značek
                    spans.add(Span(contentStart, sb.length, style!!))
                    i = close + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return Parsed(sb.toString(), spans)
    }

    /**
     * Najde uzavírací značku [c] od indexu [from]. Podmínky: neprázdný obsah,
     * obsah nezačíná ani nekončí mezerou a za zavírací značkou je hranice slova
     * (konec textu nebo ne-alfanumerický znak). `-1` = žádné platné zavření.
     */
    private fun findClose(text: String, c: Char, from: Int): Int {
        if (from >= text.length || text[from].isWhitespace()) return -1
        var j = from
        while (j < text.length) {
            if (text[j] == c &&
                j > from &&
                !text[j - 1].isWhitespace() &&
                (j + 1 >= text.length || isBoundary(text[j + 1]))
            ) {
                return j
            }
            j++
        }
        return -1
    }
}
