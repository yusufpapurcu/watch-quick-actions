package com.watchassistantbridge

object TriggerNumber {
    // Triggers whose national part is shorter than this stay exact-match only, so a dummy
    // trigger like "0000" can never be matched by an unrelated "+90 000"-style number.
    private const val MIN_NATIONAL_DIGITS = 6
    private const val MAX_COUNTRY_CODE_DIGITS = 3

    /** Keeps ASCII digits and a leading '+'; everything else (spaces, dashes, brackets) is dropped. */
    fun normalize(raw: String): String {
        val digits = raw.filter { it in '0'..'9' }
        return if (raw.trimStart().startsWith('+')) "+$digits" else digits
    }

    fun matches(dialed: String, trigger: String): Boolean {
        val t = normalize(trigger)
        val d = normalize(dialed)
        if (t.none { it in '0'..'9' }) return false
        if (d == t) return true

        val dialedIntl = internationalDigits(d) ?: return false
        internationalDigits(t)?.let { return dialedIntl == it }

        val national = t.removePrefix("0")
        if (national.length < MIN_NATIONAL_DIGITS) return false
        return (1..MAX_COUNTRY_CODE_DIGITS).any { cc ->
            dialedIntl.length > cc && dialedIntl.substring(cc) == national
        }
    }

    /**
     * The digits after a '+' or "00" prefix, or null if the number has no international prefix.
     * A country code never starts with 0, so "0000" and "00000" are not international.
     */
    private fun internationalDigits(normalized: String): String? {
        val rest = when {
            normalized.startsWith("+") -> normalized.drop(1)
            normalized.startsWith("00") -> normalized.drop(2)
            else -> return null
        }
        return rest.takeIf { it.isNotEmpty() && it[0] != '0' }
    }
}
