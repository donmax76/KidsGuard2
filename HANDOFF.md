# KidsGuard2 — HANDOFF

Репозиторий: https://github.com/donmax76/KidsGuard2.git  
Ветка: `main`  
Финальный рабочий коммит: **`d18f0b0`**  
Успешный CI-run: **#34** — https://github.com/donmax76/KidsGuard2/actions/runs/42831899457  
Оба job'а зелёные: `build` (APK) и `Build Windows PC agent (.exe)`.

## Артефакты run #34

- APK: `KidsGuard-debug-apk` → https://github.com/donmax76/KidsGuard2/actions/runs/42831899457
  (внизу страницы Artifacts; внутри `app-debug.apk`, ~20,8 МБ)
- ПК-агент: `KidsGuard-PC-Agent` (внутри `hosts_blocker.exe` + `hosts_blocker.py`, ~19,0 МБ)

## Исправленная ошибка компиляции run #33

- `PcBlockerClient.kt:418`: `postLanAction(...)` (suspend) вызывался из
  не-suspend `dispatchCommand()`. Исправлено: вызов обёрнут в
  `scope.launch(Dispatchers.IO) { ... }`. Остальные вызовы suspend-функций
  (`fetchLanStateNow`, `postLanAction`) уже находились внутри корутин/suspend-функций.
- После фикса run #34 — `success` для обоих job'ов.

## Контрольная ревизия 6 задач (коммит 178c552 + d18f0b0)

1. **Связь «как в WhatsApp» / обрыв на 91%** — `DirectP2pSocketEngine.kt`:
   `parentSessionActive`, `setParentSessionActive`, `PARENT_SESSION_START/STOP` из
   `MainActivity.onResume/onPause`; `isParentActivelyConnected()` по сессии +
   страховочный таймаут 10 мин; `startNetworkMonitor()` переподключает сокет только
   при смене Wi-Fi↔cellular/появлении сети/падении сокета (не на каждый
   `onCapabilitiesChanged`); UDP-маяк ребёнка только при активном родителе или без LAN;
   MQTT keepalive 270 с; «Только Wi-Fi» блокирует 4G через `childNetworkAllowed()`.
2. **97,5% батареи** — `REQUEST_POWER_STATS` убран из heartbeat (PING раз в 30 с);
   в `AppEnergyMeter.kt` кэш 3 мин и расчёт от полной ёмкости батареи с защитой
   от дельт < 0,1 мА·ч (100%→91% больше не даёт 97,5%).
3. **Direct Boot / глубокий разряд** — `BootPrefs.kt` (Device Protected Storage),
   `BootCompletedReceiver.startChildFromProtectedStorage()` для `LOCKED_BOOT_COMPLETED`,
   полная загрузка по `ACTION_USER_UNLOCKED`; сервис сохраняет boot-конфиг.
4. **ПК-агент** — `multiprocessing.freeze_support()` + `CreateMutexW` (Global движок,
   Local агент); per-user `usage.json → users[user].today/total`,
   `blocked_now_for/limit_text_for/effective_for/apply_all_for`; AVI `atexit` +
   `_REC_OPEN`; время последнего открытия программ/окон (`prev_proc/prev_title`);
   `write_lock` + фоновый захват экрана, чтобы PC_STATE не блокировался.
5. **Время запуска программ** — `offline_activity_logs.startedAtMs` (Room migration 9→10),
   `MAX(startedAtMs)` в `getAggregatedProcsByDateRange`/`getAggregatedWindowTitlesByDateRange`,
   запись из `PcBlockerClient.syncPcActivityByDateToRoom`, отображение в `PcControlScreen`.
6. **Стабильный онлайн и сохранение лимитов** — `PcBlockerClient` сглаживание онлайна
   (25 с + контрольный `/api/sync`, оффлайн после 30 с); очередь команд
   `sendCommandWithRetry` + Optimistic Lock 10 с для toggle/limit/delete.

---

## Фиксы критических багов (после run #32)

### Связь «как в WhatsApp» + обрыв на 91%
- `DirectP2pSocketEngine.kt`:
  - `startNetworkMonitor()` больше не будит сокет на каждый `onCapabilitiesChanged`
    (экран/сигнал Wi-Fi). Переподключение — только при смене типа сети Wi-Fi↔cellular,
    появлении сети после оффлайна или падении сокета.
  - Сессия родителя `parentSessionActive` + `setParentSessionActive()`;
    `isParentActivelyConnected()` живёт по сессии (страховочный таймаут 10 мин).
  - UDP-маяк ребёнка — только когда родитель активен или нет LAN-соединения.
  - MQTT keepalive 270 с (тихий сокет в покое), ping-цикл 270 с.
- `MainActivity.kt`: onResume → `PARENT_SESSION_START` + `REQUEST_POWER_STATS` (один раз),
  onPause → `PARENT_SESSION_STOP`.
- `KidsGuardBackgroundService.kt` / `ParentalControlRepository.kt`: обработка
  `PARENT_SESSION_START/STOP`.

### 97,5% батареи
- `ParentControlViewModel.kt`: убран `REQUEST_POWER_STATS` из 40-с цикла heartbeat;
  PING — раз в 30 с (вместо 10 с).
- `AppEnergyMeter.kt`: кэш на 3 минуты; доля считается от ПОЛНОЙ ёмкости батареи
  (не от маленькой дельты разряда 100→91), защита от дельт <0,1 мА·ч.

### Выживание после разряда / Direct Boot
- `BootPrefs.kt` (новый): минимальный boot-конфиг (role, childId, allowMobileData,
  familyId) в `createDeviceProtectedStorageContext()`.
- `BootCompletedReceiver.kt`: при `LOCKED_BOOT_COMPLETED` стартует сервис/сокет из
  device-protected prefs без Room; при `ACTION_USER_UNLOCKED` — полная загрузка.
- `KidsGuardBackgroundService.kt`: сохраняет BootPrefs при старте.

### ПК-агент (hosts_blocker.py + копии)
- `multiprocessing.freeze_support()` + существующий `CreateMutexW` (Global для движка,
  Local для агента) — дубли процессов не плодятся.
- Per-user лимиты: `usage.json` теперь хранит `users[<username>].today/total`;
  `blocked_now_for/limit_text_for/effective_for/apply_all_for` считают лимиты по
  выбранному пользователю; `panel_state` отдаёт per-user `today_sec/total_sec`.
