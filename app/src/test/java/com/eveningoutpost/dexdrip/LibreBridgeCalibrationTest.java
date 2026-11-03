package com.eveningoutpost.dexdrip;

import android.content.Intent;

import com.activeandroid.query.Select;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.Calibration;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.robolectric.RuntimeEnvironment;

import java.util.UUID;

import static com.google.common.truth.Truth.assertThat;

/** Exercises the real database/creation path with Java assertions enabled. */
public class LibreBridgeCalibrationTest extends RobolectricTestWithConfig {
    private static final long MINUTE = 60000;
    private long now;
    private Sensor sensor;

    @Before
    public void prepare() {
        LibreTestSupport.reset();
        now = System.currentTimeMillis();
        Pref.setString("dex_collection_method", "LibreReceiver");
        Pref.setString("calibrate_external_libre_2_algorithm_type", "calibrate_raw");
        Pref.setBoolean("external_blukon_algorithm", false);
        Pref.setString("units", "mgdl");
        Pref.setBoolean("I_understand", true);
        sensor = new Sensor();
        sensor.uuid = UUID.randomUUID().toString();
        sensor.started_at = now - 60 * MINUTE;
        sensor.save();
    }

    private Calibration calibration(double offset) {
        Calibration c = new Calibration();
        c.sensor = sensor;
        c.sensor_uuid = sensor.uuid;
        c.uuid = UUID.randomUUID().toString();
        c.timestamp = now - MINUTE;
        c.slope = 1;
        c.intercept = offset;
        c.slope_confidence = 1;
        c.sensor_confidence = 1;
        c.save();
        return c;
    }

    @After
    public void cleanup() {
        LibreTestSupport.reset();
    }

    @Test
    public void firstReadingIsVisibleAndSavedBeforeSlopeCalculation() {
        BgReading r = BgReading.bgReadingInsertLibre2(130, now, 135);
        assertThat(r.getId()).isNotNull();
        assertThat(BgReading.last().uuid).isEqualTo(r.uuid);
        assertThat(r.calculated_value).isEqualTo(130);
        assertThat(r.raw_data).isEqualTo(135);
        assertThat(r.calibration_uuid).isNull();
        // ListenerService on Wear requires this marker when no calibration is attached.
        assertThat(r.source_info).contains("Native");
    }

    @Test
    public void minuteReadingUsesCalibration() {
        Calibration c = calibration(10);
        BgReading r = BgReading.bgReadingInsertLibre2(130, now, 135);
        assertThat(r.calculated_value).isEqualTo(140);
        assertThat(r.filtered_calculated_value).isEqualTo(145);
        assertThat(r.calibration_uuid).isEqualTo(c.uuid);
    }

    @Test
    public void noCalibrationIgnoresLeftoverCalibration() {
        calibration(10);
        Pref.setString("calibrate_external_libre_2_algorithm_type", "no_calibration");
        BgReading r = BgReading.bgReadingInsertLibre2(130, now, 135);
        assertThat(r.calculated_value).isEqualTo(130);
        assertThat(r.calibration_uuid).isNull();
        assertThat(r.source_info).contains("Native");
    }

    @Test
    public void scanAndHistoryShareCalibrationAndDeduplication() {
        Calibration c = calibration(10);
        LibreAlarmReceiver.insertLibreBridgeBg(130, now, MINUTE, false);
        LibreAlarmReceiver.insertLibreBridgeBg(140, now - 10 * MINUTE, MINUTE, true);
        LibreAlarmReceiver.insertLibreBridgeBg(130, now, MINUTE, false);
        assertThat(new Select().from(BgReading.class).execute()).hasSize(2);
        BgReading old = BgReading.getForPreciseTimestamp(now - 10 * MINUTE, 1000);
        assertThat(old.calculated_value).isEqualTo(150);
        assertThat(old.calibration_uuid).isEqualTo(c.uuid);
        assertThat(BgReading.last().calculated_value).isEqualTo(140);
    }

    @Test
    public void disablingAndReenablingCalibrationAffectsOnlyNewReadings() {
        calibration(10);
        Pref.setString("calibrate_external_libre_2_algorithm_type", "no_calibration");
        LibreAlarmReceiver.insertLibreBridgeBg(130, now - 10 * MINUTE, MINUTE, true);
        Pref.setString("calibrate_external_libre_2_algorithm_type", "calibrate_glucose");
        LibreAlarmReceiver.insertLibreBridgeBg(130, now, MINUTE, false);
        assertThat(BgReading.last().calculated_value).isEqualTo(140);
        assertThat(BgReading.getForPreciseTimestamp(now - 10 * MINUTE, 1000).calculated_value).isEqualTo(130);
    }

    @Test
    public void scanAndHistoryWithoutCalibrationStayVisible() {
        calibration(10);
        Pref.setString("calibrate_external_libre_2_algorithm_type", "no_calibration");
        LibreAlarmReceiver.insertLibreBridgeBg(130, now, MINUTE, false);
        LibreAlarmReceiver.insertLibreBridgeBg(140, now - 10 * MINUTE, MINUTE, true);
        assertThat(BgReading.last().calculated_value).isEqualTo(130);
        assertThat(BgReading.getForPreciseTimestamp(now - 10 * MINUTE, 1000).calculated_value).isEqualTo(140);
    }

    @Test
    public void invalidInputCreatesNoReadings() {
        assertThat(BgReading.bgReadingInsertLibre2(Double.NaN, now, 130)).isNull();
        assertThat(BgReading.bgReadingInsertLibre2(130, 0, 130)).isNull();
        assertThat(BgReading.bgReadingInsertLibre2(130, now, -1)).isNull();
        assertThat(BgReading.last()).isNull();
    }

    @Test
    public void calibrationMenuFollowsMode() {
        assertThat(hasCalibrationMenu()).isTrue();
        Pref.setString("calibrate_external_libre_2_algorithm_type", "no_calibration");
        assertThat(hasCalibrationMenu()).isFalse();
    }

    private boolean hasCalibrationMenu() {
        for (Intent i : new NavDrawerBuilder(RuntimeEnvironment.getApplication()).nav_drawer_intents) {
            if (i.getComponent() != null && i.getComponent().getClassName().equals(AddCalibration.class.getName())) return true;
        }
        return false;
    }

    @Test
    public void fingerstickCreatesOffsetOnlyCalibration() {
        verifyFingerstick(140);
    }

    @Test
    public void mmolFingerstickIsConvertedBeforeCalibration() {
        Pref.setString("units", "mmol");
        verifyFingerstick(140 / com.eveningoutpost.dexdrip.utilitymodels.Constants.MMOLL_TO_MGDL);
    }

    private void verifyFingerstick(double fingerstick) {
        LibreAlarmReceiver.insertLibreBridgeBg(130, now - 10 * MINUTE, MINUTE, true);
        LibreAlarmReceiver.insertLibreBridgeBg(130, now - 5 * MINUTE, MINUTE, true);
        LibreAlarmReceiver.insertLibreBridgeBg(130, now - MINUTE, MINUTE, false);
        Calibration c = Calibration.create(fingerstick, RuntimeEnvironment.getApplication());
        assertThat(c).isNotNull();
        c = Calibration.lastValid();
        assertThat(c).isNotNull();
        assertThat(c.slope).isEqualTo(1);
        assertThat(c.intercept).isWithin(0.01).of(10);
        LibreAlarmReceiver.insertLibreBridgeBg(140, now + 5 * MINUTE, MINUTE, false);
        assertThat(BgReading.last().calculated_value).isWithin(0.01).of(150);
    }
}
