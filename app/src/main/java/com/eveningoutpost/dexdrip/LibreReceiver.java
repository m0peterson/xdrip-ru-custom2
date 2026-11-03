package com.eveningoutpost.dexdrip;

import static com.eveningoutpost.dexdrip.Home.get_engineering_mode;
import static com.eveningoutpost.dexdrip.models.JoH.emptyString;
import static com.eveningoutpost.dexdrip.models.Libre2Sensor.Libre2Sensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.format.DateFormat;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.GlucoseData;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.Libre2RawValue;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.models.UserError.Log;
import com.eveningoutpost.dexdrip.utilitymodels.Intents;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.utilitymodels.StatusItem;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class LibreReceiver extends BroadcastReceiver {
    private static final String TAG = LibreReceiver.class.getSimpleName();
    private static final Object lock = new Object();
    private static volatile String libre_calc_doku = "wait for next reading...";
    private static volatile String bluetoothAddress = "";
    private static volatile String connectionState = "";
    private static volatile long last_reading = 0;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (DexCollectionType.getDexCollectionType() != DexCollectionType.LibreReceiver) return;
        final PendingResult pending = goAsync();
        new Thread(() -> {
            PowerManager.WakeLock wl = JoH.getWakeLock("libre-receiver", 60000);
            try {
                synchronized (lock) {
                    handleIntent(context, intent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to process Libre bridge broadcast: " + e);
            } finally {
                JoH.releaseWakeLock(wl);
                if (pending != null) pending.finish();
            }
        }, "libre-receiver").start();
    }

    // Synchronous dispatch also lets tests exercise the actual broadcast payloads.
    void handleIntent(final Context context, final Intent intent) {
        if (intent == null || intent.getAction() == null
                || DexCollectionType.getDexCollectionType() != DexCollectionType.LibreReceiver) return;
        switch (intent.getAction()) {
            case Intents.LIBRE2_ACTIVATION: {
                Bundle manager = intent.getBundleExtra("bleManager");
                saveSensorStartTime(intent.getBundleExtra("sensor"),
                        manager == null ? null : manager.getString("sensorSerial"));
                Sensor.createDefaultIfMissing();
                break;
            }
            case Intents.LIBRE2_SCAN: {
                Bundle sas = intent.getBundleExtra("sas");
                if (sas == null) return;
                Sensor.createDefaultIfMissing();
                // Prefer realtime if history repeats the same timestamp. Each batch is
                // chronological; backfill never runs the live trend/alert path.
                insertScanValues(sas.getBundle("realTimeGlucoseReadings"), false);
                insertScanValues(sas.getBundle("historicGlucoseReadings"), true);
                Home.staticRefreshBGChartsOnIdle();
                break;
            }
            case Intents.LIBRE2_CONNECTION: {
                Bundle manager = intent.getBundleExtra("bleManager");
                if (manager != null) bluetoothAddress = manager.getString("sensorAddress");
                connectionState = intent.getStringExtra("connectionState");
                break;
            }
            case Intents.LIBRE2_BG: {
                Libre2RawValue current = processIntent(intent);
                if (current == null) return;
                if (!BgReading.last_within_millis(DexCollectionType.getCurrentDeduplicationPeriod())) {
                    long smoothingMinutes = Pref.getStringToInt("libre_filter_length", 25);
                    if (smoothingMinutes <= 0) smoothingMinutes = 25;
                    long interval = smoothingMinutes == 25 ? 20 : smoothingMinutes;
                    List<Libre2RawValue> values = Libre2RawValue.weightedAverageInterval(
                            interval, current.timestamp, current.serial);
                    values.add(current);
                    double value = calculateWeightedAverage(values, current.timestamp,
                            TimeUnit.MINUTES.toMillis(smoothingMinutes));
                    BgReading.bgReadingInsertLibre2(value, current.timestamp, current.glucose);
                }
                if (!Libre2RawValue.exists(current.timestamp, current.serial)) current.save();
                clearNFCsensorAge();
                break;
            }
            default:
                Log.d(TAG, "Ignoring Libre action: " + intent.getAction());
        }
    }

    private static void insertScanValues(Bundle data, boolean history) {
        if (data == null) return;
        List<GlucoseData> values = new ArrayList<>();
        for (String key : data.keySet()) {
            Bundle item = data.getBundle(key);
            if (item == null) continue;
            double glucose = item.getDouble("glucoseValue");
            long timestamp = item.getLong("timestamp");
            if (!Double.isFinite(glucose) || glucose <= 0 || timestamp <= 0) continue;
            values.add(new GlucoseData((int) Math.round(glucose), timestamp));
        }
        values.sort(Comparator.comparingLong(g -> g.realDate));
        for (GlucoseData g : values) {
            LibreAlarmReceiver.insertLibreBridgeBg(g.glucoseLevelRaw, g.realDate,
                    DexCollectionType.getCurrentDeduplicationPeriod(), history);
        }
    }

    private static void clearNFCsensorAge() {
        if (Pref.getInt("nfc_sensor_age", 0) != 0) Pref.setInt("nfc_sensor_age", 0);
    }

    private static Libre2RawValue processIntent(Intent intent) {
        Bundle manager = intent.getBundleExtra("bleManager");
        String serial = manager == null ? null : manager.getString("sensorSerial");
        double glucose = intent.getDoubleExtra("glucose", 0);
        long timestamp = intent.getLongExtra("timestamp", 0);
        if (emptyString(serial) || !Double.isFinite(glucose) || glucose <= 0 || timestamp <= 0) {
            Log.e(TAG, "Invalid Libre bridge payload");
            return null;
        }
        Bundle sas = intent.getBundleExtra("sas");
        Bundle metadata = sas == null ? null : sas.getBundle("currentSensor");
        saveSensorStartTime(metadata, serial);
        Sensor sensor = Sensor.currentSensor();
        if (sensor == null) Sensor.create(timestamp, serial);
        last_reading = timestamp;
        Libre2RawValue value = new Libre2RawValue();
        value.timestamp = timestamp;
        value.glucose = glucose;
        value.serial = serial;
        return value;
    }

    private static void saveSensorStartTime(Bundle sensor, String serial) {
        if (sensor == null || emptyString(serial) || !sensor.containsKey("sensorStartTime")) return;
        long start = sensor.getLong("sensorStartTime");
        if (start <= 0 || start > JoH.tsl()) return;
        Sensor last = Sensor.currentSensor();
        if (last != null && !last.uuid.equals(serial)) {
            Sensor.stopSensor();
            last = null;
        }
        if (last == null) Sensor.create(start, serial);
    }

    static double calculateWeightedAverage(List<Libre2RawValue> values, long now, long duration) {
        double sum = 0;
        double weightSum = 0;
        DecimalFormat format = new DecimalFormat("#,###,###,##0.00");
        libre_calc_doku = "";
        for (Libre2RawValue value : values) {
            if (duration <= 0 || value.timestamp > now || value.timestamp <= now - duration
                    || !Double.isFinite(value.glucose) || value.glucose <= 0) continue;
            double weight = 1 - (now - value.timestamp) / (double) duration;
            sum += value.glucose * weight;
            weightSum += weight;
            libre_calc_doku += DateFormat.format("kk:mm:ss :", value.timestamp)
                    + " w:" + format.format(weight) + " raw: " + value.glucose + "\n";
        }
        return weightSum > 0 ? Math.round(sum / weightSum) : Double.NaN;
    }

    public static List<StatusItem> megaStatus() {
        final List<StatusItem> l = new ArrayList<>();
        final Sensor sensor = Sensor.currentSensor();
        if (sensor != null) {
            l.add(new StatusItem("Libre2 Sensor", sensor.uuid + "\nStart: " + DateFormat.format("dd.MM.yyyy kk:mm", sensor.started_at)));
        }
        if (last_reading > 0) {
            String lastReading = "";
            try {
                lastReading = DateFormat.format("dd.MM.yyyy kk:mm:ss", last_reading).toString();
                l.add(new StatusItem(xdrip.gs(R.string.last_reading), lastReading));
            } catch (Exception e) {
                Log.e(TAG, "Error readlast: " + e);
            }
        }
        if (!emptyString(connectionState)){
            l.add(new StatusItem("Bluetooth Link", connectionState));
        }
        if (get_engineering_mode()) {
            l.add(new StatusItem("Last Calc.", libre_calc_doku));
           if (!emptyString(bluetoothAddress)) {
               l.add(new StatusItem("Bluetooth Mac", bluetoothAddress));
           }
        }
        if (Pref.getBooleanDefaultFalse("Libre2_showSensors")) {
            l.add(new StatusItem("Sensors", Libre2Sensors()));
        }
        return l;
    }
}
