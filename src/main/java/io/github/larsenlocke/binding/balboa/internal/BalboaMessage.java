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

import java.util.HashMap;
import java.util.HexFormat;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BalboaMessage} represents the messages exchanged with a Balboa Control Unit.
 *
 * @author Carl Önnheim - Initial contribution
 */
@NonNullByDefault
public class BalboaMessage {

    private static final Logger LOGGER = LoggerFactory.getLogger(BalboaMessage.class);
    private static final HexFormat HEX_FORMAT = HexFormat.of().withUpperCase();

    // Each subclass will override these
    public static final int MESSAGE_TYPE = 0;
    public static final int MESSAGE_LENGTH = 0;

    // Message type map
    private static HashMap<Integer, Class<? extends BalboaMessage>> messageTypeMap = new HashMap<Integer, Class<? extends BalboaMessage>>();

    // Inititalize message type map
    static {
        messageTypeMap.put(StatusUpdateMessage.MESSAGE_TYPE, StatusUpdateMessage.class);
        messageTypeMap.put(InformationResponseMessage.MESSAGE_TYPE, InformationResponseMessage.class);
        messageTypeMap.put(PanelConfigurationResponseMessage.MESSAGE_TYPE, PanelConfigurationResponseMessage.class);
        messageTypeMap.put(FilterCyclesResponseMessage.MESSAGE_TYPE, FilterCyclesResponseMessage.class);
        messageTypeMap.put(FaultLogResponseMessage.MESSAGE_TYPE, FaultLogResponseMessage.class);
        messageTypeMap.put(SetupParametersResponseMessage.MESSAGE_TYPE, SetupParametersResponseMessage.class);
        messageTypeMap.put(ModuleIdentificationResponseMessage.MESSAGE_TYPE, ModuleIdentificationResponseMessage.class);
    }

    /**
     * The {@link ItemType} enumerates the items that can be read and potentially also toggled.
     *
     * @author Carl Önnheim
     *
     */
    public enum ItemType {
        // @formatter:off
        PUMP             ((byte) 0x04, BalboaProtocol.MAX_PUMPS),
        LIGHT            ((byte) 0x11, BalboaProtocol.MAX_LIGHTS),
        AUX              ((byte) 0x16, BalboaProtocol.MAX_AUX),
        BLOWER           ((byte) 0x0c, 1),
        MISTER           ((byte) 0x0e, 1),
        TEMPERATURE_RANGE((byte) 0x50, 1),
        HEAT_MODE        ((byte) 0x51, 1),
        HOLD_MODE        ((byte) 0x3C, 1),
        CIRCULATION_PUMP ((byte) 0x3D, 1),
        CLEAR_NOTIFICATION((byte) 0x03, 1),
        // Read only (address is zero)
        PRIMING          ((byte) 0x00, 1),
        HEATER           ((byte) 0x00, 1),
        CIRCULATION      ((byte) 0x00, 1);
        // @formatter:on

        private final byte address;
        public final int count;

        private ItemType(byte address, int count) {
            this.address = address;
            this.count = count;
        }
    }

    /*
     * Messages implement the following interfaces to identify their capabilities (inbound and/or outbound)
     *
     */

    /**
     * All messages provide a message type code
     *
     * @author Carl Önnheim
     *
     */
    private static interface Message {
        public int getMessageType();
    }

    /**
     * Messages which can be sent to the unit implement this interface.
     *
     * @author Carl Önnheim
     *
     */
    protected static interface Outbound extends Message {
        /**
         * Provides the payload of the message instance.
         *
         * @return byte buffer containing the payload
         */
        public byte[] getPayload();
    }

    /**
     * Messages which can be received from the unit implement this interface.
     *
     * @author Carl Önnheim
     *
     */
    protected static interface Inbound extends Message {
    }

    /*
     * Implementation of outbound messages
     */

    /**
     * The {@link ConfigurationRequestMessage} messages are sent to query the device for its configuration.
     *
     * @author Carl Önnheim - Initial contribution
     */
    public static class ConfigurationRequestMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf04;
        static final byte[] PAYLOAD = new byte[0];

