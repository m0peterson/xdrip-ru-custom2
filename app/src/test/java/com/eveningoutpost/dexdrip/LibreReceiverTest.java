package com.eveningoutpost.dexdrip;

import android.content.Intent;
import android.os.Bundle;

import com.activeandroid.query.Select;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.Calibration;
import com.eveningoutpost.dexdrip.models.Libre2RawValue;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.utilitymodels.Intents;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;

import static com.google.common.truth.Truth.assertThat;

public class LibreReceiverTest extends RobolectricTestWithConfig {
    private long now;
    private LibreReceiver receiver;

    @Before
    public void prepare() {
        LibreTestSupport.reset();
        now = System.currentTimeMillis();
        receiver = new LibreReceiver();
        Pref.setString("dex_collection_method", "LibreReceiver");
        Pref.setString("calibrate_external_libre_2_algorithm_type", "calibrate_raw");
        Pref.setBoolean("external_blukon_algorithm", false);
    }

    private Intent minute(double glucose, String serial) {
        Bundle manager = new Bundle();
        manager.putString("sensorSerial", serial);
        return new Intent(Intents.LIBRE2_BG).putExtra("glucose", glucose)
                .putExtra("timestamp", now).putExtra("bleManager", manager);
    }

    private void receive(Intent i) {
        receiver.handleIntent(RuntimeEnvironment.getApplication(), i);
    }

    @After
    public void cleanup() {
        LibreTestSupport.reset();
    }

    @Test
    public void minuteBroadcastCreatesSensorAndVisibleReadingWithoutCalibration() {
        receive(minute(130, "sensor-a"));
        assertThat(Sensor.currentSensor().uuid).isEqualTo("sensor-a");
        assertThat(BgReading.last().calculated_value).isEqualTo(130);
        assertThat(BgReading.last().raw_data).isEqualTo(130);
    }

    @Test
    public void repeatedMinuteDoesNotDuplicateGraphOrSmoothingInput() {
        receive(minute(130, "sensor-a"));
        receive(minute(130, "sensor-a"));
        assertThat(new Select().from(BgReading.class).execute()).hasSize(1);
        assertThat(new Select().from(Libre2RawValue.class).execute()).hasSize(1);
    }

    @Test
    public void rejectsMissingSerialNullManagerAndNonFiniteGlucose() {
        receive(new Intent(Intents.LIBRE2_BG));
        receive(minute(130, null));
        receive(minute(Double.NaN, "sensor-a"));
        receive(minute(Double.POSITIVE_INFINITY, "sensor-a"));
        receive(minute(-1, "sensor-a"));
        receive(minute(130, "sensor-a").putExtra("timestamp", 0L));
        assertThat(BgReading.last()).isNull();
        assertThat(Sensor.currentSensor()).isNull();
    }

    @Test
    public void wrongCollectorIgnoresBroadcast() {
        Pref.setString("dex_collection_method", "BluetoothWixel");
        receive(minute(130, "sensor-a"));
        assertThat(Sensor.currentSensor()).isNull();
    }

    @Test
    public void smoothingUsesOnlySameSensorAndPastSamples() {
        Libre2RawValue.updateDB();
        sample(100, now - 60000, "sensor-a").save();
        sample(400, now - 60000, "sensor-b").save();
        sample(400, now + 60000, "sensor-a").save();
        receive(minute(130, "sensor-a"));
        // 100 * .96 + 130 * 1, divided by 1.96 and rounded = 115.
        assertThat(BgReading.last().calculated_value).isEqualTo(115);
    }

    @Test
    public void smoothingRejectsOutOfWindowAndInvalidValues() {
        double value = LibreReceiver.calculateWeightedAverage(Arrays.asList(
                sample(100, now - 60000, "a"), sample(130, now, "a"),
                sample(400, now + 1, "a"), sample(400, now - 25 * 60000, "a"),
                sample(Double.NaN, now, "a")), now, 25 * 60000);
        assertThat(value).isEqualTo(115);
    }

    @Test
    public void newSensorMetadataDoesNotReuseOldCalibration() {
        receive(minute(130, "sensor-a"));
        Calibration c = new Calibration();
        c.sensor = Sensor.currentSensor();
        c.sensor_uuid = c.sensor.uuid;
        c.slope = 1; c.intercept = 10;
        c.slope_confidence = 1; c.sensor_confidence = 1;
        c.timestamp = now; c.uuid = "old-calibration"; c.save();
        Bundle metadata = new Bundle();
        metadata.putLong("sensorStartTime", now - 60000);
        Bundle sas = new Bundle();
        sas.putBundle("currentSensor", metadata);
        receive(minute(150, "sensor-b").putExtra("sas", sas));
        assertThat(Sensor.currentSensor().uuid).isEqualTo("sensor-b");
        assertThat(Calibration.lastValid()).isNull();
    }

    @Test
    public void scanBroadcastProcessesHistoryAndRealtimeWithOneCalibrationGate() {
        receive(minute(130, "sensor-a"));
        Calibration c = new Calibration();
        c.sensor = Sensor.currentSensor(); c.sensor_uuid = c.sensor.uuid;
        c.slope = 1; c.intercept = 10; c.slope_confidence = 1; c.sensor_confidence = 1;
        c.timestamp = now; c.uuid = "calibration"; c.save();
        Bundle history = new Bundle();
        history.putBundle("old", scanValue(120, now - 30 * 60000));
        history.putBundle("new", scanValue(125, now - 20 * 60000));
        Bundle realtime = new Bundle();
        realtime.putBundle("value", scanValue(140, now + 5 * 60000));
        Bundle sas = new Bundle();
        sas.putBundle("historicGlucoseReadings", history);
        sas.putBundle("realTimeGlucoseReadings", realtime);
        Intent scan = new Intent(Intents.LIBRE2_SCAN).putExtra("sas", sas);
        receive(scan);
        receive(scan);
        assertThat(new Select().from(BgReading.class).execute()).hasSize(4);
        assertThat(BgReading.getForPreciseTimestamp(now - 30 * 60000, 1000).calculated_value).isEqualTo(130);
        assertThat(BgReading.getForPreciseTimestamp(now - 20 * 60000, 1000).calculated_value).isEqualTo(135);
        assertThat(BgReading.last().calculated_value).isEqualTo(150);
    }

    private Bundle scanValue(double glucose, long time) {
        Bundle b = new Bundle();
        b.putDouble("glucoseValue", glucose); b.putLong("timestamp", time);
        return b;
    }

    private Libre2RawValue sample(double glucose, long time, String serial) {
        Libre2RawValue value = new Libre2RawValue();
        value.glucose = glucose; value.timestamp = time; value.serial = serial;
        return value;
    }
}
