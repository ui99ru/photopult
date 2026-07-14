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

Все шесть этапов из брифа реализованы: выбор роли и онбординг, связь через Nearby с
подтверждением кода и авто-реконнектом, H.264-превью с адаптивным битрейтом, съёмка (спуск,
таймер, серия, сохранение в галерею + передача фото на пульт), доводка (вспышка, tap-to-focus,
экспокоррекция, сетка 3×3, foreground-service, уровень батареи камеры) и релизный пайплайн.
Осталась финальная проверка на двух телефонах — протокол в [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md).

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

Подписанные сборки выпускает `.github/workflows/release.yml` по пушу тега вида `v1.2.3`
(или вручную через *workflow_dispatch* с указанием тега). Он собирает `assembleRelease` +
`bundleRelease`, подписывает релизным ключом и публикует APK и AAB в GitHub Release.

`versionName` берётся из тега без `v` (`v1.2.3` → `1.2.3`), `versionCode` вычисляется из semver
как `major*10000 + minor*100 + patch` (`1.2.3` → `10203`), поэтому монотонно растёт.

### Разовая настройка ключа и secrets

1. Сгенерируйте релизный keystore (хранится **только** у вас, в репозиторий не коммитится):

   ```bash
   keytool -genkeypair -v -keystore photopult-release.jks \
     -alias photopult -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Закодируйте его в base64 для secret:

   ```bash
   base64 -w0 photopult-release.jks   # macOS: base64 -i photopult-release.jks
   ```

3. В *Settings → Secrets and variables → Actions* добавьте четыре secret:

   | Secret | Значение |
   |--------|----------|
   | `KEYSTORE_BASE64` | вывод команды base64 из шага 2 |
   | `KEYSTORE_PASSWORD` | пароль keystore |
   | `KEY_ALIAS` | `photopult` (алиас из шага 1) |
   | `KEY_PASSWORD` | пароль ключа |

4. Выпустите релиз:

   ```bash
   git tag v1.0.0 && git push origin v1.0.0
   ```

Локальную подписанную сборку можно собрать теми же свойствами:

```bash
./gradlew assembleRelease \
  -PPHOTOPULT_STORE_FILE=/путь/photopult-release.jks \
  -PPHOTOPULT_STORE_PASSWORD=… -PPHOTOPULT_KEY_ALIAS=photopult -PPHOTOPULT_KEY_PASSWORD=…
```

Без этих свойств `release`-сборка остаётся неподписанной (CI собирает только `debug`).

### Установка APK на телефон

1. Скачайте `photopult-<версия>.apk` из GitHub Release (или debug-APK из артефактов CI).
2. Скопируйте на телефон и откройте — разрешите установку из этого источника.
3. Установите приложение на **оба** телефона (одинаковую сборку — подписи должны совпадать).
4. При обновлении поверх сборки с другой подписью сначала удалите старую:
   `adb uninstall ru.ui99.photopult`.

### Материалы для RuStore

Тексты описаний, политика приватности, возрастной рейтинг и чек-лист скриншотов —
в каталоге [`store/`](store/).

## Приватность

Приложение не собирает и не передаёт данные, работает полностью офлайн. Нет рекламы, встроенных
покупок, аналитики и сетевых запросов наружу — см. [`store/privacy-policy.md`](store/privacy-policy.md).
