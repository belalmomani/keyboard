package com.omsingh.beckon.core.input

import com.omsingh.beckon.core.windows.ActiveWindow

/** What the Enter key of the phone's keyboard does while typing. */
enum class EnterAction {
    /** A new line in the text, as in a document. */
    NEWLINE,

    /** The Enter key is pressed on the PC and the line is done: a message is sent, a search is run. */
    PRESS_ENTER,
}

/**
 * How the typing field behaves in a [TypingMode]. Every field here changes something the person can
 * feel; a mode is not a label.
 */
data class TypingProfile(
    /** Ask the phone's keyboard for suggestions and autocorrect. */
    val autoCorrect: Boolean,
    /** Capitalise the first letter of a sentence. */
    val sentenceCapitals: Boolean,
    val enter: EnterAction,
)

/** What the person is typing, which decides how the phone's keyboard is set up for it. */
enum class TypingMode(val profile: TypingProfile) {
    /** As before modes existed: no capitals, suggestions on, new lines as text. */
    GENERAL(TypingProfile(autoCorrect = true, sentenceCapitals = false, enter = EnterAction.NEWLINE)),

    /** Code, a terminal, commands: nothing may be "corrected" or capitalised. */
    CODE(TypingProfile(autoCorrect = false, sentenceCapitals = false, enter = EnterAction.NEWLINE)),

    /** Messaging: capitals, and Enter sends. */
    CHAT(TypingProfile(autoCorrect = true, sentenceCapitals = true, enter = EnterAction.PRESS_ENTER)),

    /** Writing: capitals, suggestions, new lines. */
    DOCUMENT(TypingProfile(autoCorrect = true, sentenceCapitals = true, enter = EnterAction.NEWLINE)),

    /** A search box or launcher: exact words, Enter runs it. */
    SEARCH(TypingProfile(autoCorrect = false, sentenceCapitals = false, enter = EnterAction.PRESS_ENTER)),
}

/** How the mode is chosen. */
sealed interface ModeChoice {
    /** Follow the program in front on the PC, where the PC can say. */
    data object Auto : ModeChoice
    data class Manual(val mode: TypingMode) : ModeChoice

    companion object {
        /** For a value saved in settings. Anything unknown is [Auto]. */
        fun fromSaved(saved: String?): ModeChoice = when {
            saved == null || saved == "AUTO" -> Auto
            else -> TypingMode.entries.firstOrNull { it.name == saved }?.let(::Manual) ?: Auto
        }

        fun toSaved(choice: ModeChoice): String = when (choice) {
            Auto -> "AUTO"
            is Manual -> choice.mode.name
        }
    }
}

/**
 * Which typing mode suits the program in front on the PC. Only programs whose use is clear are listed:
 * a web browser is used for search boxes, forms and documents alike, so it stays [TypingMode.GENERAL]
 * and the person can pick a mode themselves.
 */
object AppModes {

    private val code = setOf(
        "code", "code - insiders", "cursor", "devenv", "idea64", "pycharm64", "webstorm64", "clion64", "rider64",
        "goland64", "phpstorm64", "studio64", "sublime_text", "notepad++", "eclipse", "windowsterminal", "wt",
        "cmd", "powershell", "pwsh", "conhost", "mintty", "putty", "alacritty", "wezterm-gui",
    )
    private val chat = setOf(
        "whatsapp", "whatsapp.root", "telegram", "discord", "slack", "teams", "ms-teams", "signal", "viber",
        "skype", "element", "wechat", "line", "messenger",
    )
    private val document = setOf(
        "winword", "onenote", "wordpad", "notion", "obsidian", "soffice", "soffice.bin", "evernote", "typora",
        "wps", "joplin",
    )
    private val search = setOf(
        "searchhost", "searchapp", "searchui", "startmenuexperiencehost", "everything", "flow.launcher",
        "powertoys.powerlauncher", "keypirinha", "wox",
    )

    /** The mode for [window]'s program, or null when it is not one of the known ones. */
    fun modeFor(window: ActiveWindow?): TypingMode? {
        val key = window?.key?.takeIf { it.isNotEmpty() } ?: return null
        return when (key) {
            in code -> TypingMode.CODE
            in chat -> TypingMode.CHAT
            in document -> TypingMode.DOCUMENT
            in search -> TypingMode.SEARCH
            else -> null
        }
    }

    /** The mode in force: the one chosen, or in [ModeChoice.Auto] the one that suits [window], else [TypingMode.GENERAL]. */
    fun effective(choice: ModeChoice, window: ActiveWindow?): TypingMode = when (choice) {
        is ModeChoice.Manual -> choice.mode
        ModeChoice.Auto -> modeFor(window) ?: TypingMode.GENERAL
    }
}
