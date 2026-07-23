package com.jelenxp.cryptochat.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Leží nad obsahem appky blokující překryv (zámek, Novinky, upozornění na
 * aktualizaci)?
 *
 * Překryvy se schválně kreslí PŘES živý `NavHost`, ať se po jejich zavření
 * uživatel vrátí přesně tam, kde byl (nezahodí se rozdělaná výměna klíče apod.).
 * Cena je, že obrazovka pod nimi dál běží: obnovuje náhledy, přeskládává seznam
 * a animuje přesuny položek. Na rychlém telefonu to nikdo nepozná, na pomalejším
 * to konkuruje o hlavní vlákno právě ve chvíli, kdy uživatel klepe na tlačítka
 * v překryvu - a klepnutí padají pod stůl.
 *
 * Obrazovky proto **pozastaví periodickou práci**, dokud je tohle `true`.
 * Jednorázové načtení při vstupu se nepozastavuje (data mají být hotová, jakmile
 * se překryv zavře).
 *
 * `static`, protože se mění zřídka a nechceme kvůli němu sledovat čtení v celém
 * stromu - změna prostě překomponuje podstrom, kde je poskytnutý.
 */
val LocalUiBlocked = staticCompositionLocalOf { false }
