# Сборка и выпуск

Требования: JDK 21, Android SDK 36, build-tools 36.0.0. Gradle Wrapper проверяет SHA-256 дистрибутива.

```sh
bash ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleRelease
```

`android.yml` выполняет эти проверки при push/PR и сохраняет APK в артефакты Actions. Тест больших подписок создаёт 500 JSON с полезной нагрузкой 136 КиБ в каждом и преобразует их с диска; unit-тесты выполняются с heap 64 МиБ.

Для подписанного релиза настройте четыре repository secrets:

- `RELEASE_KEYSTORE_BASE64` — keystore в base64.
- `RELEASE_STORE_PASSWORD` — пароль keystore.
- `RELEASE_KEY_ALIAS` — alias ключа.
- `RELEASE_KEY_PASSWORD` — пароль ключа.

Затем запустите workflow **Release APK** вручную с tag `v1.3.5-api102`. Workflow проверяет тесты, lint, собирает release APK, проверяет подпись, добавляет SHA256SUMS.txt и публикует GitHub Release. Ключи и local.properties не входят в репозиторий или артефакты. Сохраните резервную копию ключа для будущих обновлений с той же подписью.

Без signing secrets автоматический релиз останавливается с явной ошибкой; обычный CI остаётся доступным.

Публикация заранее подписанного APK: workflow Publish verified APK проверяет успешный CI исходного коммита, SHA-256 файла, подпись и выравнивание. Закрытый ключ остаётся вне GitHub. `tag` задаёт версию выпуска; временная ветка `release-artifact/<версия>` содержит только APK.