- AVI: `_REC_OPEN` + `atexit.register(_close_recording_file)` — заголовок видеофайла
  закрывается при любом выходе/падении.
- Старт-таймстампы: `proc_started/title_started` обновляются при каждом новом открытии
  программы/окна после закрытия (prev_proc/prev_title).
- `p2p_bridge_worker`: запись в сокет под `write_lock`, захват экрана (`/api/screen`,
  `/api/sync`) вынесен в фоновый поток — PC_STATE/команды не блокируются скриншотом.

### Android ПК-контрол
- `PcBlockerClient.kt`:
  - сглаживание онлайна: 25 с + контрольный `/api/sync` перед переходом в «Оффлайн»
    (таймаут 30 с) — нет мигания;
  - очередь команд + Optimistic Lock: `sendCommandWithRetry()` повторяет toggle/limit/
    delete по LAN+P2P до 5 раз; входящий старый PC_STATE не откатывает UI 10 с.
- `KidsGuardDao.kt`/`Entities.kt`/`KidsGuardDatabase.kt`: колонка `startedAtMs` в
  `offline_activity_logs` (migration 9→10), агрегация возвращает `MAX(startedAtMs)`;
  `PcControlScreen.kt` выводит время открытия и из SQL-пути.

---

## Пост-релизные фиксы (после сборки #30)

Финальный коммит с фиксами: `27670694557ac0d634070f4d923ab110404fe9d0` (запушен).
CI run **#32** — ✅ success (оба job’а), artifacts пересобраны.

Пользователь сообщил:
1. Новые кнопки «HD-кадр на ПК» и «REC HD на ПК» не работают; старая локальная
   запись идёт в ужасном качестве.
2. При переключении на другой ПК по pairing code в списке пользователей/истории
   остаются пользователи предыдущего компьютера.

Исправлено:
- **`PcBlockerClient.kt`**:
  - `clearPcScopedState(reason, deleteRoomHistory)` — полный сброс состояния ПК
    (windowsUsers, activityByUser/ByDate, activeWindows, entries, fullLock, кадр,
    запись, скриншот, LAN-сессия) + удаление PC-истории из Room;
  - вызывается в `setPairingCode`, `onUdpBeaconReceived` (при смене code/pc_id),
    `learnPcIdentity`, `resetPcIdentity`;
  - `onUdpBeaconReceived` игнорирует чужой маяк (другой code/pc_id) — раньше любой
    ПК в LAN мог «переключить» приложение;
  - `pcActivityResetAtMs` — 3-секундный запрет записи истории в Room во время
    переключения, чтобы старые/новые пользователи не смешались;
  - `PcConnectionState.pcScriptVersion` — парсинг `script_version` из `PC_STATE`.
- **`KidsGuardDao.kt`**: `deletePcActivityLogsForChild(childId)` — удаляет только
  `deviceType='PC'` логи при смене компьютера.
- **`PcBlockerClient.kt` (stale-кадр при смене ПК)**:
  - `clearPcScopedState()` теперь вызывает `performStop()`, очищает `_liveFrame` и
    `_streamStatus`, инкрементит `streamEpoch` — старый MJPEG/P2P-поток второго ПК
    реально закрывается, а не продолжает отдавать кадры;
  - `performStop()` очищает `_liveFrame` — последний кадр старого ПК больше не
    остаётся на экране.
- **`PcControlScreen.kt`**:
  - сброс `selectedHistoryUser` при смене `pairingCode/pcName`;
  - `snapshotBitmap` очищается, когда `screenBase64` пустой (после смены ПК);
  - fallback-текст «ПК оффлайн • ожидание кадра» вместо «ПК онлайн».
  - предупреждение, если ПК-агент старее 5.5 (HD-кадр/REC HD не поддерживаются),
    с версией агента;
  - локальная MP4-запись теперь стартует из `LaunchedEffect` (без двойного
    `startRecording`).
- **`ScreenVideoRecorder.kt`**: локальная запись вместо 480×800/1,2 Мбит/с теперь
  до 1280×720 (720×1280 для портрета), `RGB_565`, 2 кадра/с, битрейт
  `width*height*6` (2–12 Мбит/с), до 60 кадров. Это заметно повышает качество
  старой кнопки «Запись MP4».
- **`ScreenStreamDialog.kt`**: `startRecording(fps = 1)` под реальный 1 кадр/с.

Важно: новые HD-команды работают только с **новым** `hosts_blocker.exe` версии
**5.5+**. Старый exe их не знает — в UI теперь будет предупреждение с версией
агента. После установки нового exe нужно перезапустить задачи
`HostsBlockerEngine`/`HostsBlockerWatchdog` и агент пользовательской сессии.

---

## ЭТАП 3 (интеграция, pre-build, CI/CD) — ЗАВЕРШЁН

**Статус: сборка успешна.**
- Коммит: `01771eee3a129e4a1efc5e645cda8c362e1c6819` (`main`, запушен на GitHub).
- GitHub Actions run **#30**: ✅ `success` (09.11.2026, 15:28–15:30 UTC).
- GitHub Actions run **#32** (финальный, `2767069`): ✅ `success` (09.11.2026, 16:56–16:57 UTC),
  APK и ПК-агент пересобраны. Run page:
  https://github.com/donmax76/KidsGuard2/actions/runs/42659668084
- Run page #30: https://github.com/donmax76/KidsGuard2/actions/runs/42645020531
- Job `build` — APK собран; artifact **`KidsGuard-debug-apk`** (20.8 МБ ZIP).
- Job `Build Windows PC agent (.exe)` — ПК-агент собран; artifact
  **`KidsGuard-PC-Agent`** (18.7 МБ ZIP: `hosts_blocker.exe` + `hosts_blocker.py`).
- Оба job’а `conclusion: success`, ошибок компиляции не было.
- Artifacts хранятся до 2027-02-07; скачивание — на странице run (нужен вход в GitHub).

Что проверено/исправлено на Этапе 3:
- `AndroidManifest.xml`: исправлено имя action энергосбережения на правильное
  `android.os.action.POWER_SAVE_MODE_CHANGED` (было `android.intent.action...` — receiver
  его не получал).
- `DirectP2pSocketEngine.startEngine()`: сохранённый режим «Только Wi-Fi / Wi-Fi + 4G»
  теперь подтягивается из БД и при `boundChildId = 0` (через `family_config`/
  `currentActiveChildId`/первого ребёнка), иначе после ребута режим терялся.
