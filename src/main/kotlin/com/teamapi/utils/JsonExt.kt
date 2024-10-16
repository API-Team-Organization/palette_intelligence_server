package com.teamapi.utils

import kotlinx.serialization.json.*

operator fun JsonElement?.get(value: String): JsonElement? = this?.let { it.jsonObject[value] }
fun JsonElement?.mut(): MutableMap<String, JsonElement>? = this?.jsonObject?.toMutableMap()
fun JsonElement?.str(): String? = this?.jsonPrimitive?.contentOrNull
fun MutableMap<String, JsonElement>.editChild(path: String, callback: MutableMap<String, JsonElement>.() -> Unit) = set(path, JsonObject(get(path).mut()!!.apply(callback)))