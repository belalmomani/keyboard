package com.omsingh.beckon.core.clipboard

import com.omsingh.beckon.core.storage.AtomicTextFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * The clipboard history on disk, in the app's private storage. It is only written while the person
 * has the history switched on, and [delete] removes it for good when they switch it off.
 */
class ClipboardHistoryStore(private val file: File) {
    private val storage = AtomicTextFile(file)
    private val json = Json

    fun load(): List<ClipEntry> {
        val text = storage.read() ?: return emptyList()
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
        if (root == null) {
            storage.keepAside()
            return emptyList()
        }
        return (root["entries"] as? JsonArray).orEmpty().mapNotNull { element ->
            try {
                val o = element.jsonObject
                ClipEntry(
                    id = (o.getValue("id") as JsonPrimitive).content,
                    text = (o.getValue("text") as JsonPrimitive).content,
                    pinned = o["pinned"]?.jsonPrimitive?.boolean ?: false,
                    copiedAt = o["copiedAt"]?.jsonPrimitive?.long ?: 0,
                    source = if (o["source"]?.jsonPrimitive?.content == "PC") ClipSource.PC else ClipSource.PHONE,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun save(entries: List<ClipEntry>): Boolean = storage.write(
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("version", 1)
                put(
                    "entries",
                    buildJsonArray {
                        for (e in entries) add(
                            buildJsonObject {
                                put("id", e.id)
                                put("text", e.text)
                                put("pinned", e.pinned)
                                put("copiedAt", e.copiedAt)
                                put("source", e.source.name)
                            },
                        )
                    },
                )
            },
        ),
    )

    /** Removes the saved history, and any copy kept aside. */
    fun delete() {
        file.delete()
        File(file.parentFile, file.name + ".bad").delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
