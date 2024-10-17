package com.teamapi.dto.actor

import com.teamapi.dto.ws.GenerateMessage
import com.teamapi.dto.ws.QueueInfoMessage
import com.teamapi.dto.ws.WSBaseMessage

sealed interface ActorMessage {
    val data: WSBaseMessage

    data class QueueUpdate(val position: Int) : ActorMessage {
        override val data: WSBaseMessage
            get() = QueueInfoMessage(position)

    }
    data class GenerateResult(
        val result: Boolean,
        val image: String? = null,
        val error: String? = null
    ) : ActorMessage {
        override val data: WSBaseMessage
            get() = GenerateMessage(result, image, error)
    }
}
