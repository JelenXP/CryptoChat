package com.jelenxp.cryptochat.ui.components

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Vstupní textové pole s vypnutým „učením" klávesnice (incognito).
 *
 * **Proč platformní [EditText] a ne Compose `OutlinedTextField`:** běžné
 * klávesnice (Gboard, Samsung) vypínají personalizované učení a synchronizaci
 * napsaného textu do cloudu podle flagu
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] v `imeOptions`. Compose
 * (BOM 2024.06 / ui-text 1.6.8) tenhle flag přes `KeyboardOptions` /
 * `PlatformImeOptions` nastavit **neumí** - `PlatformImeOptions` nese jen
 * `privateImeOptions`, což klávesnice pro incognito nečtou. Proto se pole hostuje
 * jako [EditText], kde flag nastavit jde. U šifrovaného messengeru je to
 * podstatné: bez něj by se plaintext napsaných zpráv učil a odcházel mimo E2E
 * systém.
 *
 * **Obousměrná vazba:** zápis zvenčí ([value] se změní - odeslání pole vyprázdní,
 * úprava ho předvyplní) se promítne přes `update`; psaní uživatele hlásí
 * [onValueChange]. Guard `text != value` v `update` brání smyčce i skoku kurzoru
 * při psaní: během psaní se `value` už rovná obsahu pole, takže `setText` se
 * nevolá a kurzor (ani IME kompozice) se nesahá.
 */
@Composable
fun IncognitoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = 5
) {
    // Barvy z motivu - čtou se při každé rekompozici (i po přepnutí světlý/tmavý).
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val borderColor = MaterialTheme.colorScheme.outline
    // `factory` běží jen jednou - aktuální lambdu drž přes rememberUpdatedState,
    // ať TextWatcher volá vždy tu poslední, ne tu z prvního složení.
    val currentOnValueChange = rememberUpdatedState(onValueChange)

    Box(
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            factory = { ctx ->
                // Svislé odsazení v PIXELECH (proto převod z dp): jeden řádek + odsazení
                // ≈ min. výška 40 dp. Bez něj 40 dp pojme DVA řádky, takže první přírůstek
                // se „spolkne" a pole naroste (odtlačí chat) až u třetího řádku - skokově.
                // S odsazením roste plynule po jednom řádku. CENTER_VERTICAL drží jeden
                // řádek svisle vycentrovaný, takže vzhled prázdného pole zůstává.
                val vpad = (10 * ctx.resources.displayMetrics.density).toInt()
                EditText(ctx).apply {
                    background = null
                    setPadding(0, vpad, 0, vpad)
                    gravity = Gravity.CENTER_VERTICAL
                    textSize = 16f
                    this.maxLines = maxLines
                    // Víceřádkový text + velká písmena na začátku vět (jako dřív
                    // OutlinedTextField s KeyboardCapitalization.Sentences).
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    // JÁDRO FUNKCE: řekni klávesnici, ať si napsané neukládá do
                    // slovníku ani nesynchronizuje do cloudu (incognito).
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(e: Editable?) {
                            currentOnValueChange.value(e?.toString() ?: "")
                        }
                    })
                }
            },
            update = { et ->
                // Zápis zvenčí (vyprázdnění po odeslání, předvyplnění při úpravě).
                // Guard brání smyčce s TextWatcherem i skoku kurzoru při běžném psaní.
                if (et.text.toString() != value) {
                    et.setText(value)
                    et.setSelection(value.length)
                }
                if (et.isEnabled != enabled) et.isEnabled = enabled
                et.hint = hint
                et.setTextColor(textColor)
                et.setHintTextColor(hintColor)
                // Spolehlivě posuň pohled tak, aby zůstal vidět kurzor (aktuální řádek).
                // Vlastní auto-scroll EditTextu je uvnitř AndroidView nespolehlivý
                // (~50/50) kvůli časování měření - tady ho po dokončení layoutu
                // dopočítáme sami. Mění jen scrollY, ne výšku, takže layout ani
                // zalamování textu se nedotýká. Přesně to, co dřív dělal Compose
                // OutlinedTextField sám (viz docstring - proč EditText).
                et.post {
                    val layout = et.layout ?: return@post
                    val len = et.text?.length ?: 0
                    val line = layout.getLineForOffset(et.selectionEnd.coerceIn(0, len))
                    val innerH = et.height - et.compoundPaddingTop - et.compoundPaddingBottom
                    if (innerH <= 0) return@post
                    val top = layout.getLineTop(line)
                    val bottom = layout.getLineBottom(line)
                    val cur = et.scrollY
                    val next = when {
                        top < cur -> top                       // řádek nad výřezem → nahoru
                        bottom > cur + innerH -> bottom - innerH // řádek pod výřezem → dolů
                        else -> cur
                    }
                    if (next != cur) et.scrollTo(0, next.coerceAtLeast(0))
                }
            }
        )
    }
}
