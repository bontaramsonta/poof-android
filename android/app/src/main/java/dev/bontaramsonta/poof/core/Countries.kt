package dev.bontaramsonta.poof.core

/** Display names for poof's lowercase Country names. */
object Countries {
    private val special = mapOf("usa" to "USA")

    fun displayName(country: String): String =
        special[country] ?: country.replaceFirstChar { it.uppercase() }
}
