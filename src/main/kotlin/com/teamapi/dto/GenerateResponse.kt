package com.teamapi.dto

import kotlinx.serialization.Serializable

@Serializable
data class GenerateResponse(val result: Boolean, val image: String? = null, val error: String? = null)
