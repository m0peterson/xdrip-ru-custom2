> **Исторический отчёт от 16.09.2026 для `718d082`.** Указанные здесь APK, тесты другой ветки и ограничения не являются результатами проверки текущих изменений. Актуальный отчёт: [CURRENT-VERIFICATION.md](CURRENT-VERIFICATION.md).

# Adversarial verification report — custom xDrip+ build (Libre 2 Gen2 RU recognition + Libre 2 calibration)

**Date:** 2026-09-16 · **Verifier:** Hermes subagent (verification worktree `C:/Users/jar1k/xdrip-verify`, branch `verify-tests`)
**Verified revision:** integration merge `718d082` (branch `ru-custom`), built in `C:/Users/jar1k/xdrip-build-src`
**Artifacts:**
- APK: `C:/Users/jar1k/xdrip-build-src/app/build/outputs/apk/prod/release/app-prod-release.apk` (21,626,989 bytes, mtime 2026-09-16 22:52:05 +0300, sha256 `8dcb4973252392c749385498e6199eebe2d4fca638ff972d6198c38bc6f4d5cb`)
- Test logs: `C:/Users/jar1k/xdrip-workflow/verify-test-run.log` (first attempt, test compile errors), `verify-test-run2.log` (default JVM, `-ea`), `verify-test-run3-da.log` (assertions disabled), `verify-test-run4-final-default.log` and `verify-test-run5-final-da.log` (final confirmation runs on the committed test state)
- Tests committed on `verify-tests`: `664aa7d` (initial suite) + `b16a0bc` (compile fixes + run-configuration note); both commits touch test files only, worktree clean afterwards. Init script `verify-init-da.gradle` is outside the repo.
- Final runs on the committed state: default config → `20 tests completed, 3 failed`; assertions-disabled (production-equivalent) → `BUILD SUCCESSFUL`, 20/20 (XML aggregation: `TOTAL tests=20 failures=0`).

Method: falsification. Every claim was attacked with unit tests on the real product methods, a line-by-line read of the merged diff, and APK forensics. Two environment quirks are stated up front because they shape the results: (1) the Gradle test JVM runs with Java assertions **enabled** (`-ea`), which trips a pre-existing invariant `assert` in `BgReading.find_slope()` — production/Android runs with assertions disabled; (2) no sensor, phone, Juggluco or OOP2 APK is available here, so nothing was exercised end-to-end on hardware.

---

## 0. Verdict summary

| Claim | Verdict | Strongest single piece of evidence |
|---|---|---|
| C1 — Gen2 recognition (phone app) | **CONFIRMED** (wear module diverges, see F2) | `LibreOOPAlgorithm.java:301-307` + `LibreOOPAlgorithmTest` 8/8 green incl. `2B 0A 39 08 4B F7 → Libre2Gen2`, plus dex string `Libre2Gen2` present in the shipped APK |
| C2 — calibration reachability | **CONFIRMED** (with the documented LimiTTer scope expansion, F3; OOP2 dead-end note, F6) | `NavDrawerBuilder.java:75-79` + `NavDrawerLibreCalibrationTest` 3/3 green (Add Calibration present in `calibrate_raw`, absent in `no_calibration`, DexcomG6 unaffected) |
| C3 — calibration effectiveness | **CONFIRMED** for the new bridge funnel and the LIBRE2_BG path; two pre-existing caveats (F1, F4) and one design risk (F5) | `LibreAlarmReceiver.java:79-101` + `Calibration.java:134-151,813-815`; test `bridgeInsertAfterCalibration_appliesLibreOffset` green: 130 raw → calibrate 140 → next insert 140/150.0 with `calibration_uuid` set |
| C4 — no unrelated regression | **CONFIRMED** (9 intended files, +82/−5; no Dexcom/native code touched; `no_calibration` unchanged apart from the new funnel) | `git diff --stat e1407ec9 718d082` = exactly the 9 designed files; APK `versionName=718d082-ru-custom-2026.09.16` |
| C5 | **NOT DEFINED in `verification-plan.md`** (the plan contains C1–C4 only) | Treated as the implicit "the shipped APK actually contains these patches" claim — **CONFIRMED** by the resource + dex proofs in §4 |

---

