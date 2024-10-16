package com.teamapi.dto

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val comfyUrl: String,
    val isSSL: Boolean
)
