package com.omsingh.beckon.core.tvremote

import android.content.Context

/** The TVs this phone has been paired with (the TV remembers the phone; this remembers the TV's address and name). */
class TvKnown(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tv_remote", Context.MODE_PRIVATE)

    fun list(): List<FoundTv> =
        (prefs.getStringSet(KEY, emptySet()) ?: emptySet())
            .mapNotNull { entry -> entry.split('\t', limit = 2).takeIf { it.size == 2 }?.let { FoundTv(it[1], it[0]) } }
            .sortedBy { it.name }

    fun isPaired(host: String): Boolean = list().any { it.host == host }

    fun remember(tv: FoundTv) {
        val others = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).filterNot { it.startsWith(tv.host + "\t") }
        prefs.edit().putStringSet(KEY, (others + "${tv.host}\t${tv.name}").toSet()).apply()
    }

    fun forget(host: String) {
        val others = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).filterNot { it.startsWith("$host\t") }
        prefs.edit().putStringSet(KEY, others.toSet()).apply()
    }

    private companion object {
        const val KEY = "paired"
    }
}
