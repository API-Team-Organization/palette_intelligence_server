package com.teamapi.dto.ws

data class ImageProgressMessage(val value: Int, val max: Int) : WSBaseMessage {
    override val type: MessageType = MessageType.IMAGE_PROGRESS
}
