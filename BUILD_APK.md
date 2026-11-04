# 📦 Сборка APK KidsGuard и публикация на GitHub

## Вариант 1 — GitHub Actions (рекомендуется, ничего не нужно ставить локально)

В проект уже добавлен workflow: [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml).
Он собирает **debug APK** и прикладывает его к запуску как артефакт `KidsGuard-debug-apk`.

1. Загрузите проект в GitHub (см. «Публикация» ниже).
2. Откройте **Actions → Build KidsGuard APK → Run workflow**.
3. Через 5–15 минут скачайте APK из раздела **Artifacts**.
4. Если запушить тег `v3.3.35` — APK автоматически приложится к GitHub Release:
   ```bash
   git tag v3.3.35
   git push origin v3.3.35
   ```

Workflow использует JDK 21, Gradle **9.3.1** (версия из
`gradle/wrapper/gradle-wrapper.properties`) и сам создаёт `debug.keystore`, если его нет.
Секреты для debug-сборки не нужны.

## Вариант 2 — локально в Android Studio

1. Откройте папку проекта в **Android Studio** (Jellyfish или новее).
2. Дождитесь Gradle Sync (он сам скачает Android SDK/Gradle).
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Готовый файл: `app/build/outputs/apk/debug/app-debug.apk`.

## Вариант 3 — локально из командной строки

Нужны: **JDK 17+** (для AGP 9.x лучше JDK 21), **Android SDK** (platform 36, build-tools 36),
**Gradle 9.3.1**.

```bash
# 1. Переменные окружения
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# 2. (если нет debug.keystore) создать ключ для debug-подписи
keytool -genkeypair -v -keystore debug.keystore -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Android Debug,O=Android,C=US"

# 3. Сборка
gradle :app:assembleDebug --no-daemon

# 4. Результат
#    app/build/outputs/apk/debug/app-debug.apk
```

Release-подпись (по желанию):
```bash
export KEYSTORE_PATH=/path/to/my-upload-key.jks
export STORE_PASSWORD=...
export KEY_PASSWORD=...
gradle :app:assembleRelease --no-daemon
```

## Публикация на GitHub

В этом окружении нет доступа к github.com, поэтому пуш нужно выполнить у себя:

```bash
# 1. Создайте пустой репозиторий на github.com (без README), например kidsguard
# 2. В папке проекта:
git remote add origin https://github.com/<ВАШ_ЛОГИН>/kidsguard.git
git branch -M main
git push -u origin main
```

Если git ещё не инициализирован:
```bash
git init
git add -A
git commit -m "KidsGuard: живой экран ПК, стрим с телефона, экономия батареи"
git remote add origin https://github.com/<ВАШ_ЛОГИН>/kidsguard.git
git branch -M main
git push -u origin main
```

С SSH:
```bash
git remote add origin git@github.com:<ВАШ_ЛОГИН>/kidsguard.git
git push -u origin main
```

## Почему APK не собран здесь

В среде выполнения не было Android SDK и Gradle, установлена только Java 8
(AGP 9.x требует JDK 17+), а доступ к `dl.google.com`, `services.gradle.org` и
`github.com` закрыт. Поэтому подготовлен CI-workflow и точные команды выше —
сборка выполнится на стороне GitHub без локальных зависимостей.
