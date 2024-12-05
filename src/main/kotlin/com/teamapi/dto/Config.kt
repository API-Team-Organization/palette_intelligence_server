package com.teamapi.dto

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val comfyUrl: String,
    val port: Int,
    val isSSL: Boolean,
    
    val credential: String,
    val password: String,
)
