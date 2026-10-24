package com.omsingh.beckon.core.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The library as a JSON document, and back.
 *
 * Reading is forgiving, because a file can be cut short by a full disk or a dying battery, or come
 * from a newer version of the app: an entry that cannot be read is left out and the rest is kept,
 * unknown fields are ignored, and a file that is not JSON at all gives `null` so that the caller can
 * keep it aside instead of overwriting it.
 */
object LibraryJson {
    private const val VERSION = 1
    private val json = Json { prettyPrint = false }

    fun write(data: LibraryData): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("version", VERSION)
            put(
                "folders",
                buildJsonArray {
                    for (folder in data.folders) add(
                        buildJsonObject {
                            put("id", folder.id)
                            put("name", folder.name)
                        },
                    )
                },
            )
            put(
                "phrases",
                buildJsonArray {
                    for (p in data.phrases) add(
                        buildJsonObject {
                            put("id", p.id)
                            put("title", p.title)
                            put("text", p.text)
                            if (p.folderId != null) put("folderId", p.folderId)
                            put("favorite", p.favorite)
                            put("createdAt", p.createdAt)
                            put("updatedAt", p.updatedAt)
                            put("useCount", p.useCount)
                            put("lastUsedAt", p.lastUsedAt)
                        },
                    )
                },
            )
        },
    )

    /** The library in [text], or null if [text] is not a library document. */
    fun read(text: String): LibraryData? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val folders = (root["folders"] as? JsonArray).orEmpty().mapNotNull { element ->
            attempt {
                val o = element.jsonObject
                PhraseFolder(o.string("id"), o.string("name"))
            }
        }
        val folderIds = folders.map { it.id }.toSet()
        val phrases = (root["phrases"] as? JsonArray).orEmpty().mapNotNull { element ->
            attempt {
                val o = element.jsonObject
                val folderId = o["folderId"]?.jsonPrimitive?.contentOrNull
                Phrase(
                    id = o.string("id"),
                    title = o.string("title"),
                    text = o.string("text"),
                    // A folder that no longer exists leaves the phrase at the top level, not lost.
                    folderId = folderId?.takeIf { it in folderIds },
                    favorite = o["favorite"]?.jsonPrimitive?.boolean ?: false,
                    createdAt = o["createdAt"]?.jsonPrimitive?.long ?: 0,
                    updatedAt = o["updatedAt"]?.jsonPrimitive?.long ?: 0,
                    useCount = o["useCount"]?.jsonPrimitive?.int ?: 0,
                    lastUsedAt = o["lastUsedAt"]?.jsonPrimitive?.long ?: 0,
                )
            }
        }
        return LibraryData(folders, phrases)
    }

    private fun JsonObject.string(key: String): String =
        (getValue(key) as JsonPrimitive).also { require(it.isString) }.content

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
