# Sayline

Разговоры становятся текстом.

Sayline — Android-приложение для записи и расшифровки русской речи на устройстве. Оно определяет говорящих, сохраняет реплики с временными отметками и экспортирует стенограмму в JSON. Все модели включены в APK; регистрация и подключение к сети не нужны.

## Материалы решения

| Материал | Расположение |
|---|---|
| Установочный APK | [Sayline.apk](artifacts/apk/Sayline.apk) |
| Исходный код | [src/mobile](src/mobile) |
| Установка и использование | [Инструкция](docs/INSTALLATION.md) |
| Архитектура и модели | [Архитектура](docs/ARCHITECTURE.md) |
| Ограничения | [Ограничения](docs/LIMITATIONS.md) |
| Собственные тесты | [Результаты](docs/TEST_RESULTS.md) |
| Видеодемонстрация | [Демо](artifacts/demo/README.md) |
| Презентация — будет добавлена до 13:30 | [Материалы защиты](artifacts/presentation/README.md) |
| Лицензии моделей и библиотек | [Лицензии](src/mobile/MODEL_LICENSES.md) |

## Получение проекта

Большие файлы хранятся через Git LFS. Для получения исходников, моделей и APK:

```sh
git lfs install
git clone https://github.com/Dteaher/sayline.git
cd sayline
git lfs pull
```

Для установки без Git скачайте APK из [раздела Releases](https://github.com/Dteaher/sayline/releases/latest). APK содержит все шесть файлов моделей и токенов; дополнительных загрузок в приложении нет.

## Сборка

Android 8.0+, ARM64 или x86_64. Для разработки: JDK 17, Android SDK 36, Build Tools 36.0.0. Gradle 9.3.1 и AGP 9.1.0 закреплены в проекте.

На Windows:

```powershell
cd src/mobile
. ./scripts/env.ps1
./scripts/build.ps1
adb install -r ./dist/Sayline.apk
```

На Linux/macOS при настроенных JAVA_HOME и ANDROID_HOME: `cd src/mobile && chmod +x gradlew && ./gradlew :app:assembleDebug`.

Проверки: `./gradlew :app:testDebugUnitTest :app:lintDebug`; Android-тесты — `:app:connectedDebugAndroidTest`. Отдельные тяжёлые исследования включаются флагами и не требуются для обычной сборки. Инструкция по воспроизводимой пересборке JNI — [native/README.md](src/mobile/native/README.md).

## Возможности

- Импорт WAV/M4A/MP3 и запись микрофона.
- Русская речь с пунктуацией, автоматические группы говорящих, временные интервалы.
- История, воспроизведение, исправление текста и имён участников.
- JSON с `speaker_id`, `start_ms`, `end_ms`, `text`; отправка текста через системное меню.
- Обработка в фоне, отмена, светлая и тёмная темы.

Диаризация остаётся вероятностной: близкие голоса и короткие реплики могут определяться неверно. Результаты измерений и известные слабые случаи опубликованы вместе с решением.
