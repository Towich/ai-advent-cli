# AI Chat CLI

Простое консольное приложение на Kotlin для взаимодействия с AI через OpenAI API.

## Требования

- Java 17 или выше
- Gradle (включен в проект через wrapper)
- API ключ от OpenAI

## Установка и настройка на macOS

### 1. Установите Java 17+

Проверьте версию Java:
```bash
java -version
```

Если Java не установлена или версия ниже 17, установите через Homebrew:
```bash
brew install openjdk@17
```

Или скачайте с [официального сайта Oracle](https://www.oracle.com/java/technologies/downloads/).

### 2. Получите API ключ OpenAI

1. Зарегистрируйтесь на [OpenAI](https://platform.openai.com/)
2. Перейдите в раздел [API Keys](https://platform.openai.com/api-keys)
3. Создайте новый API ключ

### 3. Установите переменную окружения

Добавьте API ключ в переменные окружения. Для текущей сессии:
```bash
export CURSOR_OPENAI_API_KEY=your_api_key_here
```

Для постоянной установки добавьте в `~/.zshrc` (или `~/.bash_profile` для bash):
```bash
echo 'export CURSOR_OPENAI_API_KEY=your_api_key_here' >> ~/.zshrc
source ~/.zshrc
```

### 4. Опциональные переменные окружения

- `OPENAI_API_URL` - URL API (по умолчанию: `https://api.openai.com/v1/chat/completions`)
- `OPENAI_MODEL` - модель для использования (по умолчанию: `gpt-3.5-turbo`)

Пример:
```bash
export OPENAI_MODEL=gpt-4
```

## Запуск

### Сборка проекта
```bash
./gradlew build
```

### Запуск приложения
```bash
./gradlew run
```

Или соберите JAR и запустите:
```bash
./gradlew jar
java -jar build/libs/ai-advent-cli-1.0-SNAPSHOT.jar
```

## Использование

1. Запустите приложение
2. Введите ваш вопрос или промпт
3. Дождитесь ответа от AI
4. Для выхода введите: `exit`, `quit`, `q` или `выход`

### Пример сессии

```
=== AI Chat CLI ===
Введите ваш вопрос или 'exit' для выхода

Вы: Привет! Как дела?
AI думает...
AI: Привет! У меня всё отлично, спасибо! Я готов помочь вам с любыми вопросами. Как дела у вас?

Вы: exit
До свидания!
```

## Структура проекта

```
src/main/kotlin/
├── Main.kt          # Точка входа, интерактивный цикл
├── ApiClient.kt     # Класс для работы с OpenAI API
├── InputHandler.kt  # Обработка пользовательского ввода
└── Config.kt        # Конфигурация (переменные окружения)
```

## Особенности

- ✅ Интерактивный режим работы
- ✅ Обработка ошибок API
- ✅ Индикатор загрузки
- ✅ Асинхронные запросы через Kotlin Coroutines
- ✅ Поддержка различных команд выхода
- ✅ Чистый и понятный код

## Устранение неполадок

### Ошибка: "CURSOR_OPENAI_API_KEY не установлен"
Убедитесь, что переменная окружения установлена:
```bash
echo $CURSOR_OPENAI_API_KEY
```

### Ошибка: "неверный API ключ"
Проверьте правильность API ключа на [OpenAI Platform](https://platform.openai.com/api-keys)

### Ошибка: "превышен лимит запросов"
Подождите некоторое время или проверьте ваш тарифный план на OpenAI

## Лицензия

MIT

