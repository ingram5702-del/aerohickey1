# GitHub AAB и Google Play Release

Этот проект настроен так, чтобы GitHub Actions собирал signed `.aab`, а после первого ручного релиза мог автоматически загружать обновления в Google Play Console.

## 1. GitHub Secrets

Открой репозиторий GitHub -> `Settings` -> `Secrets and variables` -> `Actions` -> `Secrets` и добавь:

- `ANDROID_KEYSTORE_BASE64` - upload keystore в base64.
- `ANDROID_KEYSTORE_PASSWORD` - пароль keystore.
- `ANDROID_KEY_ALIAS` - alias ключа.
- `ANDROID_KEY_PASSWORD` - пароль ключа.
- `PLAY_SERVICE_ACCOUNT_JSON` - JSON сервисного аккаунта Google Play Console. Можно добавить позже, после первого ручного AAB.

На macOS keystore можно преобразовать в base64 так:

```bash
base64 -i upload-keystore.jks | pbcopy
```

Если у тебя уже есть рабочий keystore от другого Android-приложения, можно использовать его же только если ты точно хочешь подписывать это приложение тем же upload key. Главное: после первого релиза в Google Play этот ключ менять нельзя без процедуры reset upload key.

## 2. GitHub Variables

Открой `Settings` -> `Secrets and variables` -> `Actions` -> `Variables` и добавь:

- `GOOGLE_PLAY_DEPLOY_ENABLED` = `false` для первого этапа.
- `GOOGLE_PLAY_TRACK` = `internal`.
- `GOOGLE_PLAY_RELEASE_STATUS` = `completed`.

После того как первый AAB будет загружен в Play Console вручную и приложение будет создано, поменяй:

```text
GOOGLE_PLAY_DEPLOY_ENABLED=true
```

После этого каждый push в `main` будет собирать новый AAB и отправлять его в Google Play на выбранный track.

## 3. Первый AAB

1. Открой GitHub -> `Actions` -> `Android Release`.
2. Нажми `Run workflow`.
3. Оставь `deploy_to_play=false`.
4. Дождись завершения workflow.
5. Скачай artifact `neon-hockey-release-aab-v...`.
6. Загрузи `.aab` вручную в Google Play Console для первого релиза.

Первый ручной upload нужен, потому что Google Play должен создать приложение, package name и привязать upload key.

## 4. Автозагрузка следующих обновлений

После первого ручного релиза:

1. Создай service account в Google Cloud / Play Console.
2. Дай ему доступ к этому приложению в Google Play Console.
3. Скачай JSON ключ service account.
4. Добавь весь JSON в GitHub Secret `PLAY_SERVICE_ACCOUNT_JSON`.
5. Установи `GOOGLE_PLAY_DEPLOY_ENABLED=true`.

Теперь следующие изменения, попавшие в ветку `main`, будут автоматически загружаться в Google Play Console.

## 5. Версии

В GitHub Actions `versionCode` берется из `github.run_number`, поэтому он автоматически растет. Это обязательно для Google Play: каждый новый AAB должен иметь `versionCode` больше предыдущего.

Локально без переменных окружения проект остается на `versionCode=1` и `versionName=1.0`, чтобы обычная debug-разработка не зависела от GitHub.