## 1. C1 — Libre 2 Gen2 recognition

### 1.1 Code evidence (merged tree)

`app/src/main/java/com/eveningoutpost/dexdrip/models/LibreOOPAlgorithm.java`
```java
 54:    public enum SensorType {
 ...
 60:        Libre3(6),
 61:        Libre2Gen2(7);   // Russian region ("Eastern / Rest of World") FreeStyle Libre 2 Gen2 sensors
```
```java
294:    public static SensorType getSensorType(byte[] SensorInfo) {
295:        if (SensorInfo == null) { return SensorType.Libre1; }
298:        if (SensorInfo.length == 24) { return SensorType.Libre3; }        // Libre 3 still wins
301:        // Libre 2 Gen2 sensors (e.g. Russian region 2B 0A 39 08) use a new encryption generation
304:        final int family = SensorInfo[0] & 0xff;
305:        if (family == 0x2b || family == 0x2c || family == 0x76) {
306:            return SensorType.Libre2Gen2;
307:        }
308:        int SensorNum = ...            // unchanged switch; all 10 old case labels untouched
327:        Log.e(TAG, "Sensor type unknown, returning libre1 as failsafe");
328:        return SensorType.Libre1;
```
```java
433:    static public boolean isDecodeableData(byte[] patchInfo) {
434:        SensorType sensorType = getSensorType(patchInfo);
435:        return sensorType == SensorType.LibreUS14Day || sensorType == SensorType.Libre2 || sensorType == SensorType.Libre2Plus;
436:    }
```
→ `Libre2Gen2` is not in the `isDecodeableData` list, so the external OOP2 algorithm is never handed a Gen2 payload.

`app/src/main/java/com/eveningoutpost/dexdrip/NFCReaderX.java`
```java
683:                        SensorType sensorType = LibreOOPAlgorithm.getSensorType(patchInfo);
684:                        Log.uel(TAG, "Libre sensor of type " + sensorType.name() + " detected.");
685:                        if (sensorType == SensorType.Libre2Gen2) {
686:                            Log.ueh(TAG, "Libre 2 Gen2 (RU / eastern region) sensor detected by NFC scan. ...");
687:                            JoH.static_toast_long(gs(R.string.libre2_gen2_not_supported_use_bridge));
688:                            vibrate(context, 3);
689:                            return null;
690:                        }
691:                        if (sensorType == SensorType.Libre3) { Libre3.parsePatchInfo(patchInfo); }
...
699:                        if (sensorType == SensorType.Libre2 || sensorType == SensorType.Libre2Plus) {
700:                            startLibre2Streaming(nfcvTag, patchUid, patchInfo);
```
The guard sits **before** the addressed-mode selection (`addressed = false` only for non-Libre1 at `:694-697`) and before `startLibre2Streaming` — i.e. before every Libre1/Libre2 fallback path named in the claim. `return null` matches the surrounding failure convention (`:660`, `:751`).

Strings (both packaged and verified in the APK, see §4):
- `app/src/main/res/values/strings.xml:1908` — English text, name `libre2_gen2_not_supported_use_bridge`
- `app/src/main/res/values-ru/strings-ru.xml:1759` — Russian text (the em-dash→colon fix is commit `9879821`, 1 line, RU string only)

### 1.2 Test evidence — `app/src/test/java/com/eveningoutpost/dexdrip/models/LibreOOPAlgorithmTest.java` (new)

8 tests, all green in **both** runs (`-ea` and `-da`):