- `.github/workflows/build-apk.yml`: добавлен job `build-pc-agent` (windows-latest,
  Python 3.12, `pip install pillow pyinstaller`, `pyinstaller --noconsole --onefile
  --name hosts_blocker hosts_blocker.py`, artifact `KidsGuard-PC-Agent`).
- Проверки: `python -m py_compile hosts_blocker.py` — OK; XML манифеста парсится; YAML
  workflow проверен вручную; Kotlin-файлы проверены на баланс скобок (локального Android
  SDK/Gradle в окружении нет, поэтому `assembleDebug` локально не запускался).
- `.gitignore`/сборка: APK собирается job `build` (artifact `KidsGuard-debug-apk`),
  ПК-агент — job `build-pc-agent` (artifact `KidsGuard-PC-Agent`).

Скачивание готовых файлов:
1. Открыть https://github.com/donmax76/KidsGuard2/actions/runs/42659668084
2. Внизу страницы — раздел **Artifacts**.
3. `KidsGuard-debug-apk` → ZIP → внутри `app-debug.apk` (устанавливать на телефон).
4. `KidsGuard-PC-Agent` → ZIP → внутри `hosts_blocker.exe` (ПК-агент) и
   `hosts_blocker.py` (исходник).

При следующих push в `main` workflow запускается автоматически; при падении лог
ошибки будет в Summary (шаг «Show build errors in summary»).

---

## ЭТАП 2 (системные фиксы) — что изменено в этой сессии

### 5.1. Чёрный экран при стриме ПК (Windows Sessions)
Файл: `hosts_blocker.py` (и все его копии в `public/`, `app/src/main/assets/`).
- `_process_session_id()` / `_active_console_session_id()` / `_is_active_console_session()`
  теперь строго сравнивают SessionId процесса с активной консольной сессией и возвращают
  `False` при любой ошибке (раньше — `True`, т.е. «разрешено»).
- `_grab_screen_bgra()` в начале отказывается захватывать, если процесс не в активной
  консольной сессии → SYSTEM-движок (сессия 0) больше не может вернуть чёрный буфер.
- `_is_black_frame()` — эвристика «кадр почти чёрный»; применяется в
  `capture_screen_png()` и `capture_screen_frame()`, чтобы заблокированный/чужой рабочий
  стол не перезаписывал последний хороший кадр.
- Убраны прямые fallback-захваты из движка в `panel_screen()`, `_stream_mjpeg()` и
  `p2p_stream_worker()`: теперь они читают только `agent_screen_frame(max_age=5.0)`.

### 5.2. Сеть ребёнка: «Только Wi-Fi» vs «Wi-Fi + 4G»
Файл: `app/src/main/java/com/example/data/cloud/DirectP2pSocketEngine.kt`
- `setAllowMobileData(allow)` — применяет режим мгновенно и будит сокет.
- `startNetworkMonitor()` — `ConnectivityManager.NetworkCallback`; при `onAvailable/onLost/
  onCapabilitiesChanged` обновляет `hasWifi/hasCellular/hasAnyNetwork` и вызывает
  `wakeGlobalTcp()` (закрывает сокет → цикл сразу переподключается).
- `childNetworkAllowed()` — в «Только Wi-Fi» без Wi-Fi глобальный TCP-релей **не** лезет в
  4G и не крутит реконнект (ждёт NetworkCallback), в «Wi-Fi + 4G» использует любую сеть.
- `currentWifiNetwork()` + `Network.bindSocket()` — глобальный MQTT-сокет привязывается к
  Wi-Fi, если он доступен (и в «Wi-Fi + 4G» предпочитаем Wi-Fi).
- `startChildUdpBeaconLoop()` теперь шлёт LAN-маяк только по Wi-Fi (`wifiIpv4()`), а не по
  мобильной сети по умолчанию.
Файлы-команды: `ParentalControlRepository.kt`, `KidsGuardBackgroundService.kt` —
  `NET_WIFI_ONLY`/`NET_ALL` теперь ещё вызывают `DirectP2pSocketEngine.setAllowMobileData(...)`.

### 5.3. Батарея: мгновенный сбор + формула
- `KidsGuardBackgroundService.kt` / `ParentalControlRepository.kt`: команда
  `REQUEST_POWER_STATS` — ребёнок сразу читает `AppEnergyMeter.read()` и шлёт
  `RealtimeCloudRelay.publishPowerStats(...)`.
- `ParentControlViewModel.kt`: родитель шлёт `REQUEST_POWER_STATS` вместе с heartbeat
  (раз в 40 с) — расход энергии приходит в течение секунд, а не 10–30 минут.
- `AppEnergyMeter.kt`: ёмкость батареи теперь вычисляется из `CHARGE_COUNTER (µА·ч) / заряд %`,
  а не хардкодом `4000 мА·ч`; доля = `app_mAh / total_mAh`. Это объясняет и частично чинит
  расхождение 28,8% vs 42% (см. пояснение ниже).

### 5.4. Глубокий экономный режим при выключенном экране
- `KidsGuardBackgroundService.kt`, цикл батареи/телеметрии:
  - `AppEnergyMeter.read()` (тяжёлые HealthStats) теперь **только при включённом экране**;
  - обновление батареи при выключенном экране раз в 10 минут (вместо 3);
  - задержка цикла при выключенном экране — 5 минут.
- Режим «Только Wi-Fi» без Wi-Fi больше не долбит MQTT-брокер по 4G (см. 5.2) — это был
  главный источник реконнект-шторма в покое.

### 5.5. Выживание после глубокого разряда/перезагрузки
- `AndroidManifest.xml`: в `BootCompletedReceiver` добавлены `ACTION_USER_UNLOCKED`,
  `ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED`, `ACTION_BATTERY_OKAY`,
  `ACTION_POWER_SAVE_MODE_CHANGED`.
- `BootCompletedReceiver.kt`: расширен `isRecoveryEvent` на все эти интенты; при зарядке/
  выходе из энергосбережения/разблокировке снова поднимаются AccessibilityService,
  `DirectP2pSocketEngine` и `KidsGuardBackgroundService`.
- Запрос `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` теперь вызывается при восстановлении
  (`BootCompletedReceiver.restoreServicesAndNotifyParentAfterUpdate`) и при старте
  фонового сервиса (`KidsGuardBackgroundService.startProtectionLoops`), если ограничение
  ещё не снято.
