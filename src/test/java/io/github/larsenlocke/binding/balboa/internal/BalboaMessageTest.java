/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 * Copyright (c) 2026 Larsen-Locke and contributors
 *
 * See the NOTICE file distributed with this work for additional information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.larsenlocke.binding.balboa.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.FaultLogResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.FilterCyclesResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.InformationResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.ItemType;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.ModuleIdentificationResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.PanelConfigurationResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SetFilterCyclesMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SetTemperatureMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SetTemperatureScaleMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SetTimeMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SettingsRequestMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SettingsRequestMessage.SettingsType;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SetupParametersResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.StatusUpdateMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.ToggleMessage;

/**
 * Tests for the encoding and decoding of {@link BalboaMessage}s.
 *
 * Inbound payloads are built byte by byte after the layout documented in {@link BalboaMessage}; the CRC test vectors
 * are messages of the Balboa Wi-Fi protocol as documented by the community (ccutrer/balboa_worldwide_app).
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public class BalboaMessageTest {

    /**
     * Builds a complete frame: separator, length, three type bytes, payload, CRC, separator.
     */
    static byte[] frame(int messageType, int... payload) {
        byte[] buffer = new byte[payload.length + 7];
        buffer[0] = 0x7e;
        buffer[1] = (byte) (payload.length + 5);
        buffer[2] = (byte) (messageType >> 16);
        buffer[3] = (byte) (messageType >> 8);
        buffer[4] = (byte) messageType;
        for (int i = 0; i < payload.length; i++) {
            buffer[5 + i] = (byte) payload[i];
        }
        buffer[buffer.length - 2] = BalboaProtocol.calculateCrc(buffer);
        buffer[buffer.length - 1] = 0x7e;
        return buffer;
    }

    /**
     * Builds a status update frame of the given total length with the given bytes set (index = position in the
     * frame, as in the comments of {@link StatusUpdateMessage}).
     */
    static byte[] statusUpdate(int frameLength, int... indexValuePairs) {
        int[] payload = new int[frameLength - 7];
        for (int i = 0; i < indexValuePairs.length; i += 2) {
            payload[indexValuePairs[i] - 5] = indexValuePairs[i + 1];
        }
        return frame(StatusUpdateMessage.MESSAGE_TYPE, payload);
    }

    private static <T> T decode(byte[] frame, Class<T> type) {
        @Nullable
        BalboaMessage message = BalboaMessage.fromBuffer(frame);
        assertNotNull(message);
        assertInstanceOf(type, message);
        return type.cast(message);
    }

    @Nested
    class Crc {
        @Test
        void configurationRequest() {
            byte[] expected = { 0x7e, 0x05, 0x0a, (byte) 0xbf, 0x04, 0x77, 0x7e };
            assertEquals(expected[5], BalboaProtocol.calculateCrc(expected));
        }

        @Test
        void panelSettingsRequest() {
            byte[] expected = { 0x7e, 0x08, 0x0a, (byte) 0xbf, 0x22, 0x00, 0x00, 0x01, 0x58, 0x7e };
            assertEquals(expected[8], BalboaProtocol.calculateCrc(expected));
        }
    }

    @Nested
    class Outbound {
        @Test
        void toggleAddressesTheItemPlusIndex() {
            assertArrayEquals(new byte[] { 0x05, 0x00 }, new ToggleMessage(ItemType.PUMP, 1).getPayload());
            assertArrayEquals(new byte[] { 0x11, 0x00 }, new ToggleMessage(ItemType.LIGHT, 0).getPayload());
            assertArrayEquals(new byte[] { 0x51, 0x00 }, new ToggleMessage(ItemType.HEAT_MODE, 0).getPayload());
            assertEquals(0x0abf11, new ToggleMessage(ItemType.PUMP, 0).getMessageType());
        }

        @Test
        void toggleRejectsReadOnlyItemsAndBadIndices() {
            assertThrows(IllegalArgumentException.class, () -> new ToggleMessage(ItemType.HEATER, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> new ToggleMessage(ItemType.PUMP, BalboaProtocol.MAX_PUMPS));
            assertThrows(IllegalArgumentException.class, () -> new ToggleMessage(ItemType.PUMP, -1));
        }

        @Test
        void celsiusTargetIsDoubledAndClampedToTheRange() {
            assertArrayEquals(new byte[] { 75 }, new SetTemperatureMessage(37.5, true, true).getPayload());
            assertArrayEquals(new byte[] { 80 }, new SetTemperatureMessage(45, true, true).getPayload());
            assertArrayEquals(new byte[] { 53 }, new SetTemperatureMessage(20, true, true).getPayload());
            assertArrayEquals(new byte[] { 20 }, new SetTemperatureMessage(5, true, false).getPayload());
            assertArrayEquals(new byte[] { 52 }, new SetTemperatureMessage(30, true, false).getPayload());
        }

        @Test
        void fahrenheitTargetIsClampedToTheRange() {
            assertArrayEquals(new byte[] { 100 }, new SetTemperatureMessage(100, false, true).getPayload());
            assertArrayEquals(new byte[] { 104 }, new SetTemperatureMessage(110, false, true).getPayload());
            assertArrayEquals(new byte[] { 50 }, new SetTemperatureMessage(40, false, false).getPayload());
        }

        @Test
        void temperatureScale() {
            assertArrayEquals(new byte[] { 0x01, 0x01 }, new SetTemperatureScaleMessage(true).getPayload());
            assertArrayEquals(new byte[] { 0x01, 0x00 }, new SetTemperatureScaleMessage(false).getPayload());
        }

        @Test
        void timeIsClampedAndCarriesThe24hFlag() {
            assertArrayEquals(new byte[] { 14, 30 }, new SetTimeMessage(14, 30, false).getPayload());
            assertArrayEquals(new byte[] { (byte) (23 | 0x80), 0 }, new SetTimeMessage(25, -3, true).getPayload());
        }

        @Test
        void settingsRequests() {
            assertArrayEquals(new byte[] { 0x20, (byte) 0xff, 0x00 },
                    new SettingsRequestMessage(SettingsType.FAULT_LOG).getPayload());
            assertArrayEquals(new byte[] { 0x00, 0x00, 0x01 },
                    new SettingsRequestMessage(SettingsType.PANEL).getPayload());
            assertArrayEquals(new byte[] { 0x04, 0x00, 0x00 },
                    new SettingsRequestMessage(SettingsType.SETUP_PARAMETERS).getPayload());
        }

        @Test
        void targetIsClampedToTheLimitsTheUnitReported() {
            // A low range of 10..37 °C (as reported by e.g. a BP601) must not be cut at the fallback limit of 26 °C
            assertArrayEquals(new byte[] { 60 }, new SetTemperatureMessage(30, true, 10, 37).getPayload());
            assertArrayEquals(new byte[] { 74 }, new SetTemperatureMessage(40, true, 10, 37).getPayload());
            assertArrayEquals(new byte[] { 20 }, new SetTemperatureMessage(5, true, 10, 37).getPayload());
        }

        @Test
        void togglesAddedForPybalboaParity() {
            assertArrayEquals(new byte[] { 0x3c, 0x00 }, new ToggleMessage(ItemType.HOLD_MODE, 0).getPayload());
            assertArrayEquals(new byte[] { 0x3d, 0x00 }, new ToggleMessage(ItemType.CIRCULATION_PUMP, 0).getPayload());
            assertArrayEquals(new byte[] { 0x03, 0x00 },
                    new ToggleMessage(ItemType.CLEAR_NOTIFICATION, 0).getPayload());
        }

        @Test
        void filterCyclesCarryBothCyclesAndTheEnableFlag() {
            SetFilterCyclesMessage message = new SetFilterCyclesMessage(new byte[] { 8, 20 }, new byte[] { 0, 30 },
                    new byte[] { 2, 1 }, new byte[] { 0, 15 }, true);
            assertEquals(0x0abf23, message.getMessageType());
            assertArrayEquals(new byte[] { 8, 0, 2, 0, (byte) (0x80 | 20), 30, 1, 15 }, message.getPayload());
            assertEquals(20, new SetFilterCyclesMessage(new byte[] { 8, 20 }, new byte[] { 0, 30 }, new byte[] { 2, 1 },
                    new byte[] { 0, 15 }, false).getPayload()[4]);
        }
    }

    @Nested
    class StatusUpdate {
        // Celsius, 24h, filter state 1; priming; 37 °C current, 38 °C target; 21:05; rest; high range, heating
        // high; pumps 1,2,0,1,2,1; circulation, blower 3; lights 1,2; mister and AUX 1
        private final byte[] celsius = statusUpdate(34, 6, 0x01, 7, 74, 8, 21, 9, 5, 10, 0x01, 14, 0x07, 15, 0x24, 16,
                0x49, 17, 0x42, 18, 0x0e, 19, 0x09, 20, 0x09, 25, 76);

        @Test
        void temperaturesAndScale() {
            StatusUpdateMessage status = decode(celsius, StatusUpdateMessage.class);
            assertTrue(status.getCelciusDisplay());
            assertEquals(37.0, status.getTemperature(false));
            assertEquals(38.0, status.getTemperature(true));
        }

        @Test
        void fahrenheitIsNotScaled() {
            StatusUpdateMessage status = decode(statusUpdate(34, 7, 100, 25, 104), StatusUpdateMessage.class);
            assertFalse(status.getCelciusDisplay());
            assertEquals(100.0, status.getTemperature(false));
            assertEquals(104.0, status.getTemperature(true));
        }

        @Test
        void unknownTemperatureIsNegative() {
            // 0xFF = no reliable reading; the handler discards negative values
            StatusUpdateMessage status = decode(statusUpdate(34, 7, 0xff, 14, 0x01), StatusUpdateMessage.class);
            assertTrue(status.getTemperature(false) < 0);
        }

        @Test
        void states() {
            StatusUpdateMessage status = decode(celsius, StatusUpdateMessage.class);
            assertEquals(1, status.getReadyState());
            assertEquals(1, status.getFilterState());
            assertEquals(2, status.getHeatState());
            assertEquals(2, status.getItem(ItemType.HEATER, 0));
            assertEquals(1, status.getItem(ItemType.TEMPERATURE_RANGE, 0));
            assertEquals(1, status.getItem(ItemType.PRIMING, 0));
            assertEquals(1, status.getItem(ItemType.CIRCULATION, 0));
            assertEquals(3, status.getItem(ItemType.BLOWER, 0));
            assertEquals(1, status.getItem(ItemType.MISTER, 0));
        }

        @Test
        void pumpsLightsAndAux() {
            StatusUpdateMessage status = decode(celsius, StatusUpdateMessage.class);
            int[] pumps = { 1, 2, 0, 1, 2, 1 };
            for (int i = 0; i < pumps.length; i++) {
                assertEquals(pumps[i], status.getItem(ItemType.PUMP, i), "pump " + i);
            }
            assertEquals(1, status.getItem(ItemType.LIGHT, 0));
            assertEquals(2, status.getItem(ItemType.LIGHT, 1));
            assertEquals(1, status.getItem(ItemType.AUX, 0));
            assertEquals(0, status.getItem(ItemType.AUX, 1));
        }

        @Test
        void indexOutOfRangeReadsAsOff() {
            StatusUpdateMessage status = decode(celsius, StatusUpdateMessage.class);
            assertEquals(0, status.getItem(ItemType.PUMP, BalboaProtocol.MAX_PUMPS));
            assertEquals(0, status.getItem(ItemType.LIGHT, BalboaProtocol.MAX_LIGHTS));
            assertEquals(0, status.getItem(ItemType.AUX, BalboaProtocol.MAX_AUX));
            assertEquals(0, status.getItem(ItemType.PUMP, -1));
        }

        @Test
        void shorterMessagesOfOlderFirmwareAreDecodedWithTraceEnabled() {
            // The unknown-bits trace (TRACE is on for BalboaMessage in the tests) used to throw for any length other
            // than 34, so with TRACE logging enabled every status update of such a unit was lost
            StatusUpdateMessage status = decode(statusUpdate(30, 7, 74, 14, 0x01, 25, 76), StatusUpdateMessage.class);
            assertEquals(38.0, status.getTemperature(true));
            // ...and after a message of another length the comparison with the previous one must not fail either
            decode(celsius, StatusUpdateMessage.class);
            decode(statusUpdate(31, 7, 74, 14, 0x01), StatusUpdateMessage.class);
        }

        @Test
        void tooShortIsRejected() {
            assertNull(BalboaMessage.fromBuffer(statusUpdate(20)));
        }

        @Test
        void statusOfARealUnitWithAPendingFilterReminder() {
            // BP601G1, 30 Oct 2026: running, initialization mode "reminder", reminder "clean filter", 36 °C,
            // clock 20:59 in 24h format
            StatusUpdateMessage status = decode(
                    HexFormat.of().parseHex("7E20FFAF13000348143B00041F1C030C000002000000000120480000003C0000987E"),
                    StatusUpdateMessage.class);
            assertEquals(0x00, status.getSpaState());
            assertEquals(0, status.getItem(ItemType.PRIMING, 0));
            assertTrue(status.getNotificationPending());
            assertFalse(status.getPanelLocked());
            assertEquals(20, status.getTimeHour());
            assertEquals(59, status.getTimeMinute());
            assertTrue(status.getTime24h());
            assertEquals(0, status.getItem(ItemType.HOLD_MODE, 0));
        }

        @Test
        void aPendingReminderIsNotPriming() {
            // Initialization mode 0x03 (reminder) has bit 0 set like 0x01 (priming)
            StatusUpdateMessage status = decode(statusUpdate(34, 6, 0x03, 14, 0x01), StatusUpdateMessage.class);
            assertEquals(0, status.getItem(ItemType.PRIMING, 0));
            assertEquals(1, decode(statusUpdate(34, 6, 0x01, 14, 0x01), StatusUpdateMessage.class)
                    .getItem(ItemType.PRIMING, 0));
        }

        @Test
        void holdModeAndPanelLock() {
            StatusUpdateMessage status = decode(statusUpdate(34, 5, 0x05, 14, 0x21, 27, 0x10),
                    StatusUpdateMessage.class);
            assertEquals(0x05, status.getSpaState());
            assertEquals(1, status.getItem(ItemType.HOLD_MODE, 0));
            assertTrue(status.getPanelLocked());
            assertFalse(status.getNotificationPending());
        }
    }

    @Nested
    class Responses {
        @Test
        void panelConfiguration() {
            PanelConfigurationResponseMessage config = decode(
                    frame(PanelConfigurationResponseMessage.MESSAGE_TYPE, 0x49, 0x84, 0x81, 0x42, 0x11, 0x00),
                    PanelConfigurationResponseMessage.class);
            int[] pumps = { 1, 2, 0, 1, 1, 2 };
            for (int i = 0; i < pumps.length; i++) {
                assertEquals(pumps[i], config.getPump(i), "pump " + i);
            }
            assertEquals(1, config.getLight(0));
            assertEquals(2, config.getLight(1));
            assertEquals(2, config.getBlower());
            assertEquals(1, config.getCirculation());
            assertTrue(config.getAux(0));
            assertFalse(config.getAux(1));
            assertEquals(1, config.getMister());
            // Out of range reads as "not installed" instead of failing
            assertEquals(0, config.getPump(BalboaProtocol.MAX_PUMPS));
            assertEquals(0, config.getLight(BalboaProtocol.MAX_LIGHTS));
            assertFalse(config.getAux(BalboaProtocol.MAX_AUX));
        }

        @Test
        void filterCycles() {
            FilterCyclesResponseMessage cycles = decode(
                    frame(FilterCyclesResponseMessage.MESSAGE_TYPE, 20, 30, 2, 15, 0x80 | 8, 0, 1, 45),
                    FilterCyclesResponseMessage.class);
            assertEquals(20, cycles.getStartHour(1));
            assertEquals(30, cycles.getStartMinute(1));
            assertEquals(2, cycles.getDurationHour(1));
            assertEquals(15, cycles.getDurationMinute(1));
            assertTrue(cycles.isFilter2Enabled());
            assertEquals(8, cycles.getStartHour(2));
            assertEquals(1, cycles.getDurationHour(2));
            assertEquals(45, cycles.getDurationMinute(2));
        }

        @Test
        void filterCycle2Disabled() {
            FilterCyclesResponseMessage cycles = decode(
                    frame(FilterCyclesResponseMessage.MESSAGE_TYPE, 20, 0, 2, 0, 8, 0, 1, 0),
                    FilterCyclesResponseMessage.class);
            assertFalse(cycles.isFilter2Enabled());
            assertEquals(8, cycles.getStartHour(2));
        }

        @Test
        void faultLogValuesAreUnsigned() {
            FaultLogResponseMessage fault = decode(
                    frame(FaultLogResponseMessage.MESSAGE_TYPE, 200, 0, 0x10, 2, 13, 37, 0, 0, 0, 0),
                    FaultLogResponseMessage.class);
            assertEquals(200, fault.getFaultCount());
            assertEquals(0x10, fault.getFaultCode());
            assertEquals(2, fault.getDaysAgo());
            assertEquals(13, fault.getHour());
            assertEquals(37, fault.getMinute());
        }

        @Test
        void systemInformationOfARealUnit() {
            InformationResponseMessage info = decode(
                    HexFormat.of().parseHex("7E1A0ABF2464CE2C004250363031473120040B27D30603060000837E"),
                    InformationResponseMessage.class);
            assertEquals("BP601G1", info.getModel());
            assertEquals("M100_206 V44.0", info.getSoftwareVersion());
        }

        @Test
        void setupParametersRangeLimits() {
            SetupParametersResponseMessage setup = decode(
                    frame(SetupParametersResponseMessage.MESSAGE_TYPE, 0, 0, 50, 99, 80, 104, 0, 0x03, 0),
                    SetupParametersResponseMessage.class);
            assertEquals(10.0, setup.getLimit(false, false, true));
            assertEquals(37.0, setup.getLimit(false, true, true));
            assertEquals(26.5, setup.getLimit(true, false, true));
            assertEquals(40.0, setup.getLimit(true, true, true));
            assertEquals(99.0, setup.getLimit(false, true, false));
            assertEquals(2, setup.getPumpCount());
        }

        @Test
        void moduleIdentification() {
            int[] payload = new int[25];
            int[] mac = { 0x00, 0x15, 0x27, 0xc3, 0x29, 0xdb };
            System.arraycopy(mac, 0, payload, 3, mac.length);
            int[] idigi = { 0, 0, 0, 0, 0, 0, 0, 0, 0x00, 0x15, 0x27, 0xff, 0xff, 0xc3, 0x29, 0xdb };
            System.arraycopy(idigi, 0, payload, 9, idigi.length);
            ModuleIdentificationResponseMessage module = decode(
                    frame(ModuleIdentificationResponseMessage.MESSAGE_TYPE, payload),
                    ModuleIdentificationResponseMessage.class);
            assertEquals("00:15:27:c3:29:db", module.getMacAddress());
        }

        @Test
        void unknownTypeGivesAGenericMessage() {
            @Nullable
            BalboaMessage message = BalboaMessage.fromBuffer(frame(0x123456, 1, 2, 3));
            assertNotNull(message);
            assertEquals(BalboaMessage.class, message.getClass());
            assertEquals(0, message.getMessageType());
        }
    }
}
