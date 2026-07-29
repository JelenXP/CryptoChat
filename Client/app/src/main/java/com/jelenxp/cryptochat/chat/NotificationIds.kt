package com.jelenxp.cryptochat.chat

/**
 * Čistá pásma ID notifikací (bez android závislostí → jednotkově testovatelné).
 *
 * ID = báze + (hash kontaktu & [MASK]). Báze jsou od sebe (i od pevných
 * [SERVICE]/[UPDATE]) DÁL než rozsah masky, takže se pásma **nepřekrývají**.
 *
 * Historie: dřív byly báze 2000/4000 jen ~2000 od sebe, ale offset `hash&0xFFFF`
 * má rozsah 0..65535, takže pásma [2000,67535] a [4000,69535] se prolínala
 * navzájem i s UPDATE(3001) → notifikace různých typů/kontaktů dostaly stejné ID
 * a přepsaly se (tiché zmizení upozornění na nepřečtenou zprávu). Invariant
 * disjunktnosti hlídá `NotificationIdTest`.
 */
object NotificationIds {
    const val SERVICE = 1001
    const val UPDATE = 3001
    const val MASK = 0xFFFF          // per-kontakt offset 0..65535
    const val MESSAGE_BASE = 1_000_000
    const val REACTION_BASE = 2_000_000

    private fun bandId(base: Int, contactId: String): Int =
        base + (contactId.hashCode() and MASK)

    fun message(contactId: String): Int = bandId(MESSAGE_BASE, contactId)
    fun reaction(contactId: String): Int = bandId(REACTION_BASE, contactId)
}
