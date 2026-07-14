# Фотопульт

**Фотопульт** — Android-приложение для удалённого управления камерой одного смартфона с другого.
Один телефон ставится на штатив и работает камерой, второй становится пультом: живое превью,
зум, вспышка, спуск затвора. Аналог SayCheese — **без рекламы, подписок, аналитики и сетевых
запросов наружу**.

Связь работает **без роутера и без интернета** — напрямую между устройствами через
Google Nearby Connections (Bluetooth/BLE для поиска + Wi-Fi Direct для скоростного канала).

- Основная площадка публикации: **RuStore** (Google Play — возможно, позже).
- Язык интерфейса: русский (основной) + английский.
- Полный бриф проекта: [`briefs/photopult-brief.md`](briefs/photopult-brief.md).
- Дорожная карта по этапам: [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Статус

Этап 1 — **скелет проекта**: выбор роли, онбординг разрешений, проверка Google Play Services,
зелёный CI. Связь, стрим превью и съёмка появляются на следующих этапах (см. ROADMAP).

## Технологии

- Kotlin, Jetpack Compose, Material 3 (тёмная тема по умолчанию)
- CameraX + MediaCodec (аппаратный H.264) — превью и съёмка
- Google Play Services Nearby (Nearby Connections API) — транспорт
- kotlinx.serialization, Coroutines/Flow
- `minSdk 26`, `targetSdk 36`, `applicationId = ru.ui99.photopult`

## Структура

Один модуль `app`; пакеты внутри `ru.ui99.photopult`:

| Пакет | Назначение |
|-------|------------|
| `ui/` | Экраны Compose (выбор роли, онбординг, режимы камеры/пульта) |
| `camera/` | CameraX: превью, съёмка, таймер, серия (Этапы 3–5) |
| `codec/` | H.264 энкодер/декодер, адаптивный битрейт (Этапы 3–4) |
| `net/nearby/` | Nearby Connections: discovery, advertising, реконнект (Этап 2) |
| `net/protocol/` | JSON-протокол команд/событий (канал BYTES) |
| `net/transfer/` | Фоновая передача фото на пульт (Этап 5) |
| `util/` | Разрешения, проверка GMS и прочие утилиты |

## Сборка

Требуется JDK 17 и Android SDK (`compileSdk 36`).

```bash
./gradlew assembleDebug   # debug-APK → app/build/outputs/apk/debug/
./gradlew test            # юнит-тесты
```

CI (`.github/workflows/build.yml`) собирает `assembleDebug` и гоняет тесты на каждый push/PR в
`main`, публикуя debug-APK как артефакт.

## Релизы и публикация

> Будет заполнено на Этапе 6 (release-workflow): генерация keystore, настройка secrets
> (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), сборка подписанных
> APK/AAB по тегу `v*`, инструкция по установке APK и материалы для RuStore в `store/`.

## Приватность

Приложение не собирает и не передаёт данные, работает полностью офлайн. Нет рекламы, встроенных
покупок, аналитики и сетевых запросов наружу — это заявляется в описании и политике RuStore.
