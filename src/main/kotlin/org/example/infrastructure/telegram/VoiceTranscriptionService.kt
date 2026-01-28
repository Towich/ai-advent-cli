package org.example.infrastructure.telegram

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Сервис для транскрипции голосовых сообщений в текст с использованием Whisper
 */
class VoiceTranscriptionService(
    /**
     * Команда запуска whisper.
     * Рекомендуемое значение на macOS при `pip install --user`: "python3 -m whisper"
     */
    private val whisperCommand: String = "python3 -m whisper",
    private val whisperModel: String = "base",
    private val whisperLanguage: String? = null, // null означает автоопределение языка
    private val tempDir: String = System.getProperty("java.io.tmpdir")
) {
    private val logger = LoggerFactory.getLogger(VoiceTranscriptionService::class.java)
    
    init {
        // Проверяем наличие Whisper при инициализации
        checkWhisperAvailability()
    }
    
    /**
     * Проверяет доступность команды Whisper
     */
    private fun checkWhisperAvailability() {
        try {
            val (exit, out, err) = runCommand(buildWhisperBaseArgs() + listOf("--help"), timeoutSeconds = 20)
            if (exit == 0) {
                logger.info("Whisper доступен: команда '$whisperCommand' найдена")
            } else {
                logger.warn(
                    "Whisper недоступен (exit=$exit). stdout=${out.take(2000)} stderr=${err.take(2000)}. " +
                        "Установите Whisper: pip install -U openai-whisper. Также убедитесь, что установлен ffmpeg."
                )
            }
        } catch (e: Exception) {
            logger.warn("Whisper не найден: команда '$whisperCommand' недоступна. " +
                    "Установите Whisper: pip install -U openai-whisper. " +
                    "Также убедитесь, что установлен ffmpeg.")
        }
    }
    
    /**
     * Транскрибирует аудиофайл в текст
     * @param audioFile путь к аудиофайлу
     * @return транскрибированный текст или null в случае ошибки
     */
    fun transcribe(audioFile: File): String? {
        if (!audioFile.exists()) {
            logger.error("Аудиофайл не найден: ${audioFile.absolutePath}")
            return null
        }
        
        return try {
            logger.info("Начинаю транскрипцию файла: ${audioFile.name}")
            
            // Конвертируем файл в формат, который понимает Whisper (если нужно)
            val processedFile = ensureWavFormat(audioFile)
            
            val args = buildWhisperBaseArgs() + buildList {
                add(processedFile.absolutePath)
                add("--model"); add(whisperModel)
                add("--output_dir"); add(tempDir)
                add("--output_format"); add("txt")
                add("--fp16"); add("False")
                whisperLanguage?.let {
                    add("--language"); add(it)
                }
            }

            val (exitValue, output, errorOutput) = runCommand(args, timeoutSeconds = 10 * 60)
            
            if (exitValue == 0) {
                // Whisper создает файл с расширением .txt в указанной директории
                // Имя файла будет таким же, как у входного файла, но с расширением .txt
                val baseName = processedFile.nameWithoutExtension
                val txtFile = File(tempDir, "$baseName.txt")
                
                // Если файл не найден по ожидаемому пути, пробуем найти его в директории входного файла
                val txtFileAlt = File(processedFile.parent, "$baseName.txt")
                val finalTxtFile = when {
                    txtFile.exists() -> txtFile
                    txtFileAlt.exists() -> txtFileAlt
                    else -> null
                }
                
                if (finalTxtFile != null && finalTxtFile.exists()) {
                    val transcription = finalTxtFile.readText().trim()
                    logger.info("Транскрипция завершена успешно. Длина текста: ${transcription.length}")
                    
                    // Удаляем временные файлы
                    finalTxtFile.delete()
                    if (processedFile != audioFile) {
                        processedFile.delete()
                    }
                    
                    transcription
                } else {
                    logger.error("Файл транскрипции не найден. Проверенные пути: ${txtFile.absolutePath}, ${txtFileAlt.absolutePath}")
                    logger.debug("Вывод Whisper: ${output.take(2000)}")
                    logger.debug("Ошибки Whisper: ${errorOutput.take(2000)}")
                    null
                }
            } else {
                logger.error("Ошибка при транскрипции. Код выхода: $exitValue. Ошибка: ${errorOutput.take(4000)}. Вывод: ${output.take(4000)}")
                null
            }
        } catch (e: Exception) {
            logger.error("Исключение при транскрипции: ${e.message}", e)
            null
        }
    }

    private fun buildWhisperBaseArgs(): List<String> {
        // whisperCommand может быть "whisper" или "python3 -m whisper"
        // Поэтому разбиваем по пробелам (без сложного shell-parsing).
        return whisperCommand.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private fun runCommand(args: List<String>, timeoutSeconds: Long): Triple<Int, String, String> {
        if (args.isEmpty()) throw IllegalArgumentException("Empty command")

        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(false)

        val process = try {
            pb.start()
        } catch (e: IOException) {
            throw IllegalStateException("Не удалось запустить команду: ${args.joinToString(" ")}", e)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val tOut = Thread {
            process.inputStream.bufferedReader().use { r ->
                r.forEachLine { line -> stdout.appendLine(line) }
            }
        }
        val tErr = Thread {
            process.errorStream.bufferedReader().use { r ->
                r.forEachLine { line -> stderr.appendLine(line) }
            }
        }
        tOut.isDaemon = true
        tErr.isDaemon = true
        tOut.start()
        tErr.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return Triple(124, stdout.toString(), "Timeout after ${timeoutSeconds}s\n${stderr}")
        }

        tOut.join(2_000)
        tErr.join(2_000)

        return Triple(process.exitValue(), stdout.toString(), stderr.toString())
    }
    
    /**
     * Конвертирует аудиофайл в WAV формат, если это необходимо
     * Whisper может работать с разными форматами, но для надежности конвертируем в WAV
     */
    private fun ensureWavFormat(audioFile: File): File {
        // Проверяем формат файла
        val extension = audioFile.extension.lowercase()
        
        // Если уже WAV, возвращаем как есть
        if (extension == "wav") {
            return audioFile
        }
        
        // Для других форматов (ogg, m4a, mp3) Whisper обычно работает напрямую
        // Но если возникнут проблемы, можно добавить конвертацию через ffmpeg
        // Пока возвращаем файл как есть, так как Whisper поддерживает многие форматы
        return audioFile
    }
}