        /**
         * Payload is empty for this message
         */
        @Override
        public byte[] getPayload() {
            return PAYLOAD;
        }
    }

    /**
     * The {@link SettingsRequestMessage} messages are sent to query the device for its settings.
     *
     * @author Carl Önnheim - Initial contribution
     */
    public static class SettingsRequestMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf22;

        /**
         * Enumerates the differnt types of settings that can be requested
         *
         * @author Carl Önnheim
         *
         */
        public enum SettingsType {
            // @formatter:off
            PANEL        (new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x01 }),
            FILTER_CYCLES(new byte[] { (byte) 0x01, (byte) 0x00, (byte) 0x00 }),
            INFORMATION  (new byte[] { (byte) 0x02, (byte) 0x00, (byte) 0x00 }),
            SETUP_PARAMETERS(new byte[] { (byte) 0x04, (byte) 0x00, (byte) 0x00 }),
            PREFERENCES  (new byte[] { (byte) 0x08, (byte) 0x00, (byte) 0x00 }),
            // When called with Fault Log, grab the last entry.
            FAULT_LOG    (new byte[] { (byte) 0x20, (byte) 0xFF, (byte) 0x00 });
            // @formatter:on

            private final byte[] payload;

            private SettingsType(byte[] payload) {
                this.payload = payload;
            }
        }

        private SettingsType setting;

        /**
         * Construct a {@link SettingsRequestMessage} for the given {@link SettingsType}.
         *
         * @param setting the {@link SettingsType} to request.
         */
        public SettingsRequestMessage(SettingsType setting) {
            this.setting = setting;
        }

        /**
         * Payload is static given the settings type
         */
        @Override
        public byte[] getPayload() {
            return setting.payload;
        }
    }

    /**
     * The {@link ToggleMessage} messages are sent to alter the state of the balboa unit.
     *
     * @author Carl Önnheim - Initial contribution
     */
    public static class ToggleMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf11;
        private ItemType type;
        private int index;

        /**
         * Construct a {@link ToggleMessage} for the given ToggleType and index.
         *
         * @param type the ToggleMessage to create a message for.
         * @param index the index to create a message for.
         */
        public ToggleMessage(ItemType type, int index) {
            // Make sure we are not trying to toggle a read only state
            if (type.address == 0x00) {
                throw new IllegalArgumentException("Attempt to toggle a read only state");
            }
            // Make sure the index does not go out of bounds
            if (index < 0 || index >= type.count) {
                throw new IllegalArgumentException("Index out of bounds");
            }
            this.type = type;
            this.index = index;
        }

        /**
         * Provide the payload. First byte is the item address + index, second byte is always zero
         */
        @Override
        public byte[] getPayload() {
            return new byte[] { (byte) (type.address + index), (byte) 0x00 };
        }
    }

    /**
     * The {@link SetTemperatureMessage} messages are sent to change the target temperature of the balboa unit.
     *
     * @author Carl Önnheim - Initial contribution
     */
    public static class SetTemperatureMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf20;
        private byte targetTemperature;

        /**
         * Construct a {@link SetTemperatureMessage} for the given temperature.
         *
         * @param targetTemperature The temperature to set
         * @param celcius Indicates if the temperature is expressed in celcius (otherwise fahrenheit). Note that this
         *            must match the current display setting on the unit.
         * @param highRange Indicates if the temperature is in the high range (otherwise low range)
         */
        public SetTemperatureMessage(double targetTemperature, boolean celcius, boolean highRange) {
            this(targetTemperature, celcius, defaultLimit(celcius, highRange, false),
                    defaultLimit(celcius, highRange, true));
        }

        /**
         * Construct a {@link SetTemperatureMessage} for the given temperature, clamped to the given limits. Used once
         * the unit has reported its actual range limits (see {@link SetupParametersResponseMessage}).
         *
         * @param targetTemperature The temperature to set
         * @param celcius Indicates if the temperature (and the limits) are expressed in celcius (otherwise
         *            fahrenheit). Note that this must match the current display setting on the unit.
         * @param low The lowest temperature the active range accepts
         * @param high The highest temperature the active range accepts
         */
        public SetTemperatureMessage(double targetTemperature, boolean celcius, double low, double high) {
            double multiplier = celcius ? 2 : 1;

            // Set the temperature
            if (targetTemperature < low) {
                this.targetTemperature = (byte) (low * multiplier);
            } else if (targetTemperature > high) {
                this.targetTemperature = (byte) (high * multiplier);
            } else {
                this.targetTemperature = (byte) (targetTemperature * multiplier);
            }
        }

        /**
         * The fallback range limits used until the unit has reported its own (see
         * {@link SetupParametersResponseMessage}).
         *
         * @param celcius whether the limit is wanted in celcius (otherwise fahrenheit)
         * @param highRange whether the limit is wanted for the high range (otherwise low range)
         * @param max whether the upper limit is wanted (otherwise the lower)
         */
        public static double defaultLimit(boolean celcius, boolean highRange, boolean max) {
            if (celcius) {
                return highRange ? (max ? 40 : 26.5) : (max ? 26 : 10);
            } else {
                return highRange ? (max ? 104 : 79) : (max ? 80 : 50);
            }
        }

        /**
         * Provide the payload. One byte with the temperature
         */
        @Override
        public byte[] getPayload() {
            return new byte[] { targetTemperature };
        }
    }

    /**
     * The {@link SetFilterCyclesMessage} messages are sent to change the filter cycle configuration of the balboa
     * unit. The unit expects both cycles in one message, so the caller passes the complete configuration (usually
     * the last one reported, with one field changed).
     *
     * The payload layout mirrors {@link FilterCyclesResponseMessage}. It is taken from community protocol
     * documentation (ccutrer/balboa_worldwide_app) and pybalboa (garbled1/pybalboa).
     *
     * @author Thomas Binder
     */
    public static class SetFilterCyclesMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf23;
        private final byte[] payload = new byte[8];

        /**
         * Construct a {@link SetFilterCyclesMessage}. Index 0 of each array is filter cycle 1, index 1 is filter
         * cycle 2.
         *
         * @param startHour start hour (0-23) of each cycle
         * @param startMinute start minute (0-59) of each cycle
         * @param durationHour duration hours of each cycle
         * @param durationMinute duration minutes of each cycle
         * @param filter2Enabled whether filter cycle 2 is enabled (cycle 1 always is)
         */
        public SetFilterCyclesMessage(byte[] startHour, byte[] startMinute, byte[] durationHour, byte[] durationMinute,
                boolean filter2Enabled) {
            payload[0] = startHour[0];
            payload[1] = startMinute[0];
            payload[2] = durationHour[0];
            payload[3] = durationMinute[0];
            payload[4] = (byte) ((startHour[1] & 0x7F) | (filter2Enabled ? 0x80 : 0x00));
            payload[5] = startMinute[1];
            payload[6] = durationHour[1];
            payload[7] = durationMinute[1];
        }

        /**
         * Provide the payload. Start hour, start minute, duration hours and duration minutes of cycle 1, then the
         * same for cycle 2 with the enable flag in bit 7 of its start hour.
         */
        @Override
        public byte[] getPayload() {
            return payload;
        }
    }

    /**
     * The {@link SetTemperatureScaleMessage} messages are sent to set the temperature scale of the balboa unit.
     *
     * @author Carl Önnheim - Initial contribution
     */
    public static class SetTemperatureScaleMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf27;
        private byte scale;

        /**
         * Construct a {@link SetTemperatureScaleMessage}.
         *
         * @param celcius Indicates if the temperature whall be expressed in celcius (otherwise fahrenheit)
         *
         */
        public SetTemperatureScaleMessage(boolean celcius) {
            scale = (byte) (celcius ? 0x01 : 0x00);
        }

        /**
         * Provide the payload. 0x01 followed by the desired scale
         */
        @Override
        public byte[] getPayload() {
            return new byte[] { (byte) 0x01, scale };
        }
    }

    /**
     * The {@link SetTimeMessage} messages are sent to set the time of the balboa unit.
     *
     * @author Carl Önnheim - Initial contribution
     */
    public static class SetTimeMessage extends BalboaMessage implements BalboaMessage.Outbound {
        public static final int MESSAGE_TYPE = 0x0abf21;
        private byte hour;
        private byte minute;

        /**
         * Construct a {@link SetTimeMessage}.
         *
         * @param hour The hour - always in 24h format
         * @param minute The minute
         * @param display24h Show time in 24h format
         *
         */
        public SetTimeMessage(int hour, int minute, boolean display24h) {
            // Store the hour
            if (hour < 0) {
                this.hour = 0;
            } else if (hour > 23) {
                this.hour = 23;
            } else {
                this.hour = (byte) hour;
            }

            // Store the minute
            if (minute < 0) {
                this.minute = 0;
            } else if (minute > 59) {
                this.minute = 59;
            } else {
                this.minute = (byte) minute;
            }

            // Set the high bit if using 24h time
            if (display24h) {
                this.hour |= 0x80;
            }
        }

        /**
         * Provide the payload. Hour followed by minute
         */
        @Override
        public byte[] getPayload() {
            return new byte[] { hour, minute };
        }
    }

    /*
     * Implementation of inbound messages. Each is expected to have a constructor taking a raw message buffer as its
     * only parameter.
     */

    /**
     * The {@link StatusUpdateMessage} messages are sent repeatedly by the control unit.
     *
     * @author Carl Önnheim - Initial contribution
     */
    public static class StatusUpdateMessage extends BalboaMessage implements BalboaMessage.Inbound {
        public static final int MESSAGE_TYPE = 0xffaf13;
        public static final int MESSAGE_LENGTH = 30;

        private byte[] pumps = new byte[BalboaProtocol.MAX_PUMPS];
        private byte[] lights = new byte[BalboaProtocol.MAX_LIGHTS];
        private byte blower;
        private boolean[] aux = new boolean[BalboaProtocol.MAX_AUX];
        private boolean celcius, time24h, temperatureHighRange, priming, mister, circulation;
        private boolean panelLocked, reminderActive, notificationActive;
        byte timeHour, timeMinute;
        double currentTemperature, targetTemperature;
        byte readyState, heatState, filterState;
        byte spaState;

        /**
         * Instantiates a {@link StatusUpdateMessage} from a raw data buffer
         *
         * @param buffer The raw data buffer to parse the message from
         */
        public StatusUpdateMessage(byte[] buffer) {
            // Marks which bits are used so changes on the unknown bits can be traced
            // @formatter:off
            final byte[] unknown = {
                    //     0                  1                  2                  3                  4                  5                  6                  7
                    (byte) 0b00000000, (byte) 0b00000000, (byte) 0b00000000, (byte) 0b00000000, (byte) 0b00000000, (byte) 0b00000000, (byte) 0b00000000, (byte) 0b00000000,
                    //     8                  9                  10                 11                 12                 13                 14                 15
                    (byte) 0b00000000, (byte) 0b00000000, (byte) 0b11111100, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11010000, (byte) 0b11001011,
                    //     16                 17                 18                 19                 20                 21                 22                 23
                    (byte) 0b00000000, (byte) 0b00111100, (byte) 0b11110001, (byte) 0b11110000, (byte) 0b11100110, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111110,
                    //     24                 25                 26                 27                 28                 29                 30                 31
                    (byte) 0b11011111, (byte) 0b00000000, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111, (byte) 0b11111111,
                    //     32,                33
                    (byte) 0b00000000, (byte) 0b00000000
                    };
            // @formatter:on

            // Determine the temperature scale first (cannot come in byte order since used on other items)
            celcius = (buffer[14] & 0x01) != 0;

            // Byte 0: start mark
            // Byte 1: length
            // Byte 2-4: message type
            // Byte 5: Spa state (0x00 running, 0x01 initializing, 0x05 hold mode, 0x14 A/B temps on, 0x17 test mode)
            spaState = buffer[5];
            // Byte 6: Initialization mode (0x00 idle, 0x01 priming, 0x02 fault, 0x03 reminder, 0x04 stage 1,
            // 0x05 stage 3, 0x42 stage 2). Priming is the value 0x01, not a bit: a pending reminder (0x03) also has
            // bit 0 set and was previously misreported as priming.
            priming = buffer[6] == 0x01;
            // Byte 7: Current Temperature
            currentTemperature = buffer[7] * (celcius ? 0.5 : 1.0);
            // Byte 8: Time Hour
            timeHour = buffer[8];
            // Byte 9: Time Minute
            timeMinute = buffer[9];
            // Byte 10: Bits 0 and 1: Ready State (0 = Ready, 1 = Rest, 3 = Ready in rest)
            readyState = (byte) (buffer[10] & 0x03);
            // Byte 11: Reminder type (0x00 none, 0x04 clean filter, 0x0A check pH, 0x09 check sanitizer, 0x1E fault),
            // not decoded
            // Byte 12: Sensor A temperature or hold timer (not decoded)
            // Byte 13: Sensor B temperature (not decoded)
            // Byte 14: Bit 0: Celcius (otherwise Fahrenheit - determined above), 1: 24h-clock, 2-3: Filter Mode,
            // 5: panel locked
            time24h = (buffer[14] & 0x02) != 0;
            filterState = (byte) ((buffer[14] >> 2) & 0x03);
            panelLocked = (buffer[14] & 0x20) != 0;
            // Byte 15: Bit 0-1: unknown, 2: high range (otherwise low range), 3: unknown,
            // 4-5: heatState (off, low, high, ??), 6-7: unknown
            temperatureHighRange = (buffer[15] & 0x04) != 0;
            heatState = (byte) ((buffer[15] >> 4) & 0x03);
            // Byte 16-17: Pump states. 2 bits per pump like so: P3P2P1P0 P5xxxxP4 (Byte16 Byte17).
            // Each encodes off,low,high,??. 1-speed pumps only off and on.
            for (int i = 0; i < BalboaProtocol.MAX_PUMPS; i++) {
                // The bit shift goes 0, 2, 4, 6, 0, 6
                int bit = (i % 4) * 2;
                if (i == 5) {
                    bit = 6;
                }
                pumps[i] = (byte) ((buffer[16 + i / 4] >> bit) & 0x03);
            }
            // Byte 18: Bit 0 unknown, Bit 1 circulation, Bits 2-3 blower (off, low, medium, high?)
            circulation = (buffer[18] & 0x02) != 0;
            blower = (byte) ((buffer[18] >> 2) & 0x03);
            // Byte 19: Lights like so xxxxL1L0. (off, low, medium, high)
            for (int i = 0; i < BalboaProtocol.MAX_LIGHTS; i++) {
                lights[i] = (byte) ((buffer[19] >> (i * 2)) & 0x03);
            }
            // Byte 20: Bit 0: mister, bit 1-2: unkown, bit 3-4: aux1/2: bit 5-7: unknown
            mister = (buffer[20] & 0x01) != 0;
            for (int i = 0; i < BalboaProtocol.MAX_AUX; i++) {
                aux[i] = (buffer[20] & (0x08 << i)) != 0;
            }
            // Byte 21: unknown
            // Byte 22: unknown
            // Byte 23: Bit 0: reminder pending
            reminderActive = (buffer[23] & 0x01) != 0;
            // Byte 24: Bit 5: notification pending
            notificationActive = (buffer[24] & 0x20) != 0;
            // Byte 25: target temperature
            targetTemperature = buffer[25] * (celcius ? 0.5 : 1.0);
            // Byte 26: unknown
            // Byte 27: Bits 4-7: Wi-Fi module state (0 OK, 1 spa not communicating, 2 startup, 3 prime, 4 hold,
            // 5 panel) - see pybalboa WiFiState, not decoded. Earlier (shorter) message versions do not carry it.
            // Byte 28: unknown -- Note that earlier message versions may be shorter (crc and end mark shifts up)
            // Byte 29: unknown -- Note that earlier message versions may be shorter (crc and end mark shifts up)
            // Byte 30: unknown -- Note that earlier message versions may be shorter (crc and end mark shifts up)
            // Byte 31: unknown -- Note that earlier message versions may be shorter (crc and end mark shifts up)
            // Byte 32: crc
            // Byte 33: end mark

            // @formatter:off
            LOGGER.trace(
                    "Status update received {}:{}"
                            + "\n"
                            + "\nPRI: {}\tCEL: {}\t24H: {}\tHGH: {}\tMIS: {}\tCIR: {}"
                            + "\nBLW: {}\t\tRST: {}\t\tFIL: {}\t\tHEA: {}"
                            + "\nCUR: {}\tTGT: {}"
                            + "\n"
                            + "\nPUM: {}"
                            + "\nLGT: {}"
                            + "\nAUX: {}"
                            + "\n",
                    timeHour, timeMinute
                    , priming, celcius, time24h, temperatureHighRange, mister, circulation
                    , blower, readyState, filterState, heatState
                    , currentTemperature, targetTemperature
                    , pumps
                    , lights
                    , aux);
            // @formatter:on

            // Trace changes on unknown bits
            displayUnknownBits(buffer, unknown);
        }

        /**
         * Returns the state of a one- or two-state togglable item.
         *
         * @param item type of the togglable.
         * @param index the index to return (PUMP, AUX and LIGHTS)
         * @return
         */
        public byte getItem(ItemType item, int index) {
            switch (item) {
                case PUMP:
                    if (index < 0 || index >= BalboaProtocol.MAX_PUMPS) {
                        return 0;
                    } else {
                        return pumps[index];
                    }
                case AUX:
                    if (index < 0 || index >= BalboaProtocol.MAX_AUX) {
                        return 0;
                    } else {
                        return (byte) (aux[index] ? 0x01 : 0x00);
                    }
                case LIGHT:
                    if (index < 0 || index >= BalboaProtocol.MAX_LIGHTS) {
                        return 0;
                    } else {
                        return lights[index];
                    }
                case BLOWER:
                    return blower;
                case MISTER:
                    return (byte) (mister ? 0x01 : 0x00);
                case TEMPERATURE_RANGE:
                    return (byte) (temperatureHighRange ? 0x01 : 0x00);
                case CIRCULATION:
                case CIRCULATION_PUMP:
                    return (byte) (circulation ? 0x01 : 0x00);
                case HEATER:
                    return heatState;
                case PRIMING:
                    return (byte) (priming ? 0x01 : 0x00);

                case HOLD_MODE:
                    return (byte) (spaState == 0x05 ? 0x01 : 0x00);

                // The meaning of these bits in the status update is not known; their state is not reported.
                case HEAT_MODE:
                    return 0;
                default:
                    return 0;
            }
        }

        /**
         * Get the heat state
         *
         * @return
         */
        public byte getHeatState() {
            return heatState;
        }

        /**
         * Get the ready state
         *
         * @return
         */
        public byte getReadyState() {
            return readyState;
        }

        /**
         * Get whether the display is set to celcius
         *
         * @return
         */
        public boolean getCelciusDisplay() {
            return celcius;
        }

        /**
         * Get the filter state
         *
         * @return
         */
        public byte getFilterState() {
            return filterState;
        }

        /**
         * Get the temperatures
         *
         * @param target Set true if the target temperature is desired.
         * @return the target temperature if called with true, otherwise the current temperature.
         */
        public double getTemperature(boolean target) {
            return target ? targetTemperature : currentTemperature;
        }

        /**
         * Get the spa state (0x00 running, 0x01 initializing, 0x05 hold mode, 0x14 A/B temps on, 0x17 test mode)
         */
        public int getSpaState() {
            return spaState & 0xFF;
        }

        /**
         * Whether a reminder or a notification is pending on the panel
         */
        public boolean getNotificationPending() {
            return reminderActive || notificationActive;
        }

        /**
         * Whether the control panel is locked
         */
        public boolean getPanelLocked() {
            return panelLocked;
        }

        /**
         * Get the hour (0-23) of the unit's clock
         */
        public int getTimeHour() {
            return timeHour & 0xFF;
        }

        /**
         * Get the minute (0-59) of the unit's clock
         */
        public int getTimeMinute() {
            return timeMinute & 0xFF;
        }

        /**
         * Whether the unit displays the time in 24h format
         */
        public boolean getTime24h() {
            return time24h;
        }
    }

    /**
     * The {@link InformationResponseMessage} messages are sent in response to a corresponding
     * {@link SettingsRequestMessage}
     * messages.
     *
     * @author Carl Önnheim - Initial contribution
     */
    public static class InformationResponseMessage extends BalboaMessage implements BalboaMessage.Inbound {
        public static final int MESSAGE_TYPE = 0x0abf24;
        public static final int MESSAGE_LENGTH = 28;

        private final String softwareVersion;
        private final String model;

        public InformationResponseMessage(byte[] buffer) {
            // Layout as decoded by pybalboa (garbled1/pybalboa, SpaClient._parse_system_information):
            // Byte 5-8: software id and version
            softwareVersion = String.format("M%d_%d V%d.%d", buffer[5] & 0xFF, buffer[6] & 0xFF, buffer[7] & 0xFF,
                    buffer[8] & 0xFF);
            // Byte 9-16: model name (ASCII, space padded)
            StringBuilder name = new StringBuilder();
            for (int i = 9; i <= 16; i++) {
                name.append((char) (buffer[i] & 0xFF));
            }
            model = name.toString().trim();
            // Byte 17: current setup, 18-21: configuration signature, 22: voltage, 23: heater type,
            // 24-25: DIP switch settings (not decoded)

            LOGGER.trace("Information Response received: model={} software={}", model, softwareVersion);
        }

        public String getSoftwareVersion() {
            return softwareVersion;
        }

        public String getModel() {
            return model;
        }
    }

    /**
     * The {@link SetupParametersResponseMessage} messages are sent in response to a {@link SettingsRequestMessage} of
     * type {@link SettingsRequestMessage.SettingsType#SETUP_PARAMETERS}. They report the temperature limits of the
     * low and high range (always in fahrenheit) and the number of pumps.
     *
     * Layout as decoded by pybalboa (garbled1/pybalboa, SpaClient._parse_setup_parameters).
     *
     * @author Thomas Binder
     */
    public static class SetupParametersResponseMessage extends BalboaMessage implements BalboaMessage.Inbound {
        public static final int MESSAGE_TYPE = 0x0abf25;
        public static final int MESSAGE_LENGTH = 16;

        private final int lowRangeMin, lowRangeMax, highRangeMin, highRangeMax;
        private final int pumpCount;

        public SetupParametersResponseMessage(byte[] buffer) {
            // Byte 5-6: unknown
            // Byte 7-8: low range minimum/maximum in fahrenheit
            lowRangeMin = buffer[7] & 0xFF;
            lowRangeMax = buffer[8] & 0xFF;
            // Byte 9-10: high range minimum/maximum in fahrenheit
            highRangeMin = buffer[9] & 0xFF;
            highRangeMax = buffer[10] & 0xFF;
            // Byte 11: unknown
            // Byte 12: pump counter (number of set bits)
            pumpCount = Integer.bitCount(buffer[12] & 0xFF);
            // Byte 13: unknown

            LOGGER.trace("Setup Parameters received: low {}-{} F, high {}-{} F, pumps {}", lowRangeMin, lowRangeMax,
                    highRangeMin, highRangeMax, pumpCount);
        }

        /**
         * Gets a range limit.
         *
         * @param highRange whether the high range is wanted (otherwise low range)
         * @param max whether the upper limit is wanted (otherwise the lower)
         * @param celcius whether the limit is wanted in celcius (rounded to 0.5 like the unit does), otherwise
         *            fahrenheit
         */
        public double getLimit(boolean highRange, boolean max, boolean celcius) {
            int fahrenheit = highRange ? (max ? highRangeMax : highRangeMin) : (max ? lowRangeMax : lowRangeMin);
            if (!celcius) {
                return fahrenheit;
            }
            return 0.5 * Math.round(((fahrenheit - 32) / 1.8) / 0.5);
        }

        public int getPumpCount() {
            return pumpCount;
        }
    }

    /**
     * The {@link ModuleIdentificationResponseMessage} messages are sent by the Wi-Fi module in response to a
     * {@link ConfigurationRequestMessage} ("device present"). They report the module's MAC address (and its iDigi
     * device id used by the Balboa cloud, which is not decoded).
     *
     * Layout as decoded by pybalboa (garbled1/pybalboa, SpaClient._parse_module_identification).
     *
     * @author Thomas Binder
     */
    public static class ModuleIdentificationResponseMessage extends BalboaMessage implements BalboaMessage.Inbound {
        public static final int MESSAGE_TYPE = 0x0abf94;
        public static final int MESSAGE_LENGTH = 32;

        private final String macAddress;

        public ModuleIdentificationResponseMessage(byte[] buffer) {
            // Byte 5-7: unknown
            // Byte 8-13: MAC address
            StringBuilder mac = new StringBuilder();
            for (int i = 8; i <= 13; i++) {
                if (mac.length() > 0) {
                    mac.append(':');
                }
                mac.append(String.format("%02x", buffer[i] & 0xFF));
            }
            macAddress = mac.toString();
            // Byte 14-29: iDigi device id (not decoded)

            LOGGER.trace("Module Identification received: mac={}", macAddress);
        }

        public String getMacAddress() {
            return macAddress;
        }
    }

    /**
     * The {@link PanelConfigurationResponseMessage} messages are sent in response to a corresponding
     * {@link SettingsRequestMessage}
     * messages.
     *
     * @author Carl Önnheim - Initial contribution
     */
    public static class PanelConfigurationResponseMessage extends BalboaMessage implements BalboaMessage.Inbound {
        public static final int MESSAGE_TYPE = 0x0abf2e;
        public static final int MESSAGE_LENGTH = 13;

        private byte[] pumps = new byte[BalboaProtocol.MAX_PUMPS];
        private byte[] lights = new byte[BalboaProtocol.MAX_LIGHTS];
        private boolean[] aux = new boolean[BalboaProtocol.MAX_AUX];
        private byte mister;
        private byte circulation;
        private byte blower;

        public PanelConfigurationResponseMessage(byte[] buffer) {
            // Determine the pump configuration
            pumps[0] = (byte) (buffer[5] & 0x03);
            pumps[1] = (byte) ((buffer[5] >> 2) & 0x03);
            pumps[2] = (byte) ((buffer[5] >> 4) & 0x03);
            pumps[3] = (byte) ((buffer[5] >> 6) & 0x03);
            pumps[4] = (byte) ((buffer[6] >> 2) & 0x03);
            pumps[5] = (byte) ((buffer[6] >> 6) & 0x03);
            LOGGER.debug("Panel Configuration: byte5..6 {} {}: Pumps {}", Integer.toBinaryString(buffer[5] & 0xFF),
                    Integer.toBinaryString(buffer[6] & 0xFF), pumps);

            // Determine the lights configuration.
            lights[0] = (byte) (buffer[7] & 0x03);
            lights[1] = (byte) ((buffer[7] >> 6) & 0x03);
            LOGGER.debug("Panel Configuration: byte7 {}: Lights {}", Integer.toBinaryString(buffer[7] & 0xFF), lights);

            // Determine circulation and blower
            blower = (byte) (buffer[8] & 0x03);
            circulation = (byte) ((buffer[8] >> 6) & 0x03);
            LOGGER.debug("Panel Configuration: byte8 {}: circulation {}, blower {}",
                    Integer.toBinaryString(buffer[8] & 0xFF), circulation, blower);

            // Determine the aux and mister configuration.
            aux[0] = (buffer[9] & 0x01) != 0;
            aux[1] = (buffer[9] & 0x02) != 0;
            mister = (byte) ((buffer[9] >> 4) & 0x03);
            LOGGER.debug("Panel Configuration: byte9 {}: AUX {}, mister {}", Integer.toBinaryString(buffer[9] & 0xFF),
                    aux, mister);
        }

        /**
         * Gets the configuration of the pump at index i
         *
         * @param i
         * @return the configuration byte
         */
        public byte getPump(int i) {
            if (i < 0 || i >= BalboaProtocol.MAX_PUMPS) {
                return 0;
            } else {
                return pumps[i];
            }
        }

        /**
         * Gets the configuration of the light at index i
         *
         * @param i
         * @return the configuration byte
         */
        public byte getLight(int i) {
            if (i < 0 || i >= BalboaProtocol.MAX_LIGHTS) {
                return 0;
            } else {
                return lights[i];
            }
        }

        /**
         * Gets the configuration of the aux at index i
         *
         * @param i
         * @return the existence of the aux item
         */
        public boolean getAux(int i) {
            if (i < 0 || i >= BalboaProtocol.MAX_AUX) {
                return false;
            } else {
                return aux[i];
            }
        }

        /**
         * Gets the blower configuration
         *
         * @return the blower configuration
         */
        public byte getBlower() {
            return blower;
        }

        /**
         * Gets the circulation configuration
         *
         * @return the circulation configuration
         */
        public byte getCirculation() {
            return circulation;
        }

        /**
         * Gets the mister configuration
         *
         * @return the mister configuration
         */
        public byte getMister() {
            return mister;
        }
    }

    /**
     * The {@link FilterCyclesResponseMessage} messages are sent in response to a {@link SettingsRequestMessage} of
     * type {@link SettingsRequestMessage.SettingsType#FILTER_CYCLES}. They report the configured start time and
     * duration of the two filter cycles, and whether filter cycle 2 is enabled at all.
     *
     * The payload layout is not documented by Balboa. It is taken from community protocol documentation
     * (ccutrer/balboa_worldwide_app), cross-checked against other open source Balboa implementations.
     *
     * @author Larsen-Locke
     */
    public static class FilterCyclesResponseMessage extends BalboaMessage implements BalboaMessage.Inbound {
        public static final int MESSAGE_TYPE = 0x0abf23;
        public static final int MESSAGE_LENGTH = 15;

        // Index 0 is filter cycle 1, index 1 is filter cycle 2 - there are never more than two.
        private byte[] startHour = new byte[2];
        private byte[] startMinute = new byte[2];
        private byte[] durationHour = new byte[2];
        private byte[] durationMinute = new byte[2];
        private boolean filter2Enabled;

        public FilterCyclesResponseMessage(byte[] buffer) {
            // Byte 5: Filter 1 start hour
            // Byte 6: Filter 1 start minute
            // Byte 7: Filter 1 duration hours
            // Byte 8: Filter 1 duration minutes
            // Byte 9: Filter 2 start hour, bit 7 (0x80) is an enable/disable flag for filter cycle 2 itself
            // Byte 10: Filter 2 start minute
            // Byte 11: Filter 2 duration hours
            // Byte 12: Filter 2 duration minutes
            // Filter cycle 1 has no enable/disable flag - it is always active.
            startHour[0] = buffer[5];
            startMinute[0] = buffer[6];
            durationHour[0] = buffer[7];
            durationMinute[0] = buffer[8];
            filter2Enabled = (buffer[9] & 0x80) != 0;
            startHour[1] = (byte) (buffer[9] & 0x7F);
            startMinute[1] = buffer[10];
            durationHour[1] = buffer[11];
            durationMinute[1] = buffer[12];

            LOGGER.trace("Filter Cycles received: 1 {}:{}+{}:{} 2 {}:{}+{}:{} (filter 2 enabled={})", startHour[0],
                    startMinute[0], durationHour[0], durationMinute[0], startHour[1], startMinute[1], durationHour[1],
                    durationMinute[1], filter2Enabled);
        }

        /**
         * Gets the start hour of the given filter cycle.
         *
         * @param cycle 1 or 2
         */
        public byte getStartHour(int cycle) {
            return startHour[cycle - 1];
        }

        /**
         * Gets the start minute of the given filter cycle.
         *
         * @param cycle 1 or 2
         */
        public byte getStartMinute(int cycle) {
            return startMinute[cycle - 1];
        }

        /**
         * Gets the duration (hours part) of the given filter cycle.
         *
         * @param cycle 1 or 2
         */
        public byte getDurationHour(int cycle) {
            return durationHour[cycle - 1];
        }

        /**
         * Gets the duration (minutes part) of the given filter cycle.
         *
         * @param cycle 1 or 2
         */
        public byte getDurationMinute(int cycle) {
            return durationMinute[cycle - 1];
        }

        /**
         * Whether filter cycle 2 is enabled. Filter cycle 1 is always enabled.
         */
        public boolean isFilter2Enabled() {
            return filter2Enabled;
        }
    }

    /**
     * The {@link FaultLogResponseMessage} messages are sent in response to a {@link SettingsRequestMessage} of type
     * {@link SettingsRequestMessage.SettingsType#FAULT_LOG}. The request always asks for entry number 0xFF, which
     * the unit interprets as "the most recent entry", so this message always reports the latest fault (if any).
     *
     * The payload layout is not documented by Balboa. It is taken from community protocol documentation
     * (ccutrer/balboa_worldwide_app), cross-checked against other open source Balboa implementations.
     *
     * @author Larsen-Locke
     */
    public static class FaultLogResponseMessage extends BalboaMessage implements BalboaMessage.Inbound {
        public static final int MESSAGE_TYPE = 0x0abf28;
        public static final int MESSAGE_LENGTH = 17;

        private byte faultCount;
        private byte faultCode;
        private byte daysAgo;
        private byte hour;
        private byte minute;

        public FaultLogResponseMessage(byte[] buffer) {
            // Byte 5: Fault count - total number of entries currently in the log (0 if none ever occurred)
            // Byte 6: Entry number of this response (always 0, the most recent entry, given how we request it)
            // Byte 7: Message/fault code
            // Byte 8: Days ago
            // Byte 9: Time hour
            // Byte 10: Time minute
            // Bytes 11-14: Flags (heat mode/temperature range), set temperature, sensor A/B temperature - not
            // currently decoded, not needed for the channels this exposes.
            faultCount = buffer[5];
            faultCode = buffer[7];
            daysAgo = buffer[8];
            hour = buffer[9];
            minute = buffer[10];

            LOGGER.trace("Fault Log received: count={} code={} daysAgo={} time={}:{}", getFaultCount(), getFaultCode(),
                    getDaysAgo(), hour, minute);
        }

        /**
         * The total number of entries currently in the fault log. Zero means no fault has ever been logged.
         */
        public int getFaultCount() {
            return faultCount & 0xFF;
        }

        /**
         * The numeric fault/message code of the most recent entry. Only meaningful if {@link #getFaultCount()} is
         * greater than zero.
         */
        public int getFaultCode() {
            return faultCode & 0xFF;
        }

        /**
         * How many days ago the most recent fault occurred (0 = today).
         */
        public int getDaysAgo() {
            return daysAgo & 0xFF;
        }

        /**
         * The hour (0-23) at which the most recent fault occurred.
         */
        public byte getHour() {
            return hour;
        }

        /**
         * The minute (0-59) at which the most recent fault occurred.
         */
        public byte getMinute() {
            return minute;
        }
    }

    /**
     * Constructs a {@link BalboaMessage}
     */
    private BalboaMessage() {
    }

    /**
     * Constructs a {@link BalboaMessage} for an unrecognized message type and payload.
     *
     * @param messageType
     * @param payload
     */
    private BalboaMessage(int messageType, byte[] payload) {
        LOGGER.trace("{}",
                String.format("Unrecognized Message type 0x%x: %s", messageType, HEX_FORMAT.formatHex(payload)));
    }

    /**
     * Get the Message Type of a {@link BalboaMessage}.
     *
     * @return The message type or zero if not available.
     */
    public int getMessageType() {
        try {
            // Get the field through introspection, thus returning the overridden values in the subclasses.
            return this.getClass().getField("MESSAGE_TYPE").getInt(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /**
     * Get the expected length of a a {@link BalboaMessage} derived class.
     *
     * @return The expected length of an encoded message
     */
    public static int getMessageLength(@Nullable Class<? extends BalboaMessage> cls) {
        if (cls == null) {
            return 0;
        }
        try {
            // Get the field through introspection, thus returning the overridden values in the subclasses.
            return cls.getField("MESSAGE_LENGTH").getInt(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /**
     * Get get a {@link BalboaMessage} instance from a buffer.
     *
     * @return A new {@link BalboaMessage} instance.
     */
    public static @Nullable BalboaMessage fromBuffer(byte[] buffer) {
        // Determine the message type (bitmasking 0xFF effectively treats the bytes as unsigned)
        int messageType = (buffer[2] & 0xFF) << 16 | (buffer[3] & 0xFF) << 8 | (buffer[4] & 0xFF);

        // Check if it is implemented
        if (messageTypeMap.containsKey(messageType)) {
            // Lookup the class which handles the message type
            Class<? extends BalboaMessage> cls = messageTypeMap.get(messageType);

            // Check that the buffer has the appropriate length
            int expectedLength = getMessageLength(cls);
            if (buffer.length < expectedLength) {
                LOGGER.debug("Buffer length {} is too short for a {}, need at least {}", buffer.length, cls.getName(),
                        expectedLength);
                return null;
            }

            try {
                // Find the constructor and call it
                return cls.getConstructor(byte[].class).newInstance(buffer);
            } catch (ReflectiveOperationException e) {
                // A constructor that fails on the payload arrives here wrapped in an InvocationTargetException
                LOGGER.debug("Failed to instantiate {}: {}", cls.getName(), String.valueOf(e.getCause()));
                return null;
            }
        } else {
            // Default to the generic message if not known
            return new BalboaMessage(messageType, buffer);
        }
    }

    /*
     * Helpers to check if data on currently unknown bits in a message changes.
     */
    private static HashMap<Integer, byte[]> lastMaskedBuffer = new HashMap<Integer, byte[]>();

    /**
     * Prints information about the message in buffer, masked to only show the unknown bits
     *
     * @param buffer
     * @param unknown
     */
    protected void displayUnknownBits(byte[] buffer, byte[] unknown) {
        // No need to prepare the data if it is not going to be traced.
        if (!LOGGER.isTraceEnabled()) {
            return;
        }

        // Older firmware sends shorter messages than the mask describes. Only compare what both cover; this is a
        // tracing aid and must never make parsing fail.
        int length = Math.min(unknown.length, buffer.length);

        // Determine the message type (bitmasking 0xFF effectively treats the bytes as unsigned)
        int messageType = (buffer[2] & 0xFF) << 16 | (buffer[3] & 0xFF) << 8 | (buffer[4] & 0xFF);

        // mask out the known data
        byte[] masked = new byte[length];
        for (int i = 0; i < length; i++) {
            masked[i] = (byte) (buffer[i] & unknown[i]);
        }

        // Check if it is a change from the last message of the same type (same length and same checksum)
        byte[] last = null;
        if (lastMaskedBuffer.containsKey(messageType)) {
            last = lastMaskedBuffer.get(messageType);
            if (last.length == masked.length) {
                int i = 0;
                for (i = 0; i < masked.length; i++) {
                    if (masked[i] != last[i]) {
                        break;
                    }
                }
                if (i >= masked.length) {
                    return;
                }
            } else {
                // Different length (e.g. firmware variants): nothing to compare bit by bit
                last = null;
            }
        }

        // Store it for use in the next pass
        lastMaskedBuffer.put(messageType, masked);

        // Print the masked buffer as a whole
        String s = String.format("Changes on unknown bits: %s\n", HEX_FORMAT.formatHex(masked));
        // A heading line
        s = s.concat("          0        1        2        3        4        5        6        7");
        // Print each byte
        String s1 = "";
        for (int i = 0; i < masked.length; i++) {
            // Break line every 8 bytes
            if (i % 8 == 0) {
                s = s.concat(s1).concat(String.format("\n%1d0 ", i / 8));
                s1 = "\n   ";
            }
            // Print the byte (blank for known positions)
            for (int b = 7; b >= 0; b--) {
                s = s.concat((unknown[i] & (0x01 << b)) == 0 ? " " : (masked[i] & (0x01 << b)) == 0 ? "0" : "1");
                s1 = s1.concat(last == null ? " " : (last[i] & (0x01 << b)) == (masked[i] & (0x01 << b)) ? " " : "^");
            }
            s = s.concat(" ");
            s1 = s1.concat(" ");
        }
        s = s.concat(s1);
        LOGGER.trace("{}", s);
    }
}
