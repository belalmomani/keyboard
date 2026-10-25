package com.omsingh.beckon.core.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The quick-control setup as a JSON document, and back. Reading is forgiving in the same way as the phrase
 * library: an entry that cannot be read is left out, unknown fields are ignored, a part that is missing
 * gets its default, and text that is not JSON at all gives null so that the caller keeps the file aside.
 */
object CommandBoardJson {
    private const val VERSION = 1
    private val json = Json { prettyPrint = false }

    fun write(board: Board): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("version", VERSION)
            put(
                "custom",
                buildJsonArray {
                    for (c in board.custom) {
                        val action = c.action
                        add(
                            buildJsonObject {
                                put("id", c.id)
                                put("label", c.label ?: "")
                                when (action) {
                                    is CommandAction.Keys -> {
                                        put("type", "keys")
                                        put("keyCode", action.keyCode)
                                        put("modifiers", action.modifiers)
                                    }
                                    is CommandAction.Phrase -> {
                                        put("type", "phrase")
                                        put("text", action.text)
                                    }
                                    else -> put("type", "other")
                                }
                            },
                        )
                    }
                },
            )
            put(
                "wheel",
                buildJsonArray {
                    for (slot in board.wheel) add(
                        buildJsonObject {
                            when (slot) {
                                is WheelSlot.Item -> {
                                    put("type", "item")
                                    put("id", slot.commandId)
                                }
                                is WheelSlot.Group -> {
                                    put("type", "group")
                                    put("name", slot.name)
                                    put("ids", buildJsonArray { slot.commandIds.forEach { add(JsonPrimitive(it)) } })
                                }
                            }
                        },
                    )
                },
            )
            put(
                "leader",
                buildJsonArray {
                    for (b in board.leader) add(buildJsonObject { put("keys", b.keys); put("id", b.commandId) })
                },
            )
            put(
                "rhythm",
                buildJsonArray {
                    for (b in board.rhythm) add(buildJsonObject { put("pattern", b.pattern); put("id", b.commandId) })
                },
            )
        },
    )

    fun read(text: String): Board? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val custom = (root["custom"] as? JsonArray).orEmpty().mapNotNull { element ->
            attempt {
                val o = element.jsonObject
                val id = o.string("id")
                val label = o.string("label")
                when (o.string("type")) {
                    "keys" -> {
                        val code = o.getValue("keyCode").jsonPrimitive.int
                        require(ShortcutKeys.isAllowed(code))
                        Command(id, label, CommandAction.Keys(code, o.getValue("modifiers").jsonPrimitive.int and 0x0F))
                    }
                    "phrase" -> Command(id, label, CommandAction.Phrase(o.string("text")))
                    else -> null
                }
            }
        }
        val known = (CommandCatalog.builtIn.map { it.id } + custom.map { it.id }).toSet()

        val defaults = Board()
        val wheel = (root["wheel"] as? JsonArray)?.mapNotNull { element ->
            attempt {
                val o = element.jsonObject
                when (o.string("type")) {
                    "item" -> o.string("id").takeIf { it in known }?.let { WheelSlot.Item(it) }
                    "group" -> {
                        val ids = o.getValue("ids").jsonArray.map { it.jsonPrimitive.content }.filter { it in known }
                        if (ids.isEmpty()) null else WheelSlot.Group(o.string("name"), ids)
                    }
                    else -> null
                }
            }
        }?.take(CommandBoard.MAX_WHEEL_SLOTS) ?: defaults.wheel

        val leader = (root["leader"] as? JsonArray)?.mapNotNull { element ->
            attempt {
                val o = element.jsonObject
                LeaderBinding(o.string("keys"), o.string("id")).takeIf { it.commandId in known && it.keys.isNotEmpty() }
            }
        }?.let(::withoutConflicts) ?: defaults.leader

        val rhythm = (root["rhythm"] as? JsonArray)?.mapNotNull { element ->
            attempt {
                val o = element.jsonObject
                val pattern = o.string("pattern")
                RhythmBinding(pattern, o.string("id")).takeIf {
                    it.commandId in known && pattern.length in CommandBoard.MIN_RHYTHM..CommandBoard.MAX_RHYTHM &&
                        pattern.all { c -> c == '.' || c == '-' }
                }
            }
        }?.distinctBy { it.pattern } ?: defaults.rhythm

        return Board(custom, wheel, leader, rhythm)
    }

    /** Keeps the first of any sequences that are the same or begin one another, as the board itself would. */
    private fun withoutConflicts(all: List<LeaderBinding>): List<LeaderBinding> {
        val kept = ArrayList<LeaderBinding>()
        for (b in all) {
            if (kept.none { it.keys.startsWith(b.keys) || b.keys.startsWith(it.keys) }) kept += b
        }
        return kept
    }

    private fun JsonObject.string(key: String): String =
        (getValue(key) as JsonPrimitive).also { require(it.isString) }.content

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
