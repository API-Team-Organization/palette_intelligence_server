package com.teamapi.dto.ws

import com.teamapi.utils.get
import com.teamapi.utils.str
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement

@Serializable(with = WSMessageSerializer::class)
sealed interface WSBaseMessage {
    val type: MessageType
}

object WSMessageSerializer : JsonContentPolymorphicSerializer<WSBaseMessage>(WSBaseMessage::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<WSBaseMessage> {
        val mt = try {
            val type = element["type"].str()!!
            MessageType.valueOf(type)
        } catch (e: Exception) {
            throw SerializationException(e)
        }

        return when (mt) {
            MessageType.QUEUE_STATUS -> QueueInfoMessage.serializer()
            MessageType.GENERATE_FINISH -> GenerateMessage.serializer()
        }
    }
}
