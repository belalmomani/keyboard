package com.omsingh.beckon.core.windows

/**
 * The program and window in front on the PC.
 *
 * [title] can name documents and web pages, so it is only used on the phone to pick a typing mode and is
 * never written to storage or logs.
 */
data class ActiveWindow(
    /** The program's file name, such as `Code.exe`. May be empty when the PC would not say. */
    val process: String,
    val title: String,
) {
    /** The program name in lower case without `.exe`, for matching: `code`. */
    val key: String get() = process.lowercase(java.util.Locale.ROOT).removeSuffix(".exe")
}
