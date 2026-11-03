# Среда разработки, сборка APK и тесты

Для настройки телефона см. [GUIDE.md](GUIDE.md). Этот файл предназначен для разработчика, который собирает и проверяет модификацию.

## 1. Версии и зависимости

| Компонент | Версия / требование |
|---|---|
| JDK | **17**, нужен и для Gradle, и для Java toolchain модулей |
| Gradle | **8.14.3**, используйте wrapper из репозитория |
| Android Gradle Plugin | **8.13.2**, задан в `build.gradle` |
| Kotlin Gradle Plugin | **2.2.0**, задан в `build.gradle` |
| Android SDK Platform | **android-34** |
| Android Build Tools | **35.0.0** |
| Platform Tools | `adb` для установки/диагностики на телефоне |
| Телефон | Для приложения Android 8.0 / API 26 или новее; телефон с NFC/Bluetooth для аппаратной проверки |

Сборка включает Wear-компонент как зависимость приложения, даже если задача названа `:app:...`. Первый запуск скачивает значительный объём зависимостей. Не запускайте несколько полных Gradle-сборок одновременно. Начните с четырёх workers; Gradle настроен на heap 4 ГБ.

Для обычного debug/dev APK не нужны аккаунт Abbott, токен GitHub, Nightscout или ключ релизной подписи. Настройка сторонних сервисов не является условием компиляции приложения.

## 2. Получите исходники

Если репозиторий ещё не скачан:

```bash
git clone --branch ru-custom https://github.com/m0peterson/xdrip-ru-custom2.git
cd xdrip-ru-custom2
git status --short
git rev-parse HEAD
```

В облачной среде используйте уже существующий checkout `/workspace/xdrip-ru-custom2`. Каждая задача изолирована; дополнительный Git worktree не требуется, если это отдельно не запрошено.

Проверьте текущий diff до любых изменений. Не очищайте checkout командой `git reset --hard` или `git clean`, если в нём есть чужая работа. Имя текущей локальной ветки в облаке может отличаться от исходной `ru-custom`; точный HEAD и diff важнее имени.

## 3. Установите JDK и SDK

### Android Studio (Windows, Linux, macOS)