| Test | Asserts |
|---|---|
| `russianGen2PatchInfo_mapsToLibre2Gen2` | `{2b 0a 39 08 4b f7}` (issue #3438 sensor) → `Libre2Gen2` |
| `gen2FamilyBytes_allMapToLibre2Gen2` | `2B 0A 3A 08` (2+ LA), `2C 0A 3A 02` (2+ US), `76 …` → `Libre2Gen2` |
| `libre2Gen2_isDistinctConstantWithValue7` | not an alias of `Libre2`; value 7; old values 0–6 unchanged |
| `isDecodeableData_falseForAllGen2Families` | all three families → `false` |
| `isDecodeableData_unchangedForSupportedTypes` | `9d0830`/`7f0e30`/`c60931`/`e50003` → true; Libre1/unknown → false |
| `knownPatchInfos_keepTheirOldClassification` | full regression table from the plan (`9d0830`,`c50930`→Libre2; `c60931`,`7f0e31`→Libre2Plus; `7f0e30`→Libre2; `df0000`→Libre1; `a20800`→Libre1New; `e50003`,`e60003`→LibreUS14Day; `700010`→LibreProH) |
| `twentyFourBytePatchInfo_stillMapsToLibre3` | 24-byte arrays → `Libre3`, including one starting `2B 0A 39` (documents that the Libre 3 length check precedes the family check) |
| `unknownAndNullPatchInfo_stillFallBackToLibre1` | `{12 34 56}` → `Libre1`; `null` → `Libre1` |

Raw XML results (`app/build/test-results/testProdDebugUnitTest/TEST-…LibreOOPAlgorithmTest.xml`, run 3):
`com.eveningoutpost.dexdrip.models.LibreOOPAlgorithmTest tests=8 failures=0 errors=0`

### 1.3 Counter-evidence / scope limits
- **F2 — the wear module is not guarded.** `wear/src/main/java/com/eveningoutpost/dexdrip/NFCReaderX.java:631` has the identical detection point (`"Libre sensor of type … detected."`) but was left unchanged: a Gen2 sensor there falls through to `PersistentStore.setString("LibreVersion", "1")` and the Libre1-style multiblock commands. Only the enum + `getSensorType` branch were ported to wear (`wear/…/models/LibreOOPAlgorithm.java:59,301`). The design doc (A4) allowed skipping the wear guard when the wear module lacks the string resource — it does (`grep libre2_gen2_not_supported_use_bridge wear/src/main/res` → no hits) — but the skip is **not documented in `delivery-readme.md`** (grep for "wear" → no hits). Not a regression (master had no Gen2 concept at all) but the claim "NFCReaderX aborts the NFC read" is true for the phone app only.
- The Gen2 byte triple itself is taken from the issue-#3438 screenshot and DiaBLE; it cannot be re-derived from first principles here.

---

## 2. C2 — Calibration reachability

### 2.1 Code evidence

`app/src/main/java/com/eveningoutpost/dexdrip/utils/DexCollectionType.java`
```java
215:    public static boolean isLibreCalibrationEnabled(DexCollectionType collector) {
216:        return hasLibre(collector) && !isLibreOOPNonCalibratebleAlgorithm(collector);
219:    public static boolean isLibreCalibrationEnabled() { return isLibreCalibrationEnabled(getDexCollectionType()); }
```
`app/src/main/java/com/eveningoutpost/dexdrip/NavDrawerBuilder.java`
```java
74:            if (is_active_sensor) {
75:                if (DexCollectionType.isLibreCalibrationEnabled(collector)) {
78:                    this.nav_drawer_options.add(context.getString(R.string.add_calibration));
79:                    this.nav_drawer_intents.add(new Intent(context, AddCalibration.class));
80:                } else if (!CollectionServiceStarter.isBTShare(context)) {
81:                    ... unchanged Dexcom chain (lines 81-110) ...
```
`collector` is the local `DexCollectionType.getDexCollectionType()` (`:43`); the old chain is byte-identical to master (`git diff` shows only the `if`→`else if` rewrite), so `no_calibration` and every non-Libre collector keep the old behaviour.

### 2.2 Test evidence

`app/src/test/java/com/eveningoutpost/dexdrip/NavDrawerLibreCalibrationTest.java` (new, 3 tests, green in both runs):
- `libreReceiverWithCalibrationEnabled_showsAddCalibration` — mock sensor + 3 readings, `dex_collection_method=LibreReceiver`, `calibrate_raw` → `AddCalibration.class` present, `DoubleCalibrationActivity.class` absent.
- `libreReceiverWithNoCalibration_hasNoAddCalibrationEntry` — same setup, `no_calibration` → no Add Calibration entry (old chain).
- `dexcomG6_doesNotGainLibreCalibrationEntry` — `DexcomG6` → no Add Calibration.

`app/src/test/java/com/eveningoutpost/dexdrip/utils/DexCollectionTypeTest.java` (extended, green in both runs):
- `isLibreCalibrationEnabledTest` — LibreReceiver/LibreAlarm true in `calibrate_raw`; true in `calibrate_glucose`; false in `no_calibration`; false with `external_blukon_algorithm=true`; false for DexcomG6/BluetoothWixel/None; no-arg overload follows `dex_collection_method` (LibreReceiver true, DexcomG6 false). The pre-existing `isLibreOOPNonCalibratebleAlgorithmTest` still passes.

Raw XML (run 3): `NavDrawerLibreCalibrationTest tests=3 failures=0 errors=0`; `DexCollectionTypeTest tests=2 failures=0 errors=0`.

### 2.3 Counter-evidence / scope limits
- **F3 — the gate is wider than "LibreReceiver"**: `hasLibre` = `{LimiTTer, LibreAlarm, LimiTTerWifi, LibreWifi, LibreReceiver}` (`DexCollectionType.java:99`), so LimiTTer-family users also gain the drawer entry. The design doc flagged this ("behaviour change to be aware of", §4 Hunk 1 note) but the restriction to `LibreReceiver || LibreAlarm` was not applied. Pinned truthfully in the new test (`LimiTTer` asserted `true`).
- **F6 — LibreAlarm/OOP2 dead end (pre-existing, out of scope)**: the helper is also true for `LibreAlarm`, so OOP2 users in `calibrate_raw` now see "Add Calibration". But OOP2 readings inserted with no calibration go through the no-calibration branch of `BgReading.create()` (`BgReading.java:505-521`) which never assigns `calculated_value`; every consumer filters `calculated_value != 0` (`:903,936,1000,1028,1038`), so `BgReading.last()` returns null and `Calibration.create` cannot produce a calibration ("No close enough reading for Calib (15 min)", `Calibration.java:651-656`). Design R10/Hunk 6 explicitly deferred this. It is a dead-end entry for that collector, not a regression for LibreReceiver, and cannot be executed here without OOP2 + sensor.
- `AddCalibration` itself still requires `Sensor.isActive()` (`AddCalibration.java:201`) — same as before.

---

## 3. C3 — Calibration effectiveness

### 3.1 Code evidence

`app/src/main/java/com/eveningoutpost/dexdrip/LibreAlarmReceiver.java`
```java
 79:    public static boolean applyCalibrationToBridgeData() {
 80:        return DexCollectionType.isLibreCalibrationEnabled() && (Calibration.lastValid() != null);
 81:    }
 87:    public static void insertLibreBridgeBg(int glucose, long timestamp, long margin, boolean quick) {
 88:        if ((glucose <= 0) || (timestamp <= 0)) { ... return; }
 92:        if (applyCalibrationToBridgeData()) {
 93:            if (BgReading.readingNearTimeStamp(timestamp, margin) != null) { ... return; }   // dedup
 96:            final double converted = glucose * 1000;
 97:            BgReading.create(converted, converted, xdrip.getAppContext(), timestamp, quick, LIBRE_SOURCE_INFO);
 98:        } else {
 99:            BgReading.bgReadingInsertFromInt(glucose, timestamp, margin, false, LIBRE_SOURCE_INFO);
100:        }
```
The `Calibration.lastValid() != null` precondition is exactly the R1 guard: the no-calibration branch of `BgReading.create()` (`BgReading.java:505-521`) never sets `calculated_value`, so without the guard every pre-calibration bridge reading would be stored as 0 and vanish from the graph.

`app/src/main/java/com/eveningoutpost/dexdrip/LibreReceiver.java`
```java
 90:  LibreAlarmReceiver.insertLibreBridgeBg((int) Math.round(glucose), timestamp, timeslice, false);   // LIBRE2_SCAN realtime
109:  if (LibreAlarmReceiver.applyCalibrationToBridgeData()) {
112:      LibreAlarmReceiver.insertLibreBridgeBg(g.glucoseLevel, g.realDate,
113:              DexCollectionType.getCurrentDeduplicationPeriod(), true);                              // history, per point
116:  } else { LibreAlarmReceiver.insertFromHistory(gd, false); }                                        // byte-for-byte old path
```
Calibration maths for this collector — offset only, as designed:
```java
Calibration.java: 813: if (CollectionServiceStarter.isLibre2App((Context)null)) { return new Li2AppParameters(); }
Calibration.java: 714-715: calibration.slope = 1; calibration.intercept = sParams.restrictIntercept(bg - raw*slope);
Calibration.java: 147-150: Li2AppParameters.restrictIntercept = Math.min(Math.max(intercept, -40), 20);
Calibration.java: 596: slope_confidence = min(max((4 - |calc_slope*60000|)/4, 0), 1);  → 1.0 for a static bridge reading
```
`LIBRE2_BG` path (unchanged by this merge, `BgReading.java:1309/1330-1355`): `calculated_value = (slope * value) + intercept` when `Calibration.lastValid() != null`; otherwise value stored as-is (`:1311-1328`).

### 3.2 Test evidence — `app/src/test/java/com/eveningoutpost/dexdrip/models/LibreBridgeCalibrationTest.java` (new)

Run 3 (`-da`, production-equivalent): **7/7 green**. Run 2 (default `-ea`): 4/7 green, 3 fail with the pre-existing `find_slope` invariant (F1 below).

| Test | Result | Asserted numbers |
|---|---|---|
| `bridgeInsertWithoutCalibration_keepsCalculatedValueEqualToRaw` | ok (both runs) | insert 130 → `raw_data == 130 == calculated_value`, `!= 0` |
| `bridgeInsertAfterCalibration_appliesLibreOffset` | ok (both runs) | calibrate 140 over raw 130 → `slope == 1.0`, `intercept == 10.0 ± 0.001`; next insert 140 → `raw 140`, `calculated 150.0 ± 1.5`, `calibration_uuid == valid.uuid` |
| `noCalibrationMode_doesNotApplyOffsetToBridgeInsert` | ok (both runs) | calibration exists, mode `no_calibration`, insert 145 → `calculated == raw == 145`, no `calibration_uuid` |
| `dedup_repeatedInsertAtSameTimestamp_createsOneReading` | ok (both runs) | two inserts at the same ts → exactly 1 new row, offset still applied |
| `libre2BgPath_withCalibration_appliesOffset` | ok (run 3 only) | `bgReadingInsertLibre2(150) → calculated 160.0 ± 1.5`, tagged |
| `libre2BgPath_withoutCalibration_storesValueAsIs` | ok (run 3 only) | `calculated == raw == 150` |
| `noCalibrationMode_leftoverCalibrationStillAppliedOnLibre2BgPath_preExisting` | ok (run 3 only) | documents F4: 160 despite `no_calibration` |

Raw XML (run 3 and the final run 5, `-da`): `LibreBridgeCalibrationTest tests=7 failures=0 errors=0`.
Raw XML (run 2 and the final run 4, default): `LibreBridgeCalibrationTest tests=7 failures=3 errors=0`; aggregate line from both Gradle logs: `20 tests completed, 3 failed`.
Per-class totals for the production-equivalent run 5: NavDrawerLibreCalibrationTest 3/0, LibreBridgeCalibrationTest 7/0, LibreOOPAlgorithmTest 8/0, DexCollectionTypeTest 2/0 — `TOTAL tests=20 failures=0`.

### 3.3 Counter-evidence and caveats
- **F1 — pre-existing invariant assert blocks the LIBRE2_BG path under `-ea` (not a regression).** In run 2 the three `bgReadingInsertLibre2` tests fail with
  `java.lang.AssertionError: Invariant condition not fulfilled: calculating slope and current reading wasn't saved before` at `BgReading.find_slope(BgReading.java:1732)`, reached from `bgReadingInsertLibre2(BgReading.java:1324/1351)` — the method calls `find_slope()` **before** `save()`, and `find_slope()` asserts `latest(2).get(0).uuid == this.uuid` (empty list → the third failure is `IndexOutOfBoundsException` instead). Master `e1407ec9` contains the identical code (`git show e1407ec9:…BgReading.java` lines 1310-1330 & 1727-1746) and the assert itself carries the comment *"By default, assertions are disabled at runtime. Add -ea to commandline to enable."* Android release builds run with assertions disabled, so this is unreachable in production and **not** introduced by this merge; it does mean the LIBRE2_BG path cannot be unit-tested under the project's default Gradle test config. The verification therefore separates the two runs and does not weaken any test assertion.
- **F4 — `no_calibration` is not a full "leave-as-is" switch on the LIBRE2_BG path.** A leftover calibration still shifts those readings (test pinned above). Pre-existing: master `BgReading.java:1309` takes `Calibration.lastValid()` unconditionally; the design doc's optional Hunk 5 was not merged. Claim C3's "no_calibration leaves values untouched" is therefore true for the new `insertLibreBridgeBg` funnel (tests green) but not for the whole bridge surface.
- **F5 — history path drops the spline gap-filling in calibration mode.** `insertFromHistory` inserts interpolated points (`LibreAlarmReceiver.java:324-370`), while the new per-point loop does not. Design R5 accepted this trade-off; with a calibration, gaps in history are no longer filled. Not testable without a receiver-level harness.
- **Second-stage calibration remains**: `bgReadingInsertLibre2` stores `raw_data = value = calculated_value` basis (`:1315-1317`, `:1336-1338`), so xDrip's offset is applied to a value Juggluco already calibrated (design §3.4/3.5C documents this; a "single-stage" variant was not implemented).
- Within 20 minutes of a calibration-flagged reading, the shared `BgReading.create()` raw-override (`BgReading.java:591-599`) re-runs `calculate_w_l_s()`; the offset tests were deliberately placed 21 minutes after the calibration to isolate the assertion. Pre-existing Dexcom-shared behaviour (design R2).
- The receiver plumbing itself (`LibreReceiver.onReceive` → bundle parsing → thread) is **not** exercised — only the insert helper, the helper's gating and the drawer are. The plan's optional T4 broadcast test was not written because the receiver spawns a thread and `am broadcast` cannot carry the nested `sas` bundles; a package-private seam would have been required.

---

## 4. APK proofs (patched release APK)

APK: `C:/Users/jar1k/xdrip-build-src/app/build/outputs/apk/prod/release/app-prod-release.apk`
(Not the unpatched baseline: `C:/Users/jar1k/xdrip-ru/…/app-prod-debug.apk` was ignored.)

```
$ ls -la …/app-prod-release.apk
-rw-r--r-- 1 jar1k 197121 21626989 сен 16 22:52 app-prod-release.apk

$ sha256sum …/app-prod-release.apk
8dcb4973252392c749385498e6199eebe2d4fca638ff972d6198c38bc6f4d5cb

$ aapt2 dump badging …/app-prod-release.apk | head -8
package: name='com.eveningoutpost.dexdrip' versionCode='1603091400' versionName='718d082-ru-custom-2026.09.16' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='34'
minSdkVersion:'26' / targetSdkVersion:'26'  (+ NFC and other permissions)

$ apksigner verify --print-certs …/app-prod-release.apk
Signer #1 certificate DN: CN=xDrip RU custom build, OU=DIY, O=Personal, L=Moscow, C=RU
Signer #1 certificate SHA-256 digest: 7891521a53ab7dea4f68b07d2f4dd6b853bcd72efcdc78633083b2f8c916255c
Signer #1 certificate SHA-1 digest:   d28b4ec10530cf3f5bd99990bac974846d68775c

$ aapt2 dump resources …/app-prod-release.apk | grep -A4 libre2_gen2_not_supported_use_bridge
resource 0x7f100337 string/libre2_gen2_not_supported_use_bridge
      () "Libre 2 Gen2 (Russian region) sensor detected. xDrip cannot read this sensor generation directly. Use a bridge app (e.g. Juggluco with the 'Patched Libre' broadcast, or Diabox) with the 'Libre2 Patched' data source - calibrations in xDrip will then work."
      (ru) "Обнаружен сенсор Libre 2 Gen2 (российский регион). xDrip не может читать это поколение сенсоров напрямую. Используйте приложение-мост (например, Juggluco с трансляцией 'Patched Libre' или Diabox) с источником данных 'Libre2 Patched': после этого калибровки в xDrip будут работать."

$ python -c "zipfile: count byte-strings in classes*.dex"
dex entries: ['classes.dex', 'classes2.dex', 'classes3.dex']
Libre2Gen2 -> 1
insertLibreBridgeBg -> 2
isLibreCalibrationEnabled -> 1
applyCalibrationToBridgeData -> 1
```
Provenance: `versionName` is generated by `common.gradle:generateVersionName()` as `git describe --always` + current branch + build date → `718d082` + `ru-custom` + `2026.09.16`, i.e. the APK is built from the integration merge `718d082` on branch `ru-custom` in `xdrip-build-src` (its only untracked file is `app/local.gradle`, whose 30 lines only wire the release signing keystore — no product code, no version suffix).

**Result: the shipped APK contains the Gen2 user-facing string in both locales, the new enum, the bridge-insert helper and the calibration gate.** This is the "implicit C5": the patches are demonstrably not just in the source tree.

---

## 5. C4 — No unrelated regression

```
$ git -C C:/Users/jar1k/xdrip-verify diff --stat e1407ec9 718d082
 LibreAlarmReceiver.java | 29 ++
 LibreReceiver.java      | 12 +-
 NFCReaderX.java         |  6 +
 NavDrawerBuilder.java   |  7 +-
 models/LibreOOPAlgorithm.java | 10 +-
 utils/DexCollectionType.java  | 11 +
 res/values/strings.xml        |  1 +
 res/values-ru/strings-ru.xml  |  1 +
 wear/…/models/LibreOOPAlgorithm.java | 10 +-
 9 files changed, 82 insertions(+), 5 deletions(-)
```
- Exactly the files the two design docs scoped; **`BgReading.java` is untouched** (Hunk 5/6 skipped as designed), no Dexcom/G5/G6 files, no native/`.cpp`, no `build.gradle` / manifest / resource-config changes, no removals beyond the enum-line comma and the `if`→`else if` rewrite.
- Extra commit in the history: `9879821 ru-gen2: replace em dash in RU notification string with colon` — one line, RU string only.
- `no_calibration` behaviour is preserved on every path the merge touches (new funnel: tested; old chain in `NavDrawerBuilder`: unchanged; `insertFromHistory`: unchanged in that mode).
- APK-level: dex still contains the untouched Dexcom classes; nothing in the diff can affect them (all new code is behind `hasLibre()` gates or inside Libre-only files).
- The residual divergences are the ones listed as F1–F6; none is a regression versus `e1407ec9` (each was checked against master with `git show`).

---

## 6. What cannot be verified here (no live sensor / phone / Juggluco / OOP2)

1. A real NFC scan of a `2B 0A 39 08` sensor producing the toast and aborting — the guard's position and logic are proven, the physical tag interaction is not.
2. That a genuine RU Gen2 tag delivers exactly `2B 0A 39 08 …` (evidence is the issue-#3438 screenshot + DiaBLE source only).
3. The Juggluco ("Patched Libre") → `LibreReceiver.onReceive` → `processValues` end-to-end flow, including the exact intent extras/`bleManager` bundle, smoothing window, and `LIBRE2_BG` insertion in-situ (my tests call the insert functions directly).
4. Alarms, Nightscout uploads, graph rendering and System-Status diagnostics (`libre_calc_doku`) with the calibrated values.
5. The OOP2/LibreAlarm route (F6) — needs OOP2 and a sensor.
6. Real 5-minute-cadence interaction with the 20-minute raw-override window (F1 caveat/R2) and the history retro-step (R5) with real data.
7. The wear module (F2) — no Wear device/build of the wear APK’s runtime path.
8. Whether `xdrip`-side calibration visibly moves the graph *in the app* (only the DB-level numbers are proven).

## 7. Files produced by this verification

- Tests (committed on `verify-tests`): `app/src/test/java/com/eveningoutpost/dexdrip/models/LibreOOPAlgorithmTest.java` (new, 8 tests), `app/src/test/java/com/eveningoutpost/dexdrip/models/LibreBridgeCalibrationTest.java` (new, 7 tests), `app/src/test/java/com/eveningoutpost/dexdrip/NavDrawerLibreCalibrationTest.java` (new, 3 tests), `app/src/test/java/com/eveningoutpost/dexdrip/utils/DexCollectionTypeTest.java` (extended, +1 test).
- Test logs: `verify-test-run.log`, `verify-test-run2.log`, `verify-test-run3-da.log`; init script `verify-init-da.gradle` (assertions disabled, mirrors production; not part of the repo).
- Product code was not modified (verified: the only changed files in the worktree are the four test files above).
