package org.example.data.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stdio транспорт для MCP-клиента
 * Общается с MCP-сервером через стандартный ввод/вывод процесса
 */
class StdioMcpTransport(
    private val command: List<String>,
    private val workingDirectory: String? = null,
    private val environment: Map<String, String>? = null
) : McpTransport {
    private val logger = LoggerFactory.getLogger(StdioMcpTransport::class.java)
    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false // Для stdio не нужен pretty print
        encodeDefaults = true
    }

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null
    private var isClosed = false
    private val requestIdCounter = AtomicInteger(1)

    init {
        startProcess()
    }

    private fun startProcess() {
        try {
            val processBuilder = ProcessBuilder(command)
            
            if (workingDirectory != null) {
                processBuilder.directory(java.io.File(workingDirectory))
            }
            
            if (environment != null) {
                processBuilder.environment().putAll(environment)
            }

            // Перенаправляем stderr в stdout для логирования
            processBuilder.redirectErrorStream(true)

            logger.info("Запуск MCP-сервера через stdio: ${command.joinToString(" ")}")
            process = processBuilder.start()

            stdin = BufferedWriter(OutputStreamWriter(process!!.outputStream, Charsets.UTF_8))
            stdout = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))

            logger.info("MCP-сервер запущен (PID: ${process!!.pid()})")
        } catch (e: Exception) {
            logger.error("Ошибка при запуске MCP-сервера: ${e.message}", e)
            throw Exception("Не удалось запустить MCP-сервер: ${e.message}", e)
        }
    }

    override suspend fun sendRequest(requestBody: JsonObject): Result<JsonObject> {
        if (isClosed) {
            return Result.failure(Exception("Транспорт закрыт"))
        }

        val process = this.process
        val stdin = this.stdin
        val stdout = this.stdout

        if (process == null || stdin == null || stdout == null) {
            return Result.failure(Exception("Процесс MCP-сервера не запущен"))
        }

        // Проверяем, что процесс еще жив
        if (!process.isAlive) {
            val exitCode = process.exitValue()
            return Result.failure(Exception("Процесс MCP-сервера завершился с кодом: $exitCode"))
        }

        return try {
            // Сериализуем запрос в JSON строку
            // JsonObject уже является JsonElement, используем toString() для преобразования в JSON строку
            val requestJson = requestBody.toString()
            logger.debug("Отправка запроса через stdio:\n$requestJson")

            // Отправляем запрос в stdin процесса
            // Согласно спецификации MCP, сообщения разделяются новой строкой
            withContext(Dispatchers.IO) {
                stdin.write(requestJson)
                stdin.newLine()
                stdin.flush()
            }

            // Читаем ответ из stdout
            // JSON-RPC ответы также разделяются новой строкой
            // Некоторые серверы могут отправлять логи или другие сообщения перед JSON-RPC ответом,
            // поэтому читаем строки до тех пор, пока не получим валидный JSON
            var responseLine: String? = null
            var jsonResponse: JsonObject? = null
            val maxAttempts = 100 // Максимальное количество попыток чтения (защита от бесконечного цикла)
            var attempts = 0

            while (attempts < maxAttempts && jsonResponse == null) {
                val line = withContext(Dispatchers.IO) {
                    stdout.readLine()
                }

                if (line == null) {
                    // Если процесс закрыл stdout, проверяем статус
                    if (!process.isAlive) {
                        val exitCode = process.exitValue()
                        return Result.failure(Exception("Процесс MCP-сервера завершился с кодом: $exitCode"))
                    }
                    return Result.failure(Exception("Не получен ответ от MCP-сервера (stdout закрыт)"))
                }

                logger.debug("Получена строка через stdio (попытка ${attempts + 1}):\n$line")

                // Пытаемся распарсить как JSON
                try {
                    val trimmedLine = line.trim()
                    // Пропускаем пустые строки
                    if (trimmedLine.isEmpty()) {
                        attempts++
                        continue
                    }
                    
                    // Пытаемся распарсить как JSON
                    val parsed = jsonSerializer.parseToJsonElement(trimmedLine)
                    if (parsed is JsonObject) {
                        jsonResponse = parsed
                        responseLine = trimmedLine
                        logger.debug("Найден валидный JSON-RPC ответ:\n$responseLine")
                        break
                    } else {
                        // Это JSON, но не объект - пропускаем
                        logger.debug("Получен JSON, но не объект, пропускаем: $trimmedLine")
                        attempts++
                        continue
                    }
                } catch (e: Exception) {
                    // Это не JSON - вероятно, лог или другое сообщение от сервера
                    logger.debug("Строка не является JSON (пропускаем): $line")
                    attempts++
                    continue
                }
            }

            if (jsonResponse == null) {
                return Result.failure(Exception("Не удалось получить валидный JSON-RPC ответ от MCP-сервера после $attempts попыток"))
            }

            // Используем найденный JSON-RPC ответ
            val finalJsonResponse = jsonResponse!!

            // Проверяем наличие ошибки
            if (finalJsonResponse.containsKey("error")) {
                val error = finalJsonResponse["error"]?.jsonObject
                val errorCode = error?.get("code")?.jsonPrimitive?.intOrNull ?: -1
                val errorMessage =
                    error?.get("message")?.jsonPrimitive?.content ?: "Неизвестная ошибка"
                logger.error("Ошибка от MCP-сервера: code=$errorCode, message=$errorMessage")
                return Result.failure(Exception("MCP Error ($errorCode): $errorMessage"))
            }

            // Извлекаем результат
            val result = finalJsonResponse["result"]?.jsonObject
                ?: return Result.failure(Exception("Отсутствует поле 'result' в ответе MCP-сервера"))

            Result.success(result)
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("Broken pipe") == true ->
                    "Соединение с MCP-сервером разорвано. Процесс мог завершиться"

                e.message?.contains("Stream closed") == true ->
                    "Поток ввода/вывода закрыт. Процесс MCP-сервера мог завершиться"

                else -> "Ошибка при обмене данными с MCP-сервером через stdio: ${e.message ?: e.javaClass.simpleName}"
            }
            logger.error(
                "Ошибка stdio транспорта: ${e.javaClass.simpleName} - $errorMessage",
                e
            )
            Result.failure(Exception(errorMessage))
        }
    }

    override fun close() {
        if (isClosed) {
            return
        }

        isClosed = true

        try {
            stdin?.close()
        } catch (e: Exception) {
            logger.warn("Ошибка при закрытии stdin: ${e.message}")
        }

        try {
            stdout?.close()
        } catch (e: Exception) {
            logger.warn("Ошибка при закрытии stdout: ${e.message}")
        }

        val process = this.process
        if (process != null && process.isAlive) {
            try {
                logger.info("Завершение процесса MCP-сервера (PID: ${process.pid()})")
                process.destroy()
                
                // Ждем завершения процесса (с таймаутом)
                val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    logger.warn("Процесс не завершился за 5 секунд, принудительное завершение")
                    process.destroyForcibly()
                    process.waitFor()
                }
                logger.info("Процесс MCP-сервера завершен")
            } catch (e: Exception) {
                logger.error("Ошибка при завершении процесса: ${e.message}", e)
                try {
                    process.destroyForcibly()
                } catch (e2: Exception) {
                    logger.error("Ошибка при принудительном завершении процесса: ${e2.message}")
                }
            }
        }

        this.process = null
        this.stdin = null
        this.stdout = null
        logger.debug("Stdio транспорт закрыт")
    }

    override fun isOpen(): Boolean = !isClosed && process?.isAlive == true
}

