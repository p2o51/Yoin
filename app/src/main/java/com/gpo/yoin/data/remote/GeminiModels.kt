package com.gpo.yoin.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val tools: List<GeminiTool>,
    val generationConfig: GeminiGenerationConfig = GeminiGenerationConfig(),
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>,
)

@Serializable
data class GeminiPart(
    val text: String,
)

@Serializable
data class GeminiTool(
    val googleSearch: GoogleSearch = GoogleSearch(),
)

@Serializable
class GoogleSearch

@Serializable
data class GeminiGenerationConfig(
    val temperature: Float = 0.8f,
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
)

@Serializable
data class GeminiCandidate(
    val content: GeminiCandidateContent? = null,
)

@Serializable
data class GeminiCandidateContent(
    val parts: List<GeminiPart>? = null,
)