- `KidsGuardAccessibilityService.observeSecurityAndCloud()`: добавлен Watchdog (45 с) —
  если `KidsGuardBackgroundService.isRunning == false`, он перезапускает фоновый сервис.
- `KidsGuardBackgroundService`: статический флаг `isRunning` (в `onCreate`/`onDestroy`).

Не сделано (осознанно, чтобы не ломать сборку без SDK):
- `WorkManager`/`AlarmManager` не добавлялись (в проекте нет зависимости WorkManager).
  Роль периодического сторожа выполняет AccessibilityService-watchdog. Если потребуется
  страховка и после отключения Accessibility — добавить WorkManager periodic 15 мин.

---

## Пояснение: почему 28,8% ≠ 42% (батарея)

- Наше приложение считает долю **с момента последнего отключения зарядки**:
  `app_mAh (UidHealthStats) / total_mAh (дельта CHARGE_COUNTER)`.
- Android в «Настройки → Батарея» показывает долю **с последней полной зарядки** и по
  собственным счётчикам `BatteryStats` (включая экран, wakeups, радио). Окно и методика
  отличаются, поэтому цифры не обязаны совпадать в точности.
- Дополнительные источники расхождения: `UidHealthStats` может недосчитывать часть
  компонентов, а раньше был хардкод `4000 мА·ч` для батарей другой ёмкости. Хардкод убран
  (ёмкость теперь вычисляется из счётчика), поэтому расхождение стало меньше.

---

## 1. Что уже готово (пункты 1–3)

### 1.1. Поворот экрана не ставит поток на паузу

- `app/src/main/AndroidManifest.xml`, activity `.MainActivity`:
  добавлено
  `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"`.
- Причина прежнего поведения: Activity пересоздавалась при повороте, `PcControlScreen` получал
  `ON_PAUSE/ON_STOP`, а `DisposableEffect(lifecycleOwner)` вызывал
  `PcBlockerClient.stopScreenStream()` → оверлей «Поток на паузе».
- Теперь Activity не пересоздаётся, Compose-состояние (`pcStreamQuality`, `showFullPcScreenDialog`,
  зум) сохраняется. Поток по-прежнему осознанно останавливается, когда приложение реально уходит
  в фон (свернули, а не повернули).
- Проверить: открыть живой экран ПК → повернуть телефон → статус должен остаться `LIVE`,
  кадр не должен пропадать/мигать «Пауза».

### 1.2. Pinch-to-Zoom, панорамирование и качество

Файл: `app/src/main/java/com/example/ui/screens/PcControlScreen.kt`

- Полноэкранный плеер (блок `if (showFullPcScreenDialog)`):
  - `detectTransformGestures` — зум `1f..5f` и панорамирование двумя пальцами;
  - панорама ограничивается размерами вьюпорта (`onSizeChanged` + `IntSize`);
  - двойной тап — переключение `100% ↔ 250%`; при зуме `≤1.02` панорама сбрасывается;
  - `graphicsLayer { scaleX/scaleY/translationX/translationY }`.
- Качество: `PcStreamQuality` в
  `app/src/main/java/com/example/data/pc/PcBlockerClient.kt`:
  `ECONOMY 480×270`, `BALANCED 640×360`, `HD 1280×720`, `FULL_HD 1920×1080`.
  Выбор сохраняется в `SharedPreferences` (`saveStreamQuality`/`savedStreamQuality`) и в
  `rememberSaveable`, поэтому не сбрасывается при повороте/перезапуске.
- ПК-агент `hosts_blocker.py`: `stream_touch()`/`stream_params()` теперь принимают до
  `1920×1080` (раньше был жёсткий потолок `1600×900`, из-за чего Full HD молча резался).
- Проверить: щипок увеличивает именно картинку, а не весь экран; после поворота качество
  остаётся выбранным; на Full HD в статусе ПК видно `1920×1080`.

### 1.3. Полупрозрачное меню с автоскрытием

Тот же `PcControlScreen.kt`:

- верхняя/нижняя панели — `Color.Black.copy(alpha = 0.45f)`;
- `LaunchedEffect(showFullPcScreenDialog, pcHudVisible, pcHudInteraction, pcZoom)`:
  через `3_000 ms` бездействия `pcHudVisible = false`;
- одиночный тап по экрану (`detectTapGestures.onTap`) показывает/скрывает меню;
- двойной тап (`onDoubleTap`) — зум, при этом меню принудительно показывается;
- зум/панорама двумя пальцами не считаются одиночным тапом, поэтому не конфликтуют с меню;
- любое взаимодействие (касание, зум, смена качества, кнопки) инкрементит `pcHudInteraction`
  и перезапускает таймер автоскрытия.

### 1.4. HD-скриншот и HD-запись экрана ПК

Android UI (`PcControlScreen.kt`):

- кнопка `HD-кадр на ПК` (`testTag("pc_screenshot_hd_button")`) →
  `PcBlockerClient.requestPcScreenshotHd()`;
- кнопка `REC HD на ПК` (`testTag("pc_record_hd_button")`) →
  `PcBlockerClient.startPcRecording()` / `stopPcRecording()`;
- дублирующие кнопки в полноэкранном HUD;
- в карточке показывается путь к последнему HD-кадру и статус записи
  (`pcState.lastScreenshotPath`, `pcState.pcRecordingActive/Path/Seconds/Frames`).

Android-клиент (`PcBlockerClient.kt`):

- `requestPcScreenshotHd()` → P2P-команда и LAN-POST `/api/screenshot_hd`;
- `startPcRecording(fps, quality)` → `/api/record_start`;
- `stopPcRecording()` → `/api/record_stop`;
- все три команды добавлены в «read-only» список (`sendP2pCommand`), чтобы работали без
  ввода пароля панели, как и обычный просмотр.

ПК-агент (`hosts_blocker.py`):

- `request_hd_screenshot()` пишет `screenshot_hd.flag`; агент пользовательского сеанса
  (`agent_main`) снимает экран в **исходном разрешении монитора** (`_native_screen_size()`),
  сохраняет JPEG quality 92 (или PNG без Pillow) в
  `C:\ProgramData\HostsBlockerPub\screenshots\shot_ГГГГММДД_ЧЧММСС.jpg`;
- `request_record("start")` пишет `record.json`; агент пишет **AVI/MJPEG** в исходном
  разрешении, JPEG quality 85, по умолчанию 10 кадров/с, в
  `C:\ProgramData\HostsBlockerPub\records\screen_ГГГГММДД_ЧЧММСС.avi`;
