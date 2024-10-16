package com.teamapi.dto.comfy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class QueueRequest(val prompt: JsonObject, val client_id: String)
