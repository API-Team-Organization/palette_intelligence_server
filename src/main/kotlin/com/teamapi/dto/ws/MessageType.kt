package com.teamapi.dto.ws

import kotlinx.serialization.KSerializer

enum class MessageType(val delegatingSerializer: KSerializer<out WSBaseMessage>) {
    QUEUE_STATUS(QueueInfoMessage.serializer()),
    GENERATE_FINISH(GenerateMessage.serializer())
}
