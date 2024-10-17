package com.teamapi.dto.ws

import kotlinx.serialization.Serializable

@Serializable
data class GenerateMessage(
    val result: Boolean,
    val image: String? = null,
    val error: String? = null,
    override val type: MessageType = MessageType.GENERATE_FINISH
) : WSBaseMessage