1. Установите Android Studio из [официального источника](https://developer.android.com/studio).
2. Установите JDK 17. В настройках Gradle JDK укажите именно JDK 17; наличие нового встроенного JDK Studio не заменяет Java toolchain 17.
3. Откройте корень проекта, где лежат `settings.gradle` и `gradlew`.
4. Через SDK Manager установите Android SDK Platform 34, Build Tools 35.0.0, Platform Tools и Command-line Tools.
5. Укажите SDK в `local.properties` через `sdk.dir=...`, если IDE не создала его сама. Этот файл локальный, игнорируется Git.
6. Дождитесь Gradle Sync. Не соглашайтесь автоматически на обновление AGP/Gradle/Kotlin во время проверки этой версии проекта: это отдельное изменение, которое может затронуть data binding и сборку.

### Командная строка (пример Linux/macOS)

Установите JDK 17 и Command-line Tools из официальных источников. Подставьте свои абсолютные пути:

```bash
export JAVA_HOME=/absolute/path/to/jdk-17
export ANDROID_HOME=/absolute/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

java -version
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  'platforms;android-34' 'build-tools;35.0.0' 'platform-tools'

bash gradlew --version
```

Прочитайте и примите лицензии в SDK Manager. При ручном скачивании проверяйте checksum по официальной metadata/странице поставщика; не отключайте TLS и не заменяйте ожидаемый checksum после ошибки.

В Windows задайте `JAVA_HOME` и `ANDROID_HOME` в настройках переменных среды, затем используйте `gradlew.bat` вместо `bash gradlew`.

### Уже подготовленная облачная среда

```bash
source /workspace/.setup-xdrip/env.sh
cd /workspace/xdrip-ru-custom2
java -version
bash gradlew --version
```

Локальная активация устанавливает JDK 17, SDK и writable Gradle cache из `/workspace/.setup-xdrip`, а также Java proxy и доверие системному Java truststore. Никаких серверов для сборки запускать не нужно. Скрипт обновления/восстановления инструментов в этой среде:

```bash
bash /workspace/.setup-xdrip/install.sh
```

Файлы `.setup-xdrip` относятся к конкретной облачной среде и не являются файлами проекта. Для другого компьютера используйте обычную установку выше. Учтите: живые Gradle-процессы не гарантированно переживают восстановление среды.

## 4. Сеть, прокси и типичные ошибки зависимостей

Нужны HTTPS-доступ и доверенные сертификаты к:

- `github.com` и хостам GitHub загрузок — checkout и возможные перенаправления Gradle;
- `services.gradle.org`, `downloads.gradle.org` — wrapper;
- `plugins.gradle.org`, `plugins-artifacts.gradle.org` — плагины;
- `dl.google.com` — Android SDK и Google Maven;
- `repo.maven.apache.org`, `repo1.maven.org` — Maven Central;
- `jitpack.io` — в том числе `com.github.jamorham:ReplaceTokenPreprocessor:1.5`;
- `raw.github.com`, `raw.githubusercontent.com` — legacy Maven repository проекта;
- `api.foojay.io` — только если разрешаете автоматическую загрузку недостающих Java toolchains; при локальном JDK 17 обычно не требуется.

Список описывает зависимости проекта; конкретное перенаправление может требовать дополнительного домена. В управляемой среде меняйте политику доступа через её настройки, не обходите прокси.

Java/Gradle могут не использовать `HTTPS_PROXY` автоматически. При корпоративном прокси настройте `https.proxyHost`, `https.proxyPort`, `http.proxyHost`, `http.proxyPort` в пользовательской конфигурации Gradle или через параметры Java. Доверие сертификату настраивается через CA/truststore. Не используйте `curl -k`, отключение проверки TLS, подмену артефактов или токены в tracked-файлах.

- **HTTP 403 от прокси:** выясните разрешён ли нужный домен. Это не обязательно ошибка самого Maven/JitPack и не повод запрашивать новый GitHub token.
- **UnknownHostException в Java, но curl работает:** проверьте proxy-параметры Java.
- **PKIX / certificate path:** настройте доверенный CA в Java; не выключайте проверку.
- **Read timed out:** проверьте тот же URL через прокси; после устранения причины повторите команду с сохранённым cache. Не начинайте с удаления всего `.gradle`.
- **No matching Java installation 17:** установите JDK 17 и проверьте `JAVA_HOME`; JDK 21 сам по себе не удовлетворяет pinned toolchain 17.
- **Permission denied у gradlew:** запускайте `bash gradlew` или настройте executable bit осознанно.

## 5. Быстрый APK для разработки

```bash
bash gradlew :app:assembleFastDev --max-workers=4 --console=plain
```

В Windows:

```powershell
.\gradlew.bat :app:assembleFastDev --max-workers=4 --console=plain
```

`fastDev` — вариант для разработки без minify/shrink приложения, с сокращённым набором ресурсов. Для проверки русского интерфейса и полного состава ресурсов используйте `prodDev`:

```bash
bash gradlew :app:assembleProdDev --max-workers=4 --console=plain
```

APK находятся под `app/build/outputs/apk/fast/dev/` или `app/build/outputs/apk/prod/dev/`. Точное имя и путь проверьте в каталоге, а не угадывайте по имени старого релиза:

```bash
find app/build/outputs/apk -type f -name '*.apk'
```

Debug/dev APK подписывается debug-ключом. Он подходит для локальной проверки и не обязан устанавливаться поверх чужого release APK. Не публикуйте его как подписанный стабильный релиз.

## 6. Тесты

Сначала целевая проверка RU/моста/калибровки:

```bash
bash gradlew :app:testFastDevUnitTest \
  --tests '*LibreBridgeCalibrationTest' \
  --tests '*LibreReceiverTest' \
  --tests '*LibreGen2Test' \
  --max-workers=4 --console=plain
```

Затем существующий набор unit-тестов выбранного варианта:

```bash
bash gradlew :app:testProdDevUnitTest --max-workers=4 --console=plain
```

Для полного набора используйте `prodDev`, чтобы проверять все переводы. Результаты:

- HTML: `app/build/reports/tests/testProdDevUnitTest/index.html`;
- JUnit XML: `app/build/test-results/testProdDevUnitTest/TEST-*.xml`.

У целевого запуска `testFastDevUnitTest` те же каталоги называются `testFastDevUnitTest`.

В подготовленной облачной среде с Java-прокси полный запуск выполняется так:

```bash
bash gradlew -I /workspace/.setup-xdrip/local-mock-tests.gradle \
  :app:assembleProdDev :app:testProdDevUnitTest --max-workers=4 --console=plain
```

Этот локальный init-скрипт добавляет `http.nonProxyHosts` для localhost и двух имён (`carelink.minimed.eu`, `clcloud.minimed.eu`), которые `EditableCookieJarTest` специально разрешает в `127.0.0.1`. Без настройки тестовый клиент пытается обратиться к локальному серверу через корпоративный прокси. Внешние зависимости продолжают загружаться через прокси; сетевые проверки TLS не отключаются. На компьютере без такого прокси init-скрипт не нужен.

Смотрите число реально выполненных тестов, failures/errors/skipped. `BUILD SUCCESSFUL` с нулём тестов не доказывает работу сценария. Не выключайте Java assertions для маскировки ошибок: новые проверки должны работать при стандартных настройках Gradle.

Более тяжёлые задачи из существующего CI:

```bash
bash gradlew assembleProdRelease testProdReleaseUnitTest --max-workers=4 --console=plain
```

Это сборка нескольких модулей/вариантов и отдельная проверка release-конфигурации. Успешный `fastDev` не подтверждает успех этих задач. Ошибки других тестов нужно разбирать отдельно, а не объявлять весь набор зелёным по целевому фильтру.

## 7. Подписанный APK для длительного использования

1. Сначала добейтесь успешных тестов и debug-сборки.
2. Создайте собственный keystore стандартным мастером **Generate Signed Bundle / APK** Android Studio. Храните ключ и пароли вне Git с резервной копией: без того же ключа обновление поверх установленного APK не получится.
3. Выберите APK, модуль `app`, вариант `prodRelease`.
4. В проекте есть hook `app/local.gradle` для локальной Gradle-конфигурации подписи. Не считайте этот файл автоматически ignored: проверьте `git status`; при использовании добавьте путь в личный `.git/info/exclude`. Сам ключ и пароли не сохраняйте в репозитории.
5. CLI-задача для release приложения — `:app:assembleProdRelease`. Без настроенной подписи результат может быть unsigned и не установится как обычный APK. Проверяйте подпись, а не только наличие файла.
6. Проверьте артефакт:

   ```bash
   "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs /path/to/app.apk
   sha256sum /path/to/app.apk
   ```

   macOS: `shasum -a 256 /path/to/app.apk`; Windows: `Get-FileHash -Algorithm SHA256 .\app.apk`.

7. Запишите SHA-256, fingerprint подписи, HEAD, имя варианта и результаты тестов для **этого** APK. Хеш из старой документации нельзя переносить на новую сборку.

## 8. Установка и аппаратная проверка

1. Сделайте резервную копию xDrip на телефоне согласно [GUIDE.md](GUIDE.md).
2. Включите USB debugging только на время разработки, подтвердите доверие компьютеру.
3. Выполните:

   ```bash
   adb devices
   adb install -r /path/to/app.apk
   ```

4. При `INSTALL_FAILED_UPDATE_INCOMPATIBLE` проверьте подписи. Не удаляйте установленное приложение автоматически скриптом: это может уничтожить данные.
5. Выполните [чек-лист на телефоне](GUIDE.md#11-проверка-готовности-на-реальном-телефоне): приём до калибровки, включение/выключение поправки, фоновая работа, новая сессия сенсора, тревоги и внешние получатели.
6. Для диагностики используйте System Status и журнал xDrip. При необходимости `adb logcat`; перед публикацией фрагментов удалите серийные номера, медицинские данные и сетевые секреты.

Облачные unit-тесты используют Robolectric и тестовую БД. Они проверяют алгоритм и обработку сообщений, но не измеряют точность сенсора, не проверяют прошивку телефона и не доказывают доставку настоящих сообщений от установленного Juggluco.
