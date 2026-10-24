package com.omsingh.beckon.core.clipboard

/**
 * Recognises text that should not be kept in a clipboard history: card numbers, one-time codes,
 * passwords and keys. It cannot be perfect, so it errs towards not keeping: a history that forgets
 * a harmless number costs nothing, and one that keeps a password is a leak.
 */
object SensitiveText {

    enum class Reason { MARKED_BY_APP, CARD_NUMBER, ONE_TIME_CODE, PRIVATE_KEY, LABELLED_SECRET, TOKEN }

    private val labelledSecret = Regex(
        """(?i)(password|passwd|pwd|passcode|pin\s*code|secret|api[\s_-]?key|access[\s_-]?token|auth[\s_-]?token|""" +
            """كلمة\s*(ال)?(مرور|سر)|رمز\s*(ال)?(دخول|سري|تحقق)|الرقم\s*السري)\s*[:=：]\s*\S+""",
    )
    private val jwt = Regex("""^eyJ[\w-]+\.[\w-]+\.[\w-]+$""")
    private val hex = Regex("""^[A-Fa-f0-9]{32,}$""")
    private val opaque = Regex("""^[A-Za-z0-9+/_=-]{32,}$""")
    private val oneTimeCode = Regex("""^\d{4,8}$""")

    /**
     * Why [text] must not be kept, or null if it may be.
     * [markedByApp] is whether the app that copied it said it was sensitive (Android 13 lets a password
     * manager do that).
     */
    fun reasonToSkip(text: String, markedByApp: Boolean = false): Reason? {
        if (markedByApp) return Reason.MARKED_BY_APP
        val trimmed = text.trim()
        return when {
            trimmed.contains("PRIVATE KEY-----") -> Reason.PRIVATE_KEY
            isCardNumber(trimmed) -> Reason.CARD_NUMBER
            oneTimeCode.matches(toAsciiDigits(trimmed)) -> Reason.ONE_TIME_CODE
            labelledSecret.containsMatchIn(trimmed) -> Reason.LABELLED_SECRET
            jwt.matches(trimmed) || hex.matches(trimmed) || looksLikeOpaqueToken(trimmed) -> Reason.TOKEN
            else -> null
        }
    }

    private fun toAsciiDigits(text: String): String = buildString {
        for (c in text) append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                else -> c
            },
        )
    }

    private fun isCardNumber(text: String): Boolean {
        val digits = toAsciiDigits(text).filter { it != ' ' && it != '-' }
        if (digits.length !in 13..19 || !digits.all { it in '0'..'9' }) return false
        // The Luhn check that every card number passes: it keeps phone numbers and ids out of this.
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    private fun looksLikeOpaqueToken(text: String): Boolean =
        opaque.matches(text) && text.any { it.isUpperCase() } && text.any { it.isLowerCase() } && text.any { it.isDigit() }
}
