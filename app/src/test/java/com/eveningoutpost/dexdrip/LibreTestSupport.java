package com.eveningoutpost.dexdrip;

import com.activeandroid.query.Delete;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.Calibration;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.Libre2RawValue;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.utilitymodels.SensorSendQueue;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

/** Isolate the existing static context/preferences and ActiveAndroid DB between scenarios. */
final class LibreTestSupport {
    static void reset() {
        xdrip.setContextAlways(RuntimeEnvironment.getApplication());
        ReflectionHelpers.setStaticField(Pref.class, "prefs", null);
        Pref.getInstance().edit().clear().commit();
        new Delete().from(BgReading.class).execute();
        new Delete().from(Calibration.class).execute();
        new Delete().from(SensorSendQueue.class).execute();
        new Delete().from(Sensor.class).execute();
        Libre2RawValue.updateDB();
        new Delete().from(Libre2RawValue.class).execute();
        Sensor.setUnitTestStopTime(0);
        JoH.clearCache();
    }
}