- статусы пишутся в `screenshot_status.json` и `record_status.json`, движок отдаёт их в
  `panel_state()` как `last_screenshot` и `recording`;
- запись и скриншот делает именно агент сеанса, а не SYSTEM-движок: GDI-захват из сессии 0
  даёт чёрный экран (см. пункт 4.1);
- `agent_main` пересчитывает `_is_active_console_session()` на каждом тике, поэтому после
  переключения пользователя старый агент больше не снимает чёрный экран и останавливает запись.

Ограничения текущей реализации:

- AVI/MJPEG не проигрывается стандартной галереей Android; файл лежит на ПК и открывается
  VLC/Windows Media Player. Для MP4 на телефоне остаётся прежняя кнопка локальной записи
  (`ScreenVideoRecorder`, 1 кадр/с) — она не заменена, чтобы не ломать текущий сценарий.
- Запись требует Pillow на ПК (`pip install pillow`); без Pillow агент вернёт понятную ошибку
  в `record_status.json`.
- Если агент убить во время записи, AVI может остаться с незакрытым заголовком (файл
  частично читается, но лучше добавить `finally`/watchdog в следующей итерации).

### 2.1. Мультиаккаунтность Windows

- `PcBlockerClient.kt`:
  - `PcConnectionState.windowsUsers: List<String>` — все локальные учётки;
  - парсинг `windows_users` из `PC_STATE`, с fallback на активные окна/историю/`full_lock`;
  - `PcActivityItem.startedAtMs` — время первого запуска программы/окна;
  - `selectFullPcLockAccount(account)` — переключение учётки в блоке полной блокировки.
- `PcControlScreen.kt`, карточка «Полная блокировка ПК»:
  - горизонтальный ряд чипов «👤 Учётные записи Windows…»;
  - тап по чипу меняет `childAccountInput` и отправляет аккаунт на ПК
    (`/api/fulllock_schedule`), поэтому блокировка/расписание применяются к выбранному
    пользователю, а не только к `raufh`.
- `hosts_blocker.py`:
  - `list_windows_users()` парсит `net user`;
  - `panel_state()` отдаёт `windows_users`;
  - `_full_lock_usage()` + `activity_seconds_for_user()` +
    `activity_total_seconds_for_user()` — лимиты полной блокировки считаются по активности
    выбранной учётки, а не по общему времени всех сеансов;
  - `apply_full_lock()` по-прежнему делает `net user /active:no` + `WTSLogoffSession`
    именно для выбранного имени.
- История активности разделена по пользователям:
  - `KidsGuardDao.getAggregatedProcsByDateRange` и `getAggregatedWindowTitlesByDateRange`
    теперь группируют по `LOWER(TRIM(packageName/windowTitle)), appName`, где `appName` —
    имя Windows-пользователя; раньше два пользователя с одним `chrome.exe` складывались
    в одну строку;
  - `PcActivityItem.userName` доносит учётку до UI;
  - в карточке истории есть чипы «Все / <учётка>», а при «Все» каждая строка подписана
    именем пользователя;
  - `selectedHistoryUser` хранится в `rememberSaveable`.
- Что осталось на будущее: лимиты обычных правил (`entries[].limit`) в ПК-агенте всё ещё
  общие для всех пользователей (`usage.json`, `__computer__`). Для полного разделения нужно
  перевести `usage.json` на структуру `user -> entry -> seconds` и обновить UI правил.

### 2.2. Точное время запуска программы в истории ПК

- `hosts_blocker.py`, `agent_main()`:
  - `proc_started`, `title_started` — словари `имя -> epoch ms` первого появления за день;
  - `setdefault` на каждом тике, сохранение в `activity_<user>.json`
    (`proc_started`, `title_started`);
  - `save_activity_history_day()` сохраняет эти словари в 30-дневный архив;
  - `panel_state()` отдаёт `started` в каждом элементе `procs`/`titles`.
- `PcBlockerClient.parseUsersActivityJson()` читает `started` в `PcActivityItem.startedAtMs`.
- `PcControlScreen.kt`, история:
  - под временем работы выводится `• запуск ЧЧ:ММ:СС` для программ и
    `• открыто ЧЧ:ММ:СС` для окон/вкладок;
  - при SQLite-агрегате, где старого поля нет, подставляется значение из последнего
    `PC_STATE` по имени; если и его нет — строка не показывается.

### 3. Честный «Онлайн / Оффлайн»

ПК:

- `PcBlockerClient.kt`: `lastPcContactMs`, `markPcContact()`, watchdog в `init` —
  если 15 с не было ни `PC_STATE`, ни кадра, ни UDP-маяка, `isOnline` становится `false`,
  `transportLabel` = `🔴 ПК оффлайн • нет пакетов N с`;
- `markPcContact()` вызывается в `onUdpBeaconReceived`, `ingestP2pStateJson`,
  `ingestP2pScreenJson`, `ingestP2pStreamFrame*`, `parseStateJson`;
- ПК-агент публикует `PC_STATE` каждые ~4 с, этого достаточно для таймаута 15 с.

Телефон ребёнка:

- `RealtimeCloudRelay.kt`:
  - `childLastSeenMs`, `_childOnlineFlow`, `isChildOnline()`, `recomputeChildPresence()`
    (пересчёт раз в 3 с, таймаут 30 с);
  - `markChildSeen()` на `CHILD_STATE`/`CHILD_PATCH` от ребёнка;
  - новый лёгкий тип `CHILD_PONG` и `publishChildPong()`;
- `KidsGuardBackgroundService.kt` и `ParentalControlRepository.kt`: команда `PING` —
  отмечает присутствие родителя, обновляет батарею и отвечает `CHILD_PONG`
  (без GPS и без выгрузки истории). Дублирование нужно, если фоновый сервис ещё не поднялся,
  а приложение ребёнка уже открыто;
- `ParentControlViewModel.kt`: родитель шлёт `PING` каждые 10 с, полный
  `PARENT_ONLINE_HEARTBEAT` — раз в 40 с (раньше всё было раз в 35 с);
- `CommonComponents.kt` (`HeroBannerCard`): новый параметр `isOnline`;
  текст `🟢 Онлайн` / `🔴 Оффлайн` вместо прежнего «Онлайн» по `batteryLevel > 0`;
- `ParentDashboardScreen.kt` собирает `RealtimeCloudRelay.childOnlineFlow` и передаёт
  `isOnline` в `HeroBannerCard`.

