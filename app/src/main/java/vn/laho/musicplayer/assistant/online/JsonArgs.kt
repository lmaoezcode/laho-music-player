// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.online

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import vn.laho.musicplayer.assistant.ToolResult

/** Converts LLM function-call JSON arguments into plain Kotlin values for the tools. */
internal object JsonArgs {

    fun toKotlin(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull ?: element.content
        }
        is JsonArray -> element.map { toKotlin(it) }
        is JsonObject -> element.mapValues { toKotlin(it.value) }
    }

    fun toKotlin(args: Map<String, JsonElement>): Map<String, Any?> = args.mapValues { toKotlin(it.value) }

    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is Iterable<*> -> JsonArray(value.map { toJson(it) })
        is Array<*> -> JsonArray(value.map { toJson(it) })
        else -> JsonPrimitive(value.toString())
    }

    /** The payload returned to the model after a tool runs. */
    fun resultToJson(result: ToolResult): JsonObject = JsonObject(
        buildMap {
            put("success", JsonPrimitive(result.success))
            put("message", JsonPrimitive(result.message))
            result.data.forEach { (k, v) -> put(k, toJson(v)) }
        },
    )
}
