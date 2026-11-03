package com.eveningoutpost.dexdrip.models;

import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import org.junit.Test;
import static com.google.common.truth.Truth.assertThat;

public class LibreGen2Test extends RobolectricTestWithConfig {
    @Test
    public void gen2FamiliesAreDistinctAndNotSentToOop2Decoder() {
        for (int family : new int[]{0x2b, 0x2c, 0x76}) {
            byte[] info = {(byte) family, 0x0a, 0x39, 0x08, 0x4b, (byte) 0xf7};
            assertThat(LibreOOPAlgorithm.getSensorType(info)).isEqualTo(LibreOOPAlgorithm.SensorType.Libre2Gen2);
            assertThat(LibreOOPAlgorithm.isDecodeableData(info)).isFalse();
        }
    }

    @Test
    public void existingEuAndLibre3DetectionIsPreserved() {
        assertThat(LibreOOPAlgorithm.getSensorType(new byte[]{(byte) 0x9d, 8, 0x30}))
                .isEqualTo(LibreOOPAlgorithm.SensorType.Libre2);
        assertThat(LibreOOPAlgorithm.getSensorType(new byte[]{(byte) 0xc6, 9, 0x31}))
                .isEqualTo(LibreOOPAlgorithm.SensorType.Libre2Plus);
        byte[] info = new byte[24]; info[0] = 0x2b;
        assertThat(LibreOOPAlgorithm.getSensorType(info)).isEqualTo(LibreOOPAlgorithm.SensorType.Libre3);
    }

    @Test
    public void truncatedPatchInfoDoesNotCrash() {
        for (byte[] info : new byte[][]{null, {}, {0x2b}, {0x2b, 0x0a}}) {
            assertThat(LibreOOPAlgorithm.getSensorType(info)).isEqualTo(LibreOOPAlgorithm.SensorType.Libre1);
        }
    }

    @Test
    public void libreBridgeOffsetLimitsAreUnchanged() {
        Li2AppParameters parameters = new Li2AppParameters();
        assertThat(parameters.restrictIntercept(-100)).isEqualTo(-40);
        assertThat(parameters.restrictIntercept(100)).isEqualTo(20);
        assertThat(parameters.restrictIntercept(10)).isEqualTo(10);
    }
}
