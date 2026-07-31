package com.jelenxp.cryptochat.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Vstupní textové pole s vypnutým „učením" klávesnice (incognito).
 *
 * **Proč platformní [EditText] a ne Compose `OutlinedTextField`:** běžné
 * klávesnice (Gboard, Samsung) vypínají personalizované učení a synchronizaci
 * napsaného textu do cloudu podle flagu
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] v `imeOptions`. Compose
 * (BOM 2024.06 / ui-text 1.6.8) tenhle flag přes `KeyboardOptions` /
 * `PlatformImeOptions` nastavit **neumí**. Proto se pole hostuje jako [EditText],
 * kde flag nastavit jde. U šifrovaného messengeru je to podstatné: bez něj by se
 * plaintext napsaných zpráv učil a odcházel mimo E2E systém.
 *
 * **Výška se počítá SYNCHRONNĚ z textu** (přes [StaticLayout] rovnou v kompozici),
 * NE přes WRAP měření `AndroidView`. WRAP se totiž při přidání řádku na 1-3 snímky
 * přeměřil špatně (pole zkolabovalo na jeden řádek a hned se roztáhlo) - viditelné
 * bliknutí, při kterém chat „poskočil". Protože tady je výška hotová ve STEJNÉM
 * snímku, kdy se změní text, k žádnému přeměřování se zpožděním nedojde. Nad
 * [maxLines] se výška zastaví a obsah se scrolluje za kurzorem ([scrollCaretIntoView]).
 *
 * **Obousměrná vazba:** zápis zvenčí ([value] se změní - odeslání pole vyprázdní,
 * úprava ho předvyplní) se promítne přes `update`; psaní uživatele hlásí
 * [onValueChange]. Guard `text != value` v `update` brání smyčce i skoku kurzoru.
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
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val borderColor = MaterialTheme.colorScheme.outline
    val currentOnValueChange = rememberUpdatedState(onValueChange)

    val density = LocalDensity.current
    val textSizeSp = 16f
    val textSizePx = with(density) { textSizeSp.sp.toPx() }
    // Odhad jednoho řádku, dokud neznáme šířku (první frame): ať pole neblikne.
    val oneLineFallback: Dp = with(density) { (textSizePx * 1.4f).toDp() }

    BoxWithConstraints(
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            // Svislé odsazení dělá z jednořádkového pole pohodlnou „pilulku" a drží
            // text vycentrovaný; roste jen textová oblast uvnitř, odsazení je stálé.
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        // Šířka textové oblasti = vnitřek Boxu (po odečtení paddingu); stejnou dostane
        // i AndroidView (fillMaxWidth), takže lomení řádků sedí na náš výpočet.
        val widthPx = constraints.maxWidth
        // Přesná výška pro aktuální text a šířku, spočítaná HNED (StaticLayout), takže
        // AndroidView nikdy nemá špatnou (kolabovanou) výšku ani na jeden snímek.
        val contentHeight: Dp = remember(value, widthPx, textSizePx, maxLines) {
            if (widthPx <= 0) oneLineFallback
            else with(density) { measureTextHeightPx(value, widthPx, textSizePx, maxLines).toDp() }
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(contentHeight),
            factory = { ctx ->
                EditText(ctx).apply {
                    background = null
                    setPadding(0, 0, 0, 0)
                    // TOP: víc řádků roste odshora. U ≤ maxLines je výška == obsah,
                    // takže na gravitaci nezáleží; nad maxLines se scrolluje za kurzorem.
                    gravity = Gravity.TOP
                    textSize = textSizeSp
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
                if (et.text.toString() != value) {
                    et.setText(value)
                    et.setSelection(value.length)
                }
                if (et.isEnabled != enabled) et.isEnabled = enabled
                et.hint = hint
                et.setTextColor(textColor)
                et.setHintTextColor(hintColor)
                // Žádné ruční scrollování obsahu: do maxLines se pole vejde celé
                // (výška je spočítaná přesně na počet řádků), nad maxLines si kurzor
                // ohlídá sám EditText. Dřívější ruční scroll v post{} způsoboval při
                // psaní bliknutí (obsah na 1-3 snímky poskočil a srovnal se).
            }
        )
    }
}

/**
 * Spočítá výšku textové oblasti (px) pro daný [text] a šířku [widthPx] pomocí
 * [StaticLayout] - stejný layout engine jako v [EditText], takže lomení i výška
 * řádků sedí. Omezeno na [maxLines] (nad to se scrolluje). Prázdný text = jeden řádek.
 */
private fun measureTextHeightPx(text: String, widthPx: Int, textSizePx: Float, maxLines: Int): Int {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        typeface = Typeface.DEFAULT
    }
    val src = if (text.isEmpty()) " " else text
    val layout = StaticLayout.Builder.obtain(src, 0, src.length, paint, widthPx).build()
    val lines = layout.lineCount.coerceIn(1, maxLines)
    return layout.getLineBottom(lines - 1)
}
