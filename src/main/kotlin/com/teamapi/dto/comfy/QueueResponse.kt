package com.teamapi.dto.comfy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class QueueResponse(
    @SerialName("prompt_id") val promptId: String,
    val number: Int,
    @SerialName("node_errors") val nodeErrors: JsonElement
)
