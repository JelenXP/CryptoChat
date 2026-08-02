package com.jelenxp.cryptochat.chat

/**
 * Který chat je právě otevřený (na popředí). Background sync podle toho
 * NEposílá notifikaci pro konverzaci, kterou uživatel zrovna čte.
 */
object ActiveChat {
    @Volatile
    var currentId: String? = null

    /** Který SKUPINOVÝ chat je otevřený (namespace zvlášť — groupId a contactId se nesmí splést). */
    @Volatile
    var currentGroupId: String? = null
}
