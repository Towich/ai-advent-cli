# Stage 1: Build
FROM gradle:8.9-jdk17 AS build

WORKDIR /app

# Копируем файлы конфигурации Gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Копируем исходный код
COPY src ./src

# Собираем приложение (создаем fat JAR)
RUN gradle fatJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:17-jre

WORKDIR /app

# Копируем собранный fat JAR из stage сборки
COPY --from=build /app/build/libs/*-fat.jar app.jar

# Создаем пользователя для безопасности
RUN groupadd -r appuser && useradd -r -g appuser appuser
RUN chown -R appuser:appuser /app
USER appuser

# Открываем порт
EXPOSE 8080

# Переменные окружения с дефолтными значениями
ENV SERVER_PORT=8080
ENV PERPLEXITY_API_URL=https://api.perplexity.ai/chat/completions
ENV PERPLEXITY_MODEL=sonar

# PERPLEXITY_API_KEY должен быть передан при запуске контейнера

# Запускаем приложение
ENTRYPOINT ["java", "-jar", "app.jar"]

