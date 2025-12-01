#!/bin/bash

# Проверяем наличие PERPLEXITY_API_KEY
if [ -z "$PERPLEXITY_API_KEY" ]; then
    echo "❌ Ошибка: PERPLEXITY_API_KEY не установлен!"
    echo "Установите переменную окружения:"
    echo "  export PERPLEXITY_API_KEY=your_api_key"
    exit 1
fi

echo "🚀 Запускаю Docker контейнер..."
docker-compose up --build

