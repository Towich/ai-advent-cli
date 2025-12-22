package org.example.domain.model

/**
 * Чанк документа с эмбеддингом
 */
data class DocumentChunk(
    val id: String,
    val filePath: String,
    val content: String,
    val chunkIndex: Int,
    val embedding: List<Float>,
    val metadata: Map<String, String> = emptyMap()
)

