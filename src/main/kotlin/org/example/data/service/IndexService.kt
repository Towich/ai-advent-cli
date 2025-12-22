package org.example.data.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.domain.model.DocumentChunk
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Сервис для сохранения и загрузки индекса документов
 */
class IndexService(val indexPath: String) {
    private val logger = LoggerFactory.getLogger(IndexService::class.java)
    private val jsonSerializer = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class IndexData(
        val chunks: List<ChunkData>,
        val createdAt: Long,
        val model: String,
        val totalDocuments: Int,
        val totalChunks: Int
    )

    @Serializable
    private data class ChunkData(
        val id: String,
        val filePath: String,
        val content: String,
        val chunkIndex: Int,
        val embedding: List<Float>,
        val metadata: Map<String, String> = emptyMap()
    )

    /**
     * Сохраняет индекс в JSON файл
     */
    fun saveIndex(chunks: List<DocumentChunk>, model: String): Result<Unit> {
        return try {
            val indexFile = File(indexPath)
            indexFile.parentFile?.mkdirs()

            val uniqueFiles = chunks.map { it.filePath }.distinct()
            val indexData = IndexData(
                chunks = chunks.map { chunk ->
                    ChunkData(
                        id = chunk.id,
                        filePath = chunk.filePath,
                        content = chunk.content,
                        chunkIndex = chunk.chunkIndex,
                        embedding = chunk.embedding,
                        metadata = chunk.metadata
                    )
                },
                createdAt = System.currentTimeMillis(),
                model = model,
                totalDocuments = uniqueFiles.size,
                totalChunks = chunks.size
            )

            val json = jsonSerializer.encodeToString(indexData)
            indexFile.writeText(json, Charsets.UTF_8)

            logger.info("Индекс сохранен: ${chunks.size} чанков из ${uniqueFiles.size} документов, модель: $model")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Ошибка при сохранении индекса: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Загружает индекс из JSON файла
     */
    fun loadIndex(): Result<List<DocumentChunk>> {
        return try {
            val indexFile = File(indexPath)
            if (!indexFile.exists()) {
                logger.warn("Файл индекса не существует: $indexPath")
                return Result.success(emptyList())
            }

            val json = indexFile.readText(Charsets.UTF_8)
            val indexData = jsonSerializer.decodeFromString<IndexData>(json)

            val chunks = indexData.chunks.map { chunk ->
                DocumentChunk(
                    id = chunk.id,
                    filePath = chunk.filePath,
                    content = chunk.content,
                    chunkIndex = chunk.chunkIndex,
                    embedding = chunk.embedding,
                    metadata = chunk.metadata
                )
            }

            logger.info("Индекс загружен: ${chunks.size} чанков из ${indexData.totalDocuments} документов, модель: ${indexData.model}")
            Result.success(chunks)
        } catch (e: Exception) {
            logger.error("Ошибка при загрузке индекса: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Проверяет, существует ли индекс
     */
    fun indexExists(): Boolean {
        return File(indexPath).exists()
    }

    /**
     * Получает информацию об индексе
     */
    fun getIndexInfo(): IndexInfo? {
        return try {
            val indexFile = File(indexPath)
            if (!indexFile.exists()) {
                return null
            }

            val json = indexFile.readText(Charsets.UTF_8)
            val indexData = jsonSerializer.decodeFromString<IndexData>(json)

            IndexInfo(
                totalChunks = indexData.totalChunks,
                totalDocuments = indexData.totalDocuments,
                model = indexData.model,
                createdAt = indexData.createdAt
            )
        } catch (e: Exception) {
            logger.error("Ошибка при получении информации об индексе: ${e.message}", e)
            null
        }
    }

    data class IndexInfo(
        val totalChunks: Int,
        val totalDocuments: Int,
        val model: String,
        val createdAt: Long
    )
}