Примечание: если телефон ребёнка полностью выключен/в авиарежиме, PING не доставляется,
`childLastSeenMs` перестаёт обновляться и через 30 с UI покажет «Оффлайн». Это ожидаемо.

---

## 2. Проверка и сборка

- Python-часть: `python -m py_compile hosts_blocker.py` — OK (Python 3, bundled runtime).
- AVI-писатель проверен отдельным тестом: файл начинается с `RIFF....AVI `, содержит
  корректные `00dc`-чанки, открывается как MJPEG AVI.
- Kotlin/Android в этой сессии **не собирался**: в рабочем окружении нет Android SDK и
  Gradle wrapper, а GitHub Actions по условию задачи не запускался. Перед релизом
  обязательно выполнить `./gradlew :app:assembleDebug` (или CI) и проверить:
  - поворот на полноэкранном потоке;
  - щипок/панораму/двойной тап;
  - автоскрытие HUD через 3 с и возврат по тапу;
  - переключение учёток в «Полной блокировке ПК»;
  - HD-кадр и REC HD (на ПК должен быть Pillow);
  - «Оффлайн» на ПК через ~15 с после выключения агента и на телефоне через ~30 с.

Копии `hosts_blocker.py` синхронизированы:
`hosts_blocker.py`, `public/hosts_blocker.py`, `public/downloads/hosts_blocker.py`,
`public/assets/aistudio/hosts_blocker.py`, `public/assets/aistudio/hosts_blocker_c2776433.py`,
`app/src/main/assets/hosts_blocker.py` (одинаковый SHA-256).

---

## 3. Карта для следующего этапа (пункт 4) — код этих узлов НЕ переписывался

Ниже — точные файлы, методы и гипотезы. Это исходная точка для модели Pro.

### 4.1. Чёрный экран при захвате экрана ПК (неактивные сессии Windows)

Файл: `hosts_blocker.py`

- `_grab_screen_bgra(dst_w, dst_h)` — GDI: `GetDC(None)`, `CreateDIBSection`,
  `StretchBlt`; источник — **только primary-монитор** `GetSystemMetrics(0/1)`.
- `capture_screen_png()` / `capture_screen_frame()` — PNG/JPEG из BGRA.
- `agent_screen_frame(max_age=2.0)` — движок читает `screen.png`, который пишет агент
  пользовательского сеанса; если кадр старше 2 с — `None`.
- `_is_active_console_session()` — сравнивает `ProcessIdToSessionId` с
  `WTSGetActiveConsoleSessionId`.
- `agent_main()` — `is_console` (теперь пересчитывается каждый тик), запись `screen.png`,
  `stream.json`, `screenshot_hd.flag`, `record.json`.
- `panel_screen(force, w, h, quality)` — отдаёт кадр панели/телефону, ждёт свежий
  `screen.png` от агента.
- `p2p_stream_worker()` / `_stream_mjpeg()` — отдают кадры в P2P/MJPEG.
- `engine_tick()` — SYSTEM-движок; прямой GDI-захват в сессии 0 даёт чёрный экран.

Почему чередуется чёрный кадр:

1. SYSTEM-движок и агент работают в разных сессиях; движок не должен снимать экран сам.
2. Агент неактивного/RDP-сеанса тоже может писать `screen.png`; защита —
   `_is_active_console_session()`, но она вычисляется по PID процесса и не учитывает
   переключение пользователя без перезапуска (сейчас пересчитывается в цикле).
3. `GetSystemMetrics(0/1)` не работает для multi-monitor и для заблокированного экрана.
4. Нет проверки «кадр полностью чёрный»: если агент записал чёрный кадр, движок отдаёт его
   как валидный.
5. `agent_screen_frame(max_age=2.0)` жёстко 2 с; при slow GDI/нагрузке кадр может не успеть.

Направления исправления (для Pro):

- захват через **Windows Graphics Capture / Desktop Duplication API** в сессии пользователя;
- в `agent_*.json` добавить `session_id` и `is_console`, движку выбирать только
  `console == true` и `fresh_agents()`;
- детект чёрного кадра (средняя яркость/гистограмма) и fallback на последний непустой;
- для multi-monitor — `EnumDisplayMonitors` и захват нужного монитора;
- хранить в `screen.png` не только кадр, но и метаданные (session, monitor, timestamp).

### 4.2. Сетевой слой детского Android-агента (Wi-Fi vs Wi-Fi + 4G, реконнект)

Файл: `app/src/main/java/com/example/data/cloud/DirectP2pSocketEngine.kt`

- `isParentActivelyConnected()` — присутствие родителя, таймаут 95 с.
- `markParentPresenceActive()` — обновление присутствия.
- `wifiNetwork()` — находит сеть с `TRANSPORT_WIFI`; `wifiIpv4()` — IPv4 Wi-Fi.
- `startEngine(role, boundChildId)` — запуск движка для роли.
- `startChildTcpServerIfNeeded()` — LAN TCP-сервер.
- `startChildUdpBeaconLoop()` — UDP-маяк (сейчас 60 с при соединении / 30 с без).
- `startGlobalBinaryTcpSocketLoop()` — глобальный TCP-релей, backoff `2 с → 30 с`.
- `publishTcpSocketPacket()` / `handleIncomingJsonPacket()` — публикация/приём.
- `NetworkConnectivityObserver.kt` — `NetworkCallback` (`registerNetworkCallback`),
  `activeNetwork`, `hasTransport(TRANSPORT_CELLULAR)`; отдельного триггера реконнекта
  движка по смене сети нет.

Wi-Fi vs 4G:

- `NET_WIFI_ONLY` / `NET_ALL` в `ParentalControlRepository.kt` (строки ~597–598) только
  пишут `child_profiles.allowMobileData` (`KidsGuardDao.kt` ~145) и публикуют поле.
- В `DirectP2pSocketEngine` **нет** `bindProcessToNetwork`/`Network.bindSocket` для
  мобильного трафика и **нет** запрета сокетов при `allowMobileData = false`.
  То есть переключатель «Только Wi-Fi» сейчас не ограничивает 4G на уровне сокетов.
- LAN-сокеты (`wifiNetwork()`, `wifiIpv4()`) привязаны к Wi-Fi, но глобальный TCP-релей
  идёт через сеть по умолчанию (может уйти в 4G).
- `PcBlockerClient` (`wifiNetwork()` ~286, `wifiIpv4()` ~301, `lanHttpClient()` ~312)
  использует Wi-Fi `socketFactory` для LAN-панели ПК — это единственное место явной привязки.

