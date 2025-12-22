package org.example.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.example.data.service.DocumentService
import org.example.data.service.IndexService
import org.example.domain.model.DocumentChunk
import org.example.domain.repository.OllamaRepository
import org.slf4j.LoggerFactory
import java.io.File

/**
 * UseCase для индексации документов: загрузка, разбивка на чанки, генерация эмбеддингов, сохранение индекса
 */
class IndexDocumentsUseCase(
    private val ollamaRepository: OllamaRepository,
    private val documentService: DocumentService,
    private val indexService: IndexService,
    private val embeddingModel: String = "nomic-embed-text",
    private val batchSize: Int = 10 // Размер батча для генерации эмбеддингов
) {
    private val logger = LoggerFactory.getLogger(IndexDocumentsUseCase::class.java)

    /**
     * Выполняет полный цикл индексации документов
     * @param repoUrl URL репозитория GitHub
     * @param repoPath путь для клонирования репозитория
     * @return результат индексации
     */
    suspend fun execute(repoUrl: String, repoPath: String): Result<IndexResult> {
        return try {
            logger.info("Начало индексации документов из $repoUrl")

            // Шаг 1: Клонирование репозитория
            logger.info("Шаг 1: Клонирование репозитория...")
            val clonedPath = documentService.cloneRepository(repoUrl, repoPath)
                ?: return Result.failure(Exception("Не удалось клонировать репозиторий"))

            // Шаг 2: Загрузка markdown файлов
            logger.info("Шаг 2: Загрузка markdown файлов...")
            val markdownFiles = documentService.loadMarkdownFiles(clonedPath)
            if (markdownFiles.isEmpty()) {
                return Result.failure(Exception("Не найдено markdown файлов в репозитории"))
            }

            // Шаг 3: Чтение и разбивка на чанки
            logger.info("Шаг 3: Чтение файлов и разбивка на чанки...")
            val allChunks = mutableListOf<DocumentService.ChunkData>()
            markdownFiles.forEach { filePath ->
                val content = documentService.readFileContent(filePath)
                if (content != null) {
                    val chunks = documentService.splitIntoChunks(content, filePath)
                    allChunks.addAll(chunks)
                } else {
                    logger.warn("Не удалось прочитать файл: $filePath")
                }
            }

            if (allChunks.isEmpty()) {
                return Result.failure(Exception("Не удалось создать чанки из документов"))
            }

            logger.info("Создано ${allChunks.size} чанков из ${markdownFiles.size} файлов")

            // Шаг 4: Генерация эмбеддингов
            logger.info("Шаг 4: Генерация эмбеддингов для ${allChunks.size} чанков...")
            val documentChunks = generateEmbeddingsForChunks(allChunks)

            // Шаг 5: Сохранение индекса
            logger.info("Шаг 5: Сохранение индекса...")
            val saveResult = indexService.saveIndex(documentChunks, embeddingModel)
            if (saveResult.isFailure) {
                return Result.failure(saveResult.exceptionOrNull() ?: Exception("Ошибка сохранения индекса"))
            }

            logger.info("Индексация завершена успешно")
            Result.success(
                IndexResult(
                    totalDocuments = markdownFiles.size,
                    totalChunks = documentChunks.size,
                    model = embeddingModel,
                    indexPath = indexService.indexPath
                )
            )
        } catch (e: Exception) {
            logger.error("Ошибка при индексации документов: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Генерирует эмбеддинги для чанков батчами
     */
    private suspend fun generateEmbeddingsForChunks(
        chunks: List<DocumentService.ChunkData>
    ): List<DocumentChunk> {
        val documentChunks = mutableListOf<DocumentChunk>()

        // Обрабатываем батчами
        chunks.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            logger.info("Обработка батча ${batchIndex + 1}/${(chunks.size + batchSize - 1) / batchSize} (${batch.size} чанков)")

            val texts = batch.map { it.content }
            val embeddingsResult = ollamaRepository.generateEmbeddings(texts, embeddingModel)

            when {
                embeddingsResult.isSuccess -> {
                    val embeddings = embeddingsResult.getOrNull() ?: emptyList()
                    if (embeddings.size == batch.size) {
                        batch.forEachIndexed { index, chunk ->
                            documentChunks.add(
                                DocumentChunk(
                                    id = chunk.id,
                                    filePath = chunk.filePath,
                                    content = chunk.content,
                                    chunkIndex = chunk.chunkIndex,
                                    embedding = embeddings[index],
                                    metadata = mapOf(
                                        "file" to File(chunk.filePath).name,
                                        "chunkIndex" to chunk.chunkIndex.toString()
                                    )
                                )
                            )
                        }
                    } else {
                        logger.error("Размер эмбеддингов (${embeddings.size}) не совпадает с размером батча (${batch.size})")
                        // Пытаемся обработать по одному
                        batch.forEach { chunk ->
                            val singleResult = ollamaRepository.generateEmbedding(chunk.content, embeddingModel)
                            singleResult.fold(
                                onSuccess = { embedding ->
                                    documentChunks.add(
                                        DocumentChunk(
                                            id = chunk.id,
                                            filePath = chunk.filePath,
                                            content = chunk.content,
                                            chunkIndex = chunk.chunkIndex,
                                            embedding = embedding,
                                            metadata = mapOf(
                                                "file" to File(chunk.filePath).name,
                                                "chunkIndex" to chunk.chunkIndex.toString()
                                            )
                                        )
                                    )
                                },
                                onFailure = { error ->
                                    logger.error("Ошибка при генерации эмбеддинга для чанка ${chunk.id}: ${error.message}")
                                }
                            )
                        }
                    }
                }
                else -> {
                    logger.error("Ошибка при генерации эмбеддингов для батча: ${embeddingsResult.exceptionOrNull()?.message}")
                    // Пытаемся обработать по одному
                    batch.forEach { chunk ->
                        val singleResult = ollamaRepository.generateEmbedding(chunk.content, embeddingModel)
                        singleResult.fold(
                            onSuccess = { embedding ->
                                documentChunks.add(
                                    DocumentChunk(
                                        id = chunk.id,
                                        filePath = chunk.filePath,
                                        content = chunk.content,
                                        chunkIndex = chunk.chunkIndex,
                                        embedding = embedding,
                                        metadata = mapOf(
                                            "file" to File(chunk.filePath).name,
                                            "chunkIndex" to chunk.chunkIndex.toString()
                                        )
                                    )
                                )
                            },
                            onFailure = { error ->
                                logger.error("Ошибка при генерации эмбеддинга для чанка ${chunk.id}: ${error.message}")
                            }
                        )
                    }
                }
            }
        }

        return documentChunks
    }

    data class IndexResult(
        val totalDocuments: Int,
        val totalChunks: Int,
        val model: String,
        val indexPath: String
    )
}

