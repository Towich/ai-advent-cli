package org.example.data.service

import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Сервис для работы с документами: загрузка из GitHub и разбивка на чанки
 */
class DocumentService(
    private val githubToken: String? = null,
    private val chunkSize: Int = 500,
    private val chunkOverlap: Int = 50
) {
    private val logger = LoggerFactory.getLogger(DocumentService::class.java)

    /**
     * Загружает документы из локальной директории (после клонирования репозитория)
     * @param repoPath путь к локальной копии репозитория
     * @return список путей к .md файлам
     */
    fun loadMarkdownFiles(repoPath: String): List<String> {
        val directory = File(repoPath)
        if (!directory.exists() || !directory.isDirectory) {
            logger.warn("Директория не существует: $repoPath")
            return emptyList()
        }

        val markdownFiles = mutableListOf<String>()
        directory.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.lowercase() == "md") {
                markdownFiles.add(file.absolutePath)
            }
        }

        logger.info("Найдено ${markdownFiles.size} markdown файлов в $repoPath")
        return markdownFiles
    }

    /**
     * Читает содержимое файла
     */
    fun readFileContent(filePath: String): String? {
        return try {
            File(filePath).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            logger.error("Ошибка при чтении файла $filePath: ${e.message}", e)
            null
        }
    }

    /**
     * Разбивает текст на чанки
     * @param text исходный текст
     * @param filePath путь к файлу (для метаданных)
     * @return список чанков
     */
    fun splitIntoChunks(text: String, filePath: String): List<ChunkData> {
        if (text.isBlank()) {
            return emptyList()
        }

        val chunks = mutableListOf<ChunkData>()
        var startIndex = 0
        var chunkIndex = 0

        while (startIndex < text.length) {
            val endIndex = minOf(startIndex + chunkSize, text.length)
            
            // Пытаемся разбить по границам предложений или параграфов
            val actualEndIndex = if (endIndex < text.length) {
                // Ищем ближайший перенос строки или точку (ищем назад от endIndex)
                val searchStart = maxOf(startIndex, endIndex - chunkSize)
                val newlineIndex = text.lastIndexOf('\n', endIndex - 1)
                val periodIndex = text.lastIndexOf('.', endIndex - 1)
                val spaceIndex = text.lastIndexOf(' ', endIndex - 1)
                
                when {
                    newlineIndex >= startIndex -> newlineIndex + 1
                    periodIndex >= startIndex -> periodIndex + 1
                    spaceIndex >= startIndex -> spaceIndex + 1
                    else -> endIndex
                }
            } else {
                endIndex
            }

            val chunkContent = text.substring(startIndex, actualEndIndex).trim()
            if (chunkContent.isNotBlank()) {
                chunks.add(
                    ChunkData(
                        id = UUID.randomUUID().toString(),
                        content = chunkContent,
                        chunkIndex = chunkIndex,
                        filePath = filePath
                    )
                )
                chunkIndex++
            }

            // Перекрытие: начинаем следующий чанк с учетом overlap
            startIndex = if (actualEndIndex < text.length) {
                maxOf(startIndex + 1, actualEndIndex - chunkOverlap)
            } else {
                actualEndIndex
            }
        }

        logger.debug("Текст из $filePath разбит на ${chunks.size} чанков")
        return chunks
    }

    /**
     * Клонирует репозиторий из GitHub
     * @param repoUrl URL репозитория
     * @param targetPath путь для клонирования
     * @return путь к клонированному репозиторию или null при ошибке
     */
    fun cloneRepository(repoUrl: String, targetPath: String): String? {
        return try {
            val targetDir = File(targetPath)
            if (targetDir.exists()) {
                logger.info("Директория уже существует: $targetPath. Используем существующую.")
                return targetPath
            }

            targetDir.parentFile?.mkdirs()

            val processBuilder = ProcessBuilder(
                "git", "clone", repoUrl, targetPath
            ).apply {
                if (githubToken != null) {
                    // Если есть токен, добавляем его в URL
                    val authenticatedUrl = repoUrl.replace(
                        "https://github.com/",
                        "https://$githubToken@github.com/"
                    )
                    command(listOf("git", "clone", authenticatedUrl, targetPath))
                }
                redirectErrorStream(true)
            }

            logger.info("Клонирование репозитория $repoUrl в $targetPath...")
            val process = processBuilder.start()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                logger.info("Репозиторий успешно клонирован")
                targetPath
            } else {
                val errorOutput = process.inputStream.bufferedReader().readText()
                logger.error("Ошибка при клонировании репозитория (код $exitCode): $errorOutput")
                null
            }
        } catch (e: Exception) {
            logger.error("Исключение при клонировании репозитория: ${e.message}", e)
            null
        }
    }

    /**
     * Данные чанка до генерации эмбеддинга
     */
    data class ChunkData(
        val id: String,
        val content: String,
        val chunkIndex: Int,
        val filePath: String
    )
}