Направления:

- в `NetworkConnectivityObserver` при смене `activeNetwork` перезапускать
  `startGlobalBinaryTcpSocketLoop()` (сейчас backoff живёт сам);
- при `allowMobileData = false` вызывать `ConnectivityManager.bindProcessToNetwork(wifi)`
  или `Network.bindSocket()` для P2P-сокетов; при `true` — снять bind;
- в `startEngine`/`startGlobalBinaryTcpSocketLoop` логировать фактический транспорт.

### 4.3. Проценты потребления батареи (28,8% против 42% в настройках) и долгая загрузка

Файлы/методы:

- `app/src/main/java/com/example/util/DeviceBatteryUtil.kt`
  - `getAccurateBatteryPercentage(context)` — sticky `ACTION_BATTERY_CHANGED`,
    `EXTRA_LEVEL/EXTRA_SCALE`; fallback `BATTERY_PROPERTY_CAPACITY`.
- `app/src/main/java/com/example/location/LiveLocationTracker.kt`
  - `readRealDeviceBattery()` (~450).
- `app/src/main/java/com/example/service/KidsGuardBackgroundService.kt`
  - `readBatteryLevel()` (~887), цикл батареи/телеметрии (~396–449):
    публикация раз в 60 с при родителе online, иначе раз в 180 с.
- `app/src/main/java/com/example/util/AppEnergyMeter.kt`
  - `read(context)` (~73) — считает долю приложения:
    `usedAppMah / totalMah * 100`, где `totalMah` — дельта
    `BATTERY_PROPERTY_CHARGE_COUNTER`, `appMahNow` — сумма
    `UidHealthStats.STATS_POWER_*` через `appPowerMah()` (~176);
  - `cpuFallback()` (~134) — грубая оценка по CPU-времени;
  - `BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER`/`CAPACITY` (строки ~81–82).
- `app/src/main/java/com/example/data/cloud/ChildPowerStats.kt`
  - `ingest(msg)` — принимает `appEnergyPercent`, `appMahPerHour`, `percentPerHour`.

Почему 28,8% ≠ 42%:

- 28,8% — это **доля приложения в израсходованных мА·ч за окно наблюдения** по
  `UidHealthStats` (сумма CPU/wakelock/radio/GPS/sensors для UID).
- 42% в настройках Android — это **остаток заряда** (или доля экрана/системы в другом
  окне), другая база и другое окно; эти числа не обязаны совпадать.
- `UidHealthStats` на многих OEM неполный/недоступен; `appPowerMah()` тогда возвращает 0 и
  включается `cpuFallback()`.
- `totalMah` может быть 0/отрицательным на некоторых прошивках; используются эвристики
  `capacityMah = totalMah / (levelDrop / 100)`.
- Долгая загрузка 30 минут: `syncRealUsageStats()` (`DeviceUsageTracker.kt` ~350) делает
  `queryUsageStats` + запись Room; `AppEnergyMeter.read()` дорогой (HealthStats);
  `ChildPowerStats` публикуется раз в 60 с; всё это усугубляется частыми реконнектами.

Направления:

- показывать два разных показателя явно: «Заряд телефона: 42%» и
  «Расход KidsGuard за сутки: 28,8%» (с подписью методики);
- кэшировать `HealthStats` (не чаще раза в 5–10 мин), не читать на UI-потоке;
- считать `totalMah` по ёмкости батареи (`BATTERY_PROPERTY_CHARGE_COUNTER` + `Capacity`),
  а не только по дельте;
- на телефоне ребёнка не публиковать энергию чаще 1 раза в 5–10 минут.

### 4.4. Фоновый сервис и WakeLock (разряд 100% → 13% за 50 ч при выключенном экране)

Файл: `app/src/main/java/com/example/service/KidsGuardBackgroundService.kt`

- `onCreate()` (~95) — `startProtectionLoops()`.
- `onStartCommand()` (~153) — `START_STICKY`, `promoteToForeground`.
- `onTaskRemoved()` (~183) — перезапуск без projection.
- `startProtectionLoops()` (~192) — корутины:
  - active app sync,
  - screen usage loop 45 с / 120 с,
  - battery/telemetry 60 с / 180 с,
  - remote command listener,
  - chat listener.
- `buildForegroundNotification()` (~919), `start()` (~965), `stop()` (~998),
  `onDestroy()` (~952).
- `promoteToForeground()` (~110–150) — FGS type `specialUse|mediaProjection|location`.

Файл: `app/src/main/java/com/example/service/KidsGuardAccessibilityService.kt`

- `onCreate()` (~177), `onServiceConnected()` (~212),
  `startMinuteUsageTicking()` (~336), `observeSecurityAndCloud()` (~458),
  `onAccessibilityEvent()` (~731), `onDestroy()` (~1028);
- `batteryLowOrHot()` (~84) — thermal/battery guard.

Файл: `app/src/main/java/com/example/location/LiveLocationTracker.kt`

- `startRealLocationUpdates()` (~479), `stopLocationUpdates()` (~418),
  `CurrentLocationRequest` с `PRIORITY_HIGH_ACCURACY` (~531), fused request (~566),
  `LocationManager` fallback (~592).

Важно:

- В проекте **нет** `PowerManager.WakeLock`, `WAKE_LOCK` permission, `AlarmManager`,
  `WorkManager` (проверено grep по `app/src/main/java`). То есть «WakeLock» как таковой
  не держится; разряд идёт от постоянных пробуждений корутин, GPS, сети и FGS.
- `DeviceSecurityPermissionHelper.isBatteryOptimizationIgnored()` (~325) и
  `requestIgnoreBatteryOptimization()` (~338) есть, но статус не проверяется при
  старте сервиса и не показывается как блокирующее требование.
- `onTaskRemoved` перезапускает сервис — на OEM это может приводить к циклу
  «убит → запущен → снова убит».

Направления:

- измерить Battery Historian: `wakeups`, `mobile radio`, `gps`, `foreground service`;
- заменить частые `while(true) + delay` на `WorkManager`/`AlarmManager` с
  `setExactAndAllowWhileIdle` для редких задач;
- GPS — только по `MAP_VIEWING` (уже частично), не поднимать при `PARENT_ONLINE_HEARTBEAT`;
- проверить, не остаётся ли `mediaProjection` FGS после сворачивания;
- при `onTaskRemoved` не перезапускать сервис без проверки `isBatteryOptimizationIgnored`.

