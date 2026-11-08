# Android приложение

Планируемая сборка: Gradle Kotlin DSL с корнем в `apps/android`. Пять модулей создаются на этапе Android bootstrap; сейчас здесь находится только спецификация.

```text
apps/android/
  app/                     # :app — Compose, ViewModel, навигация и сборка зависимостей
  core/
    domain/                # :core:domain — чистый Kotlin, правила и контракты
    data/                  # :core:data — Room и реализации репозиториев
  platform/
    speech/                # :platform:speech — микрофон, Vosk и TextToSpeech
    reminders/             # :platform:reminders — AlarmManager и уведомления
  gradle/libs.versions.toml # Единые фиксированные версии зависимостей
  settings.gradle.kts
  build.gradle.kts
  gradlew
  gradlew.bat
```

Gradle Wrapper и остальные файлы сборки из дерева пока не созданы. Версии JDK, Kotlin, AGP, Gradle и SDK будут зафиксированы совместно при bootstrap и проверены сборкой; установленная на машине JDK сама по себе не определяет toolchain проекта.

## Организация UI

В `:app` использовать пакеты `feature.pressure`, `feature.medications`, `feature.voice` и `di`. Пакеты экранов не обращаются напрямую к DAO, Vosk или AlarmManager. Отдельные Gradle-модули экранов понадобятся при независимой разработке или заметном росте сборки.

Для пяти модулей достаточно явного `AppContainer`: он создаёт базу, репозитории, распознаватель, планировщик и use cases. ViewModel получают зависимости через фабрики. Переход на DI-фреймворк — отдельное решение при росте графа.

## Зависимости

- `:app` → все четыре библиотечных модуля.
- `:core:data` → `:core:domain`.
- `:platform:speech` → `:core:domain`.
- `:platform:reminders` → `:core:domain`.
- `:core:domain` не зависит от других модулей проекта, Android, Room и Vosk; Kotlin Coroutines допустимы.

Связи между адаптерами передаются через контракты из `domain`. Например, напоминания читают расписание через `MedicationRepository`, а реализацию Room предоставляет `AppContainer`.

## Будущие проверки сборки

После bootstrap из этой директории должны работать `./gradlew test lint assembleDebug` и `gradlew.bat test lint assembleDebug` на Windows. До появления Wrapper это критерии готовности, а не доступные команды.

Общие границы и сценарии описаны в [архитектуре](../../docs/architecture.md).