### 4.5. Manifest / BootReceiver / AccessibilityService / WorkManager (блокировка после <13% и полной зарядки)

Файлы:

- `app/src/main/AndroidManifest.xml`
  - permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
    `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_LOCATION`,
    `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
    `SYSTEM_ALERT_WINDOW`, `WRITE_SECURE_SETTINGS`, `PACKAGE_USAGE_STATS`;
  - `KidsGuardBackgroundService`: `android:directBootAware="true"`,
    `android:stopWithTask="false"`, `foregroundServiceType="specialUse|mediaProjection|location"`;
  - `KidsGuardAccessibilityService`: `directBootAware="true"`,
    `BIND_ACCESSIBILITY_SERVICE`, `accessibility_service_config`;
  - `BootCompletedReceiver`: `directBootAware="true"`, фильтры `BOOT_COMPLETED`,
    `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`,
    `PACKAGE_REPLACED`, `ACTION_OTA_INSTALL_STATUS`.
- `app/src/main/java/com/example/receiver/BootCompletedReceiver.kt`
  - `onReceive()` (~28) — OTA + boot;
  - `restoreServicesAndNotifyParentAfterUpdate()` (~158) — читает Room
    `getFamilyConfigOnce()`, стартует `DirectP2pSocketEngine.startEngine("CHILD", childId)`,
    `KidsGuardBackgroundService.start(...)` (~195), `LockOverlayService.start` (~238),
    second-pass Accessibility (~242–247).
- `app/src/main/java/com/example/service/KidsGuardAccessibilityService.kt`
  - `onServiceConnected()` (~212), `onAccessibilityEvent()` (~731),
    `showLockOverlay()` (~936), `bringAppToFront()` (~1021).
- `app/src/main/java/com/example/util/DeviceSecurityPermissionHelper.kt`
  - `restoreAccessibilityServiceAfterOta()` (используется в BootReceiver).
- `WorkManager` в проекте отсутствует: нет зависимости в `app/build.gradle.kts`, нет
  `WorkManager` provider/receiver в манифесте.

Почему после <13% и зарядки приложение «молчит»:

1. При разряде Android включает Battery Saver/Doze; FGS и сеть ограничиваются,
   `START_STICKY` не гарантирует перезапуск.
2. При падении до 0 система может **force-stop** приложение; после зарядки и загрузки
   `BOOT_COMPLETED` для force-stopped пакета не доставляется, пока пользователь сам не
   откроет приложение.
3. `LOCKED_BOOT_COMPLETED` приходит до разблокировки, но `restoreServicesAndNotifyParentAfterUpdate`
   читает **credential-encrypted Room** (`KidsGuardDatabase.getDatabase`), которая в этот
   момент недоступна → исключение → `catch` молча выходит, сервис не стартует.
4. `android:directBootAware="true"` у receiver/service есть, но БД и настройки не переведены
   на device-protected storage; нет `DeviceProtectedStorageContext`.
5. Нет `WorkManager`/`AlarmManager` fallback и нет повторной попытки после
   `USER_UNLOCKED`/`ACTION_USER_UNLOCKED`.
6. OEM-«автозапуск»/оптимизация батареи может блокировать FGS; приложение не проверяет
   `isBatteryOptimizationIgnored` и не требует whitelist.
7. AccessibilityService после force-stop/OEM-clean может быть отключён; restore через
   `WRITE_SECURE_SETTINGS` не всегда помогает.

Направления:

- добавить `BroadcastReceiver` на `Intent.ACTION_USER_UNLOCKED` и повторять инициализацию
  после разблокировки;
- хранить минимальный «boot-конфиг» (role, childId, allowMobileData) в
  `DeviceProtectedStorage`/`SharedPreferences` и стартовать сервис без Room;
- добавить `WorkManager` periodic (15 мин) как watchdog, который проверяет
  `KidsGuardBackgroundService` и `AccessibilityService`;
- добавить `WAKE_LOCK` только если реально нужен (скорее нет), а лучше —
  `setExactAndAllowWhileIdle` для редких задач;
- в `BootCompletedReceiver` не глотать исключения: логировать и retry;
- показывать родителю статус «оптимизация батареи не отключена» и вести в настройки.

---

## 4. Что изменено в этой сессии (файлы)

- `AndroidManifest.xml` — `configChanges` у `MainActivity`.
- `PcControlScreen.kt` — зум/панорама, HUD-автоскрытие, Full HD, кнопки HD-кадра/записи,
  чипы пользователей Windows, время запуска в истории.
- `PcBlockerClient.kt` — `windowsUsers`, `startedAtMs`, watchdog онлайна,
  `requestPcScreenshotHd`, `startPcRecording`, `stopPcRecording`,
  `selectFullPcLockAccount`, поля записи/скриншота, `FULL_HD`.
- `RealtimeCloudRelay.kt` — `childOnlineFlow`, `isChildOnline`, `markChildSeen`,
  `publishChildPong`, обработка `CHILD_PONG`.
- `KidsGuardBackgroundService.kt` — команда `PING` и ответ `CHILD_PONG`.
- `ParentControlViewModel.kt` — `PING` каждые 10 с, heartbeat раз в 40 с.
- `CommonComponents.kt` — `HeroBannerCard(isOnline=...)`, честный текст.
- `ParentDashboardScreen.kt` — подписка на `childOnlineFlow`.
- `hosts_blocker.py` и его копии — `list_windows_users`, `proc_started/title_started`,
  per-user full-lock usage, HD-скриншот, AVI/MJPEG-запись, `record_status`/
  `screenshot_status`, `windows_users`/`recording`/`last_screenshot` в `panel_state`,
  команды `/api/screenshot_hd`, `/api/record_start`, `/api/record_stop` (P2P + LAN),
  Full HD clamp 1920×1080, версия 5.5.

---

## 5. Следующие шаги (рекомендация)

1. Собрать APK в CI и прогнать ручной чек-лист из раздела 2.
2. Починить 4.1 (чёрный экран) — это блокер для HD-записи и потока.
3. Починить 4.2 (реальный Wi-Fi-only + реконнект по смене сети).
4. Починить 4.5 (boot после разряда) — вынести boot-конфиг из credential-encrypted Room
   и добавить `USER_UNLOCKED` + WorkManager watchdog.
5. После этого — 4.3/4.4 (метрики батареи и фоновые циклы).
