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

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.FaultLogResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.FilterCyclesResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.InformationResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.ItemType;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.ModuleIdentificationResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.PanelConfigurationResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SettingsRequestMessage.SettingsType;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SetupParametersResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.StatusUpdateMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaProtocol.Handler;
import io.github.larsenlocke.binding.balboa.internal.BalboaProtocol.Status;

/**
 * The {@link BalboaHandler} is responsible for handling Balboa things.
 *
 * @author Carl Önnheim - Initial contribution
 */
@NonNullByDefault
public class BalboaHandler extends BaseThingHandler implements Handler {

    // Logger and configuration fields
    private final Logger logger = LoggerFactory.getLogger(BalboaHandler.class);
    // The time zone configured in openHAB (regional settings), used for the unit's clock
    private final TimeZoneProvider timeZoneProvider;
    private BalboaConfiguration config = getConfigAs(BalboaConfiguration.class);

    // Instantiate a protocol with this instance as the handler (receiving callbacks)
    private BalboaProtocol protocol = new BalboaProtocol(this);
    // This manages the reconnection attempts
    private ReconnectJob reconnectJob = new ReconnectJob();
    // This is used to poll the unit for non-broadcast information and keeping the connection alive.
    private PollingJob pollingJob = new PollingJob();
    // This detects a unit that has silently disappeared (no FIN/RST, e.g. powered off) and forces a reconnect.
    private WatchdogJob watchdogJob = new WatchdogJob();
    // This forces a reconnect if the unit does not answer the configuration request, since the jobs above only run once
    // the unit is configured.
    private ConfigurationTimeoutJob configurationTimeoutJob = new ConfigurationTimeoutJob();
    // Set while this handler is disposed, so a late callback from the protocol (for example the disconnect that
    // dispose() itself triggers) does not try to update the status of a thing this handler no longer handles.
    private volatile boolean disposed = false;

    /**
     * Helper class providing a thread safe reconnection mechanism.
     *
     * @author Carl Önnheim
     *
     */
    private class ReconnectJob implements Runnable {
        // Reconnect delays grow exponentially from config.reconnectInterval, capped at this many seconds, so a
        // long-lasting outage does not keep the binding hammering the unit (or the network) forever.
        private static final long MAX_RECONNECT_DELAY_SECONDS = 600;
        // The backoff exponent is also capped, mainly to avoid overflow for unusual configurations.
        private static final int MAX_BACKOFF_SHIFT = 8;

        private @Nullable ScheduledFuture<?> job;
        private boolean enabled = false;
        private int attempt = 0;

        /**
         * Enables the reconnection mechanism
         */
        public void enable() {
            // Store the state
            enabled = true;
        }

        /**
         * Disables the reconnection mechanism
         */
        public void disable() {
            // Cancel pending reconnects if disabled
            if (job != null) {
                job.cancel(true);
                job = null;
            }

            // Store the state
            enabled = false;
        }

        /**
         * Resets the backoff so that the next reconnect attempt (if any) starts again at
         * {@code config.reconnectInterval}. Called once the connection is confirmed to be online again.
         */
        public synchronized void resetBackoff() {
            attempt = 0;
        }

        /**
         * Schedules a reconnect if enabled and not already pending. Successive attempts back off exponentially
         * from {@code config.reconnectInterval} (capped at {@link #MAX_RECONNECT_DELAY_SECONDS}), with a little
         * random jitter added so repeated failures do not keep retrying in lockstep.
         */
        public synchronized void schedule() {
            if (enabled && job == null && config.reconnectInterval > 0) {
                long delay = Math.min(config.reconnectInterval * (1L << Math.min(attempt, MAX_BACKOFF_SHIFT)),
                        MAX_RECONNECT_DELAY_SECONDS);
                delay += ThreadLocalRandom.current().nextInt(0, 3);
                attempt++;

                job = scheduler.schedule(this, delay, TimeUnit.SECONDS);
                logger.debug("Reconnection attempt {} in {} seconds", attempt, delay);
            }
        }

        /**
         * Performs the reconnect and marks the job as not pending
         */
        @Override
        public synchronized void run() {
            // Clear the job first since a new reconnect may be scheduled by the connect call
            job = null;
            logger.debug("Reconnecting...");
            connect();
        }
    }

    /**
     * Helper class providing a thread safe polling mechanism.
     *
     * @author Carl Önnheim
     *
     */
    private class PollingJob implements Runnable {
        private @Nullable ScheduledFuture<?> job;

        /**
         * Starts polling if not already active.
         */
        public synchronized void start() {
            if (job == null && config.pollingInterval > 0) {
                job = scheduler.scheduleWithFixedDelay(this, config.pollingInterval, config.pollingInterval,
                        TimeUnit.SECONDS);
                logger.debug("Polling started with {} seconds interval", config.pollingInterval);
            }
        }

        /**
         * Stops polling if active.
         */
        public synchronized void stop() {
            if (job != null) {
                job.cancel(true);
                logger.debug("Polling stopped");
                job = null;
            }
        }

        /**
         * Sends a polling packet
         */
        @Override
        public void run() {
            logger.trace("Polling the unit");
            // Send an information request
            protocol.sendMessage(new BalboaMessage.SettingsRequestMessage(SettingsType.INFORMATION));
            // Also refresh the fault log, so a newly logged fault shows up on the fault-code/fault-time channels
            // without needing a reconnect.
            protocol.sendMessage(new BalboaMessage.SettingsRequestMessage(SettingsType.FAULT_LOG));
        }
    }

    /**
     * Helper class detecting a unit that has gone silent without the socket noticing (no FIN/RST is sent if e.g. the
     * Wi-Fi module simply loses power), by periodically checking how long it has been since anything was received.
     *
     * @author Larsen-Locke
     *
     */
    private class WatchdogJob implements Runnable {
        // How often to check for staleness.
        private static final long CHECK_INTERVAL_SECONDS = 30;
        // The unit normally streams status updates several times per second while connected, so anything longer
        // than this without receiving a single byte means the connection is effectively dead.
        private static final long COMMUNICATION_TIMEOUT_MILLIS = 90_000;

        private @Nullable ScheduledFuture<?> job;

        /**
         * Starts the watchdog if not already active.
         */
        public synchronized void start() {
            if (job == null) {
                job = scheduler.scheduleWithFixedDelay(this, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
            }
        }

        /**
         * Stops the watchdog if active.
         */
        public synchronized void stop() {
            if (job != null) {
                job.cancel(true);
                job = null;
            }
        }

        /**
         * Checks how long it has been since data was last received, and forces a reconnect if it has been too long.
         */
        @Override
        public void run() {
            long silence = protocol.getMillisSinceLastActivity();
            if (silence > COMMUNICATION_TIMEOUT_MILLIS) {
                logger.warn("No data received from the Balboa unit for {} ms, assuming the connection is dead",
                        silence);
                // This triggers onStateChange(OFFLINE, ...), which in turn schedules a reconnect.
                protocol.disconnect();
            }
        }
    }

    /**
     * Helper class forcing a reconnect if the unit does not answer the configuration request in time. The polling and
     * the watchdog only run once the unit is configured, so without this a connection on which the request was lost
     * (or never sent) would stay on CONFIGURATION_PENDING forever.
     *
     * @author Larsen-Locke
     *
     */
    private class ConfigurationTimeoutJob implements Runnable {
        // The unit normally answers within a fraction of a second, this only has to be safely longer than that.
        private static final long TIMEOUT_SECONDS = 60;

        private @Nullable ScheduledFuture<?> job;

        /**
         * Starts the timeout if not already active.
         */
        public synchronized void start() {
            if (job == null) {
                job = scheduler.schedule(this, TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }

        /**
         * Stops the timeout if active.
         */
        public synchronized void stop() {
            if (job != null) {
                job.cancel(true);
                job = null;
            }
        }

        /**
         * Forces a reconnect if the unit is still waiting for its configuration.
         */
        @Override
        public void run() {
            if (getThing().getStatus() == ThingStatus.ONLINE
                    && getThing().getStatusInfo().getStatusDetail() == ThingStatusDetail.CONFIGURATION_PENDING) {
                logger.warn("No configuration received from the Balboa unit within {} seconds, reconnecting",
                        TIMEOUT_SECONDS);
                // This triggers onStateChange(OFFLINE, ...), which in turn schedules a reconnect with backoff, since
                // the backoff is only reset once the unit is configured.
                protocol.disconnect();
            }
        }
    }

    // We keep all channels in a hash map. It is easier to treat all channels the same way, since the majority are
    // dynamic. This is read from the framework's command-handling thread (handleCommand) and written/iterated
    // from the protocol's callback thread (onMessage, e.g. when the channel set is rebuilt after a reconnect) -
    // a plain HashMap is not safe for that, so this uses a ConcurrentHashMap instead.
    private class ChannelMap extends ConcurrentHashMap<ChannelUID, BalboaChannel> {
        private static final long serialVersionUID = 1L;

        // Helper method to add a channel with its UID as key
        protected void addChannel(BalboaChannel channel) {
            put(channel.getChannelUID(), channel);
        }
    }

    private ChannelMap channels = new ChannelMap();

    /*
     * Basic structure of the handler implementation is as follows
     *
     * Block A - Basic handler functions. Deals with instatiation, intitialize/dispose and handling of commands
     * Block B - Handling events from the communication protocol (status updates and messages).
     * Block C - Passing updates to/from the framework from/to the balboa unit.
     *
     * Block A begins here
     *
     */

    /**
     * Constructs a {@link BalboaHandler} for a thing.
     *
     * @param thing
     */
    public BalboaHandler(Thing thing, TimeZoneProvider timeZoneProvider) {
        super(thing);
        this.timeZoneProvider = timeZoneProvider;
    }

    /**
     * Initializes a {@link BalboaHandler}. The configuration is reread and the communication protocol with the unit is
     * connected.
     *
     */
    @Override
    public void initialize() {
        // The same handler instance is initialized again after a dispose when the thing is updated
        disposed = false;

        // Initialize the status as UNKNOWN. The protocol will update the status in callbacks.
        updateStatus(ThingStatus.UNKNOWN);

        // Allow reconnect attempts
        reconnectJob.enable();

        // Connect the protocol
        connect();
    }

    /**
     * Attempts to connect the {@link BalboaProtocol}
     *
     */
    public void connect() {
        // Reread the configuration
        config = getConfigAs(BalboaConfiguration.class);

        // Connect the protocol
        logger.debug("Starting balboa protocol with {} at {}", config.host, config.port);
        protocol.connect(config.host, config.port);
    }

    /**
     * Disposes as {@link BalboaHandler}. The communication protocol with the unit is disconnected.
     */
    @Override
    public void dispose() {
        // From here on protocol callbacks must not touch the thing status anymore
        disposed = true;

        // Stop any active polling, watchdog and configuration timeout jobs
        pollingJob.stop();
        watchdogJob.stop();
        configurationTimeoutJob.stop();

        // Disallow reconnect attempts and disconnect
        reconnectJob.disable();
        protocol.disconnect();
    }

    /**
     * Handles commands sent to the {@link BalboaHandler}. All channels are handled by inner classes implementing the
     * {@link BalboaChannel} interface. This method will look up the {@link BalboaChannel} in question and pass the
     * command along to it.
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Pass the command to the given channel. A single get() (rather than containsKey() followed by a
        // separate get()) avoids a race with the channel set being rebuilt concurrently on the protocol
        // callback thread between the two calls.
        BalboaChannel channel = channels.get(channelUID);
        if (channel != null) {
            channel.handleCommand(command);
        } else {
            logger.warn("Command received on unknown channel: {}", channelUID.getAsString());
        }
    }

    /*
     * Block B - Handling events from the communication protocol (status updates and messages).
     *
     * The BalboaProtocol manages the communication with the Balboa Unit. State Changes and Incoming Messages will be
     * passed back to the handler using these callbacks.
     */

    /**
     * Handles state changes on the protocol. The callback receives an enumerated status and a descriptive detail.
     */
    @Override
    public void onStateChange(Status status, String detail) {
        // Stop any active polling, watchdog and configuration timeout jobs before handling status transitions
        pollingJob.stop();
        watchdogJob.stop();
        configurationTimeoutJob.stop();

        // A disposed handler must not report anything to the framework, it would only log a warning
        if (disposed) {
            logger.debug("Ignoring protocol status {} ({}) after the handler was disposed", status, detail);
            return;
        }

        // Handle the status transition
        switch (status) {
            case INITIAL:
                // No action is required
                break;
            case CONFIGURATION_PENDING:
                // Report back to the framework that we are waiting for configuration of the unit.
                logger.debug("Balboa Protocol Pending Configuration: {}", detail);
                updateStatus(ThingStatus.ONLINE, ThingStatusDetail.CONFIGURATION_PENDING, detail);
                // The unit has to answer the configuration request, otherwise reconnect
                configurationTimeoutJob.start();
                break;
            case ERROR:
                // Report back to the framework that we have an error. Schedule a reconnect
                logger.debug("Balboa Protocol Error: {}", detail);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, detail);
                reconnectJob.schedule();
                break;
            case OFFLINE:
                // Schedule a reconnect (nothing will happen if reconnects are disabled)
                reconnectJob.schedule();
                // Avoid spamming the framework with a redundant, identical update on every failed reconnect
                // attempt once we are already showing OFFLINE. But do report the transition into OFFLINE
                // whether it came from ONLINE (a real disconnect) or from UNKNOWN (the very first connect
                // attempt after startup failed) - otherwise a Thing that fails to connect on the first try
                // would appear stuck on UNKNOWN forever, even while reconnect attempts keep happening.
                if (this.getThing().getStatus() != ThingStatus.OFFLINE) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, detail);
                    logger.debug("Balboa Protocol went Offline");
                } else {
                    logger.debug("Balboa Protocol disconnected");
                }
                break;
            case ONLINE:
                updateStatus(ThingStatus.ONLINE);
                logger.debug("Balboa Protocol is Online");
                // Reset the reconnect backoff now that the connection is confirmed to be working again
                reconnectJob.resetBackoff();
                // Start sending poll messages and watching for the unit going silent
                pollingJob.start();
                watchdogJob.start();
                break;
            default:
                break;
        }
    }

    /**
     * Handles messages received by the communication protocol. The callback receives the parsed message. Panel
     * Configuration messages are used to configure the Thing itself (determining what channels it actually has). Other
     * messages are passed to each channel to determine if anything is to be done with it.
     */
    @Override
    public void onMessage(BalboaMessage message) {
        logger.trace("Received a {}", message.getClass().getName());

        // Update the thing configuration (channels) on panel configuration messages
        if (message instanceof BalboaMessage.PanelConfigurationResponseMessage) {
            BalboaMessage.PanelConfigurationResponseMessage config = (PanelConfigurationResponseMessage) message;

            // This only happens once after each connect. Clear the channels we have from before and start over.
            channels.clear();

            // These channels are always there
            channels.addChannel(new TemperatureChannel("current-temperature", "Current Temperature", false));
            channels.addChannel(new TemperatureChannel("target-temperature", "Target Temperature", true));
            channels.addChannel(new TemperatureScale());
            channels.addChannel(new TemperatureRange());
            channels.addChannel(new HeatMode());
            channels.addChannel(new FilterStatus());
            channels.addChannel(new LastSeenChannel());

            // Plain Switch views of Temperature Range and Heat Mode, so a Switch item can be linked directly to
            // the channel without a second item plus a profile transforming the String channel to ON/OFF.
            channels.addChannel(new TemperatureRangeSwitch());
            channels.addChannel(new HeatModeSwitch());

            // Independent per-range desired target temperatures (see DesiredTemperatureChannel for why this
            // needs to be its own thing, rather than just reading/writing the plain target-temperature channel).
            channels.addChannel(
                    new DesiredTemperatureChannel(true, "target-temperature-high", "Target Temperature (High)"));
            channels.addChannel(
                    new DesiredTemperatureChannel(false, "target-temperature-low", "Target Temperature (Low)"));
            channels.addChannel(new ContactChannel(ItemType.PRIMING, "priming", "Priming", "priming"));
            channels.addChannel(
                    new ContactChannel(ItemType.CIRCULATION, "circulation", "Circulation Pump", "circulation"));
            channels.addChannel(new ContactChannel(ItemType.HEATER, "heater", "Heater", "heater"));

            // Fault log (most recent entry) and filter cycle configuration. These are not reported in the panel
            // configuration message, so unlike the items below they are unconditionally always present.
            channels.addChannel(new FaultCodeChannel());
            channels.addChannel(new FaultTimeChannel());
            channels.addChannel(
                    new FilterCycleTimeChannel(1, FilterCycleField.START, "filter-1-start", "Filter Cycle 1 Start"));
            channels.addChannel(new FilterCycleTimeChannel(1, FilterCycleField.DURATION, "filter-1-duration",
                    "Filter Cycle 1 Duration"));
            channels.addChannel(
                    new FilterCycleTimeChannel(2, FilterCycleField.START, "filter-2-start", "Filter Cycle 2 Start"));
            channels.addChannel(new FilterCycleTimeChannel(2, FilterCycleField.DURATION, "filter-2-duration",
                    "Filter Cycle 2 Duration"));
            channels.addChannel(new Filter2EnabledChannel());
            channels.addChannel(new Filter2EnabledSwitch());
            channels.addChannel(new DerivedChannel("fault-count", "Fault Count", "fault-count", "Number",
                    m -> m instanceof FaultLogResponseMessage f ? new DecimalType(f.getFaultCount()) : null));

            // Unit state, decoded from the status update (see pybalboa SpaState and the community protocol
            // documentation ccutrer/balboa_worldwide_app).
            channels.addChannel(new DerivedChannel("spa-state", "Spa State", "spa-state", "String",
                    m -> m instanceof StatusUpdateMessage s ? codeState(SPA_STATES, s.getSpaState()) : null));
            channels.addChannel(new DerivedChannel("notification", "Notification Pending", "notification", "Contact",
                    m -> m instanceof StatusUpdateMessage s
                            ? (s.getNotificationPending() ? OpenClosedType.OPEN : OpenClosedType.CLOSED)
                            : null));
            channels.addChannel(new ClearNotificationChannel());
            channels.addChannel(new DerivedChannel("panel-locked", "Panel Locked", "panel-locked", "Contact",
                    m -> m instanceof StatusUpdateMessage s
                            ? (s.getPanelLocked() ? OpenClosedType.OPEN : OpenClosedType.CLOSED)
                            : null));
            channels.addChannel(new DerivedChannel("heat-state", "Heat State", "heat-state", "String",
                    m -> m instanceof StatusUpdateMessage s ? codeState(HEAT_STATES, s.getHeatState()) : null));
            channels.addChannel(new OneSpeedToggle(ItemType.HOLD_MODE, 0, "hold-mode", "Hold Mode", "hold-mode"));

            // The unit's clock
            channels.addChannel(new SpaClockChannel());
            channels.addChannel(new Clock24hChannel());
            channels.addChannel(new DerivedChannel("clock-offset", "Clock Offset", "clock-offset", "Number:Time",
                    m -> m instanceof StatusUpdateMessage s ? new QuantityType<>(clockOffsetMinutes(s), Units.MINUTE)
                            : null));

            // Range limits reported by the unit (used to clamp target temperatures before sending)
            channels.addChannel(new RangeLimitChannel(false, false, "low-range-min", "Low Range Minimum"));
            channels.addChannel(new RangeLimitChannel(false, true, "low-range-max", "Low Range Maximum"));
            channels.addChannel(new RangeLimitChannel(true, false, "high-range-min", "High Range Minimum"));
            channels.addChannel(new RangeLimitChannel(true, true, "high-range-max", "High Range Maximum"));

            // System information and Wi-Fi module identification
            channels.addChannel(new DerivedChannel("model", "Model", "model", "String",
                    m -> m instanceof InformationResponseMessage i ? new StringType(i.getModel()) : null));
            channels.addChannel(new DerivedChannel("software-version", "Software Version", "software-version", "String",
                    m -> m instanceof InformationResponseMessage i ? new StringType(i.getSoftwareVersion()) : null));
            channels.addChannel(new DerivedChannel("mac-address", "MAC Address", "mac-address", "String",
                    m -> m instanceof ModuleIdentificationResponseMessage i ? new StringType(i.getMacAddress())
                            : null));
            // Circulation pump control, only if the unit has one (pybalboa toggles it with item code 0x3D)
            if (config.getCirculation() != 0) {
                channels.addChannel(new OneSpeedToggle(ItemType.CIRCULATION_PUMP, 0, "circulation-switch",
                        "Circulation (Switch)", "circulation-switch"));
            }

            // Add pumps based on the configuration message. These can be one- or two-speed (Switch or OFF/LOW/HIGH).
            // Two-speed pumps are not verified on hardware yet (no unit with one available).
            for (int i = 0; i < BalboaProtocol.MAX_PUMPS; i++) {
                switch (config.getPump(i)) {
                    // One-speed pump
                    case 0x01:
                        channels.addChannel(new OneSpeedToggle(ItemType.PUMP, i, String.format("pump-%d", i + 1),
                                String.format("Jet Pump %d, one-speed", i + 1), "pump1"));
                        break;
                    // Two-speed pump
                    case 0x02:
                        channels.addChannel(new TwoSpeedToggle(ItemType.PUMP, i, String.format("pump-%d", i + 1),
                                String.format("Jet Pump %d, two-speed", i + 1), "pump2"));
                        break;
                }
            }

            // Add lights based on the configuration message. These can be one- or two-level (Switch or OFF/LOW/HIGH).
            // Two-level lights are not verified on hardware yet (no unit with one available).
            for (int i = 0; i < BalboaProtocol.MAX_LIGHTS; i++) {
                switch (config.getLight(i)) {
                    // One-level light
                    case 0x01:
                        channels.addChannel(new OneSpeedToggle(ItemType.LIGHT, i, String.format("light-%d", i + 1),
                                String.format("Light %d, one-level", i + 1), "light1"));
                        break;
                    // Two-level light
                    case 0x02:
                        channels.addChannel(new TwoSpeedToggle(ItemType.LIGHT, i, String.format("light-%d", i + 1),
                                String.format("Light %d, two-level", i + 1), "light2"));
                        break;
                }
            }

            // Add aux items based on the configuration message. These are always one-speed.
            // AUX items are not verified on hardware yet (no unit with one available).
            for (int i = 0; i < BalboaProtocol.MAX_AUX; i++) {
                if (config.getAux(i)) {
                    channels.addChannel(new OneSpeedToggle(ItemType.AUX, i, String.format("aux-%d", i + 1),
                            String.format("AUX %d", i + 1), "aux"));
                }
            }

            // Blower. These can be one- or two-speed (Switch or OFF/LOW/HIGH).
            // Two-speed blowers are not verified on hardware yet (no unit with one available).
            switch (config.getBlower()) {
                // One-speed blower
                case 0x01:
                    channels.addChannel(
                            new OneSpeedToggle(ItemType.BLOWER, 0, "blower", "Blower, one-speed", "blower1"));
                    break;
                // Two-speed blower
                case 0x02:
                    channels.addChannel(
                            new TwoSpeedToggle(ItemType.BLOWER, 0, "blower", "Blower, two-speed", "blower2"));
                    break;
            }

            // Mister. These can be one- or two-speed (Switch or OFF/LOW/HIGH).
            // Two-speed misters are not verified on hardware yet (no unit with one available).
            switch (config.getMister()) {
                // One-speed mister
                case 0x01:
                    channels.addChannel(
                            new OneSpeedToggle(ItemType.MISTER, 0, "mister1", "Mister, one-speed", "mister1"));
                    break;
                // Two-speed mister
                case 0x02:
                    channels.addChannel(
                            new TwoSpeedToggle(ItemType.MISTER, 0, "mister2", "Mister, two-speed", "mister2"));
                    break;
            }

            // Build the channels on the thing. Start by wiping all channels existing (e.g. from a previous connect)
            ThingBuilder builder = editThing().withoutChannels(getThing().getChannels());
            // Add the channels determined above, sorted alphabetically by channel id. The backing map's iteration
            // order is hash-based, not insertion order, so without this the channel list order in the UI would be
            // effectively random.
            channels.values().stream().sorted(Comparator.comparing(channel -> channel.getChannelUID().getId()))
                    .forEach(channel -> builder.withChannel(channel.getChannel()));

            // Update the thing with the channels
            updateThing(builder.build());

            // Replay the settings responses that arrived before the channels existed (system information, setup
            // parameters, module identification are requested in the same batch as the panel configuration and
            // may well be answered first).
            for (BalboaMessage cached : lastSettings.values()) {
                for (BalboaChannel channel : channels.values()) {
                    channel.handleUpdate(cached);
                }
            }

        } else {
            // Remember the latest settings responses, see the replay above. Status updates are excluded, they are
            // broadcast continuously anyway.
            if (!(message instanceof StatusUpdateMessage)) {
                lastSettings.put(message.getClass(), message);
            }
            if (message instanceof SetupParametersResponseMessage) {
                setupParameters = (SetupParametersResponseMessage) message;
            } else if (message instanceof FilterCyclesResponseMessage) {
                filterCycles = (FilterCyclesResponseMessage) message;
            }

            // Any other message type is passed to the respective channels to determine state updates
            for (BalboaChannel channel : channels.values()) {
                channel.handleUpdate(message);
            }
        }

        // Record that a message was received, regardless of its type - this is what last-seen reports.
        BalboaChannel lastSeen = channels.get(new ChannelUID(thing.getUID(), "last-seen"));
        if (lastSeen instanceof LastSeenChannel) {
            ((LastSeenChannel) lastSeen).markSeen();
        }
    }

    /*
     * Block C - Passing updates to/from the framework from/to the balboa unit.
     *
     * This is implemented as a set of classes implementing a common interface (BalboaChannel) since many of the exposed
     * items have things in common. A hierarchy of classes in turn implement this interface as follows:
     * - BaseBalboaChannel - implements things common to all channels (building the channel and handling channel UID's)
     * |- ContactChannel - handles binary read only states received from the protocol
     * |- OneSpeedToggle - handles binary read/write states with the protocol (Switches)
     * |- TwoSpeedToggle - handles (OFF/LOW/HIGH) channels (as Strings with coded values)
     * |- Specific handlers for HeatMode, TemperatureChannel, TemperatureScale, TemperatureRange and FilterStatus
     */

    // We need to track what temperature scale and range we are currently at, in order to form update messages
    // properly. Written from the protocol callback thread (handleUpdate) and read from the framework's
    // command-handling thread (handleCommand), hence volatile.
    private volatile boolean celciusDisplay, temperatureHighRange;

    // The desired target temperature for each range, used by target-temperature-high/target-temperature-low (see
    // DesiredTemperatureChannel). The unit's "set temperature" message has no range selector of its own - it always
    // applies to whichever range is currently active - so a desired value for the range that is NOT active right
    // now cannot be sent immediately. It is cached here instead and pushed once the unit reports a switch into
    // that range (see TemperatureRange#handleUpdate). NaN means "not known yet" (nothing commanded and no status
    // update decoded for that range since startup/reconnect). Same threading rationale as above, hence volatile.
    private volatile double desiredHighTarget = Double.NaN, desiredLowTarget = Double.NaN;

    // Latest settings responses by message class, replayed to the channels when they are (re)built after a panel
    // configuration message - see onMessage.
    private final Map<Class<?>, BalboaMessage> lastSettings = new ConcurrentHashMap<>();

    // The range limits reported by the unit (null until received) - used to clamp target temperatures - and the
    // last reported filter cycle configuration (null until received) - the unit only accepts both cycles at once,
    // so a change to one field needs the others. Same threading rationale as above, hence volatile.
    private volatile @Nullable SetupParametersResponseMessage setupParameters;
    private volatile @Nullable FilterCyclesResponseMessage filterCycles;

    // The unit's clock as last reported, and when the clock was last synchronized (see SpaClockChannel).
    private volatile int spaHour = -1, spaMinute = -1;
    private volatile boolean spaTime24h = true;
    private volatile long lastClockSyncMillis = 0;

    // Codes reported by the unit and the option values the corresponding channels show for them. The labels
    // (plain text) for each option are in thing-types.xml. Unknown codes are shown as UNKNOWN_<hex code>.
    // @formatter:off
    private static final Map<Integer, String> SPA_STATES = Map.of(
            0x00, "RUNNING",
            0x01, "INITIALIZING",
            0x05, "HOLD",
            0x14, "AB_TEMPS_ON",
            0x17, "TEST");
    private static final Map<Integer, String> HEAT_STATES = Map.of(
            0, "OFF",
            1, "HEATING",
            2, "HEAT_WAITING");
    // @formatter:on

    // Minimum time between two automatic clock corrections, so a unit that does not accept the time does not get
    // a set time message on every status update.
    private static final long CLOCK_SYNC_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);

    /**
     * Maps a code reported by the unit to the option value of a String channel.
     */
    private static StringType codeState(Map<Integer, String> options, int code) {
        String option = options.get(code);
        return StringType.valueOf(option != null ? option : String.format("UNKNOWN_%02X", code));
    }

    /**
     * How many minutes the unit's clock is ahead of (positive) or behind (negative) the openHAB time, in the range
     * -720..719 (the unit has no date, so a difference of more than half a day cannot be told apart).
     */
    private int clockOffsetMinutes(StatusUpdateMessage status) {
        LocalTime now = LocalTime.now(timeZoneProvider.getTimeZone());
        int spa = status.getTimeHour() * 60 + status.getTimeMinute();
        int local = now.getHour() * 60 + now.getMinute();
        return Math.floorMod(spa - local + 720, 1440) - 720;
    }

    /**
     * Sends a target temperature for the given range, clamped to the range limits the unit reported (see
     * {@link SetupParametersResponseMessage}) or, until those are known, to the binding's fallback limits.
     */
    private void sendTargetTemperature(double value, boolean highRange) {
        SetupParametersResponseMessage limits = setupParameters;
        if (limits == null) {
            protocol.sendMessage(new BalboaMessage.SetTemperatureMessage(value, celciusDisplay, highRange));
        } else {
            double low = limits.getLimit(highRange, false, celciusDisplay);
            double high = limits.getLimit(highRange, true, celciusDisplay);
            if (value < low || value > high) {
                logger.debug("Target temperature {} is outside the {} range {}..{}, clamping", value,
                        highRange ? "high" : "low", low, high);
            }
            protocol.sendMessage(new BalboaMessage.SetTemperatureMessage(value, celciusDisplay, low, high));
        }
    }

    /**
     * Parses "HH:MM" (also "H:MM") into minutes, or returns -1 if it is not a valid time of that form.
     *
     * @param maxHour the highest allowed hour (23 for a time of day, 24 for a duration)
     */
    private static int parseHourMinute(String text, int maxHour) {
        String[] parts = text.trim().split(":");
        if (parts.length != 2) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > maxHour || minute < 0 || minute > 59
                    || (hour == maxHour && maxHour == 24 && minute > 0)) {
                return -1;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Sends a complete filter cycle configuration based on the last one reported by the unit, with the given
     * change applied, and asks the unit to report the result.
     *
     * @param cycle 1 or 2, the cycle to change (ignored if only filter2Enabled changes)
     * @param startMinutes new start time in minutes after midnight, or -1 to keep it
     * @param durationMinutes new duration in minutes, or -1 to keep it
     * @param filter2Enabled new enable state of cycle 2, or null to keep it
     */
    private void sendFilterCycles(int cycle, int startMinutes, int durationMinutes, @Nullable Boolean filter2Enabled) {
        FilterCyclesResponseMessage current = filterCycles;
        if (current == null) {
            logger.warn("Filter cycle configuration not received from the unit yet, cannot change it");
            return;
        }
        byte[] startHour = { current.getStartHour(1), current.getStartHour(2) };
        byte[] startMinute = { current.getStartMinute(1), current.getStartMinute(2) };
        byte[] durationHour = { current.getDurationHour(1), current.getDurationHour(2) };
        byte[] durationMinute = { current.getDurationMinute(1), current.getDurationMinute(2) };
        if (startMinutes >= 0) {
            startHour[cycle - 1] = (byte) (startMinutes / 60);
            startMinute[cycle - 1] = (byte) (startMinutes % 60);
        }
        if (durationMinutes >= 0) {
            durationHour[cycle - 1] = (byte) (durationMinutes / 60);
            durationMinute[cycle - 1] = (byte) (durationMinutes % 60);
        }
        boolean enabled = filter2Enabled != null ? filter2Enabled : current.isFilter2Enabled();
        protocol.sendMessage(new BalboaMessage.SetFilterCyclesMessage(startHour, startMinute, durationHour,
                durationMinute, enabled));
        protocol.sendMessage(new BalboaMessage.SettingsRequestMessage(SettingsType.FILTER_CYCLES));
    }

    /**
     * Channels exposed by the Balboa Unit are handled by classes implementing the {@link BalboaChannel} interface.
     *
     * @author Carl Önnheim
     *
     */
    private interface BalboaChannel {
        /**
         * Returns the {@link ChannelUID} of a {@link BalboaChannel}
         *
         * @return A {@link ChannelUID}
         */
        public ChannelUID getChannelUID();

        /**
         * Returns the {@link Channel} representation of a {@link BalboaChannel}
         *
         * @return A {@link Channel}
         */
        public Channel getChannel();

        /**
         * Handles commands sent to the channel
         *
         * @param command The command sent from the framework
         */
        public void handleCommand(Command command);

        /**
         * Transforms incoming messages from the Balboa Unit to status updates of the {@link Thing}
         *
         * @param message The message received from the communication protocol
         */
        public void handleUpdate(BalboaMessage message);
    }

    /**
     * Base class for channels, implementing common logic
     *
     * @author Carl Önnheim
     *
     */
    private abstract class BaseBalboaChannel implements BalboaChannel {
        private ChannelUID channelUID;
        private String description;
        private String channelType;
        private String itemType;

        /**
         * Constructs the base channel from a id, description. channel type and item type.
         *
         * @param id
         * @param description
         * @param channelType
         */
        protected BaseBalboaChannel(String id, String description, String channelType, String itemType) {
            this.channelUID = new ChannelUID(thing.getUID(), id);
            this.description = description;
            this.channelType = channelType;
            this.itemType = itemType;
        }

        /**
         * Returns the Channel UID.
         */
        @Override
        public ChannelUID getChannelUID() {
            return channelUID;
        }

        /**
         * Builds the channel.
         */
        @Override
        public Channel getChannel() {
            ChannelBuilder builder = ChannelBuilder.create(channelUID, itemType).withLabel(description)
                    .withDescription(description)
                    .withType(new ChannelTypeUID(BalboaBindingConstants.BINDING_ID, channelType));
            return builder.build();
        }
    }

    /**
     * Handles contact items
     *
     * @author Carl Önnheim
     *
     */
    private class ContactChannel extends BaseBalboaChannel {
        private ItemType balboaItemType;

        /**
         * Instantiate a contact item for the given {@link ItemType}.
         *
         */
        protected ContactChannel(ItemType balboaItemType, String id, String description, String channelType) {
            super(id, description, channelType, "Contact");
            this.balboaItemType = balboaItemType;
        }

        /**
         * Updates will have no effect. The channels are read-on so should never happen in practise
         *
         */
        @Override
        public void handleCommand(Command command) {
            if (!(command instanceof RefreshType)) {
                logger.debug("Contact channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         *
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Get the raw state of the item.
                byte rawState = ((BalboaMessage.StatusUpdateMessage) message).getItem(balboaItemType, 0);
                // Make the update
                updateState(getChannelUID(), rawState == 0 ? OpenClosedType.CLOSED : OpenClosedType.OPEN);
            }
        }
    }

    /**
     * Handles One-Speed toggle items
     *
     * @author Carl Önnheim
     *
     */
    private class OneSpeedToggle extends BaseBalboaChannel {
        private int index;
        private ItemType balboaItemType;
        private OnOffType state = OnOffType.OFF;

        /**
         * Instantiate a one-speed toggle item.
         *
         * @param index
         */
        protected OneSpeedToggle(ItemType balboaItemType, int index, String id, String description,
                String channelType) {
            super(id, description, channelType, "Switch");

            // Sanity check the index
            if (index < 0 || index >= balboaItemType.count) {
                throw new IllegalArgumentException("Index out of bounds");
            }

            this.balboaItemType = balboaItemType;
            this.index = index;
        }

        /**
         * Set the item to the desired state (ON/OFF)
         */
        @Override
        public void handleCommand(Command command) {
            // Send a toggle if the desired state is not equal to the current state
            if (command instanceof OnOffType) {
                if (command != state) {
                    protocol.sendMessage(new BalboaMessage.ToggleMessage(balboaItemType, index));
                }
            } else if (!(command instanceof RefreshType)) {
                logger.debug("One-speed channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Get the raw state of the item and determine the OH state.
                byte rawState = ((BalboaMessage.StatusUpdateMessage) message).getItem(balboaItemType, index);
                state = rawState == 0 ? OnOffType.OFF : OnOffType.ON;
                // Make the update
                updateState(getChannelUID(), state);
            }
        }
    }

    /**
     * Handles Two-Speed toggle items
     *
     * @author Carl Önnheim
     *
     */
    private class TwoSpeedToggle extends BaseBalboaChannel {
        private int index;
        private ItemType balboaItemType;
        private byte rawState;

        /**
         * Instantiate a two-speed toggle item.
         *
         * @param index
         */
        protected TwoSpeedToggle(ItemType balboaItemType, int index, String id, String description,
                String channelType) {
            super(id, description, channelType, "String");

            // Sanity check the index
            if (index < 0 || index >= balboaItemType.count) {
                throw new IllegalArgumentException("Index out of bounds");
            }

            this.balboaItemType = balboaItemType;
            this.index = index;
        }

        /**
         * Set the item to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            if (command instanceof StringType) {
                // Determine how many times to switch (state wraps around 0 -> 1 -> 2 -> 0)
                int count = 0;
                switch (command.toString()) {
                    case "OFF":
                        count = Math.floorMod(0 - rawState, 3);
                        break;
                    case "LOW":
                        count = Math.floorMod(1 - rawState, 3);
                        break;
                    case "HIGH":
                        count = Math.floorMod(2 - rawState, 3);
                        break;
                    default:
                        // Unknown target state, do nothing
                        count = 0;
                        break;
                }
                // Toggle that many times
                for (int i = 0; i < count; i++) {
                    protocol.sendMessage(new BalboaMessage.ToggleMessage(balboaItemType, index));
                }
            } else if (!(command instanceof RefreshType)) {
                logger.debug("Two-speed channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Get the raw state of the item and determine the OH state.
                rawState = ((BalboaMessage.StatusUpdateMessage) message).getItem(balboaItemType, index);
                StringType state = StringType.EMPTY;
                switch (rawState) {
                    case 0x00:
                        state = StringType.valueOf("OFF");
                        break;
                    case 0x01:
                        state = StringType.valueOf("LOW");
                        break;
                    case 0x02:
                        state = StringType.valueOf("HIGH");
                        break;
                }
                // Make the update
                updateState(getChannelUID(), state);
            }
        }
    }

    /**
     * Handles the Heat Mode
     *
     * @author Carl Önnheim
     *
     */
    private class HeatMode extends BaseBalboaChannel {
        private byte rawState;

        /**
         * Instantiate a heat mode item.
         *
         * @param index
         */
        protected HeatMode() {
            super("heat-mode", "Heat Mode", "heat-mode", "String");
        }

        /**
         * Set the item to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            // Send a toggle if the desired state is not equal to the current state
            if (command instanceof StringType) {
                // Switch based on where the user wants to go
                switch (command.toString()) {
                    case "READY":
                        // We need to toggle if the first bit is set (REST or READY_IN_REST). From READY_IN_REST a
                        // single toggle only goes to REST, it takes a second one to reach READY (as pybalboa does).
                        if ((rawState & 0x01) != 0) {
                            protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.HEAT_MODE, 0));
                            if (rawState == 0x03) {
                                protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.HEAT_MODE, 0));
                            }
                        }
                        break;
                    case "REST":
                    case "READY_IN_REST":
                        // We need to toggle if the first bit is not set (READY)
                        if ((rawState & 0x01) == 0) {
                            protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.HEAT_MODE, 0));
                        }
                        break;
                }
            } else if (!(command instanceof RefreshType)) {
                logger.debug("Heat Mode channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Get the raw state of the item and determine the OH state.
                rawState = ((BalboaMessage.StatusUpdateMessage) message).getReadyState();
                StringType state = StringType.EMPTY;
                switch (rawState) {
                    case 0x00:
                        state = StringType.valueOf("READY");
                        break;
                    case 0x01:
                        state = StringType.valueOf("REST");
                        break;
                    case 0x03:
                        state = StringType.valueOf("READY_IN_REST");
                        break;
                }
                // Make the update
                updateState(getChannelUID(), state);
            }
        }
    }

    /**
     * Handles the Temperature Scale
     *
     * @author Carl Önnheim
     *
     */
    private class TemperatureScale extends BaseBalboaChannel {

        /**
         * Instantiate a temperature scale item.
         *
         * @param index
         */
        protected TemperatureScale() {
            super("temperature-scale", "Temperature Scale", "temperature-scale", "String");
        }

        /**
         * Set the item to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            // Set the desired temperature scale
            if (command instanceof StringType) {
                switch (command.toString()) {
                    case "C":
                        protocol.sendMessage(new BalboaMessage.SetTemperatureScaleMessage(true));
                        break;
                    case "F":
                        protocol.sendMessage(new BalboaMessage.SetTemperatureScaleMessage(false));
                        break;
                }
            } else if (!(command instanceof RefreshType)) {
                logger.debug("Temperature Scale channel received update of type {}",
                        command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Remember the state at handler level, since it is needed when setting the target temperature.
                celciusDisplay = ((BalboaMessage.StatusUpdateMessage) message).getCelciusDisplay();
                // Make the update
                updateState(getChannelUID(), celciusDisplay ? StringType.valueOf("C") : StringType.valueOf("F"));
            }
        }
    }

    /**
     * Handles the Temperature Range
     *
     * @author Carl Önnheim
     *
     */
    private class TemperatureRange extends BaseBalboaChannel {

        /**
         * Instantiate a temperature range item.
         *
         * @param index
         */
        protected TemperatureRange() {
            super("temperature-range", "Temperature Range", "temperature-range", "String");
        }

        /**
         * Set the temperature range to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            // Set the desired temperature range
            if (command instanceof StringType) {
                // Toggle if we are not already at the desired state
                switch (command.toString()) {
                    case "LOW":
                        if (temperatureHighRange) {
                            protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.TEMPERATURE_RANGE, 0));
                        }
                        break;
                    case "HIGH":
                        if (!temperatureHighRange) {
                            protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.TEMPERATURE_RANGE, 0));
                        }
                        break;
                }
            } else if (!(command instanceof RefreshType)) {
                logger.debug("Temperature Range channel received update of type {}",
                        command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                BalboaMessage.StatusUpdateMessage status = (BalboaMessage.StatusUpdateMessage) message;
                boolean newHighRange = status.getItem(ItemType.TEMPERATURE_RANGE, 0) != 0x00;
                boolean rangeChanged = newHighRange != temperatureHighRange;
                // Remember the state at handler level, since it is needed when setting the target temperature.
                temperatureHighRange = newHighRange;
                // Make the update
                updateState(getChannelUID(),
                        temperatureHighRange ? StringType.valueOf("HIGH") : StringType.valueOf("LOW"));

                // We just switched range. If a desired value was remembered for the range we switched into (see
                // DesiredTemperatureChannel) and it does not match what the unit now reports for it - e.g. it was
                // last set to something else in a previous session - push it once so the range behaves like an
                // independent setpoint instead of whatever the unit happened to remember.
                if (rangeChanged) {
                    double desired = temperatureHighRange ? desiredHighTarget : desiredLowTarget;
                    double rawState = status.getTemperature(true);
                    if (!Double.isNaN(desired) && rawState >= 0 && Math.abs(desired - rawState) > 0.4) {
                        sendTargetTemperature(desired, temperatureHighRange);
                    }
                }
            }
        }
    }

    /**
     * Handles the Temperatures (current temperature and target temperature)
     *
     * @author Carl Önnheim
     *
     */
    private class TemperatureChannel extends BaseBalboaChannel {

        private boolean isTarget;

        /**
         * Instantiate a Temperature item.
         *
         */
        protected TemperatureChannel(String id, String description, boolean isTarget) {
            super(id, description, isTarget ? "target-temperature" : "current-temperature", "Number:Temperature");
            this.isTarget = isTarget;
        }

        /**
         * Sets the target temperature (current temperature is read only)
         */
        @Override
        public void handleCommand(Command command) {
            // Only Quantity Type commands are allowed and only the target temperature is writable
            if (command instanceof QuantityType<?> && isTarget) {
                // Convert to the temperature unit used on the balboa unit
                QuantityType<?> target = (QuantityType<?>) command;
                if (celciusDisplay) {
                    target = target.toUnit(SIUnits.CELSIUS);
                } else {
                    target = target.toUnit(ImperialUnits.FAHRENHEIT);
                }
                // Set the target temperature if the conversion was successful
                if (target != null) {
                    sendTargetTemperature(target.doubleValue(), temperatureHighRange);
                }
            } else if (!(command instanceof RefreshType)) {
                logger.debug("{} received update of type {}", this.getChannelUID().getId(),
                        command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Get the raw state
                double rawState = ((BalboaMessage.StatusUpdateMessage) message).getTemperature(isTarget);
                // The unit reports negative numbers if the temperature measurement is unreliable (0xFF which casts to
                // -1.0 double). We discard these altogether.
                if (rawState < 0) {
                    return;
                }
                // Set the proper unit of the value. Get the scale from the message, not the handlers state, since
                // TemperatureScale handler is not necessarily called first.
                QuantityType<Temperature> state;
                if (((BalboaMessage.StatusUpdateMessage) message).getCelciusDisplay()) {
                    state = new QuantityType<Temperature>(rawState, SIUnits.CELSIUS);
                } else {
                    state = new QuantityType<Temperature>(rawState, ImperialUnits.FAHRENHEIT);
                }
                // Make the update
                updateState(getChannelUID(), state);
            }
        }
    }

    /**
     * Handles the Filter Status
     *
     * @author Carl Önnheim
     *
     */
    private class FilterStatus extends BaseBalboaChannel {

        /**
         * Instantiate a Filter status item.
         *
         */
        protected FilterStatus() {
            super("filter", "Filter Status", "filter", "String");
        }

        /**
         * The channel is read only, no action will be taken.
         */
        @Override
        public void handleCommand(Command command) {
            if (!(command instanceof RefreshType)) {
                logger.debug("Filter Status channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            // Only status update messages are of interest
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                // Determine state
                StringType state = StringType.EMPTY;
                switch (((BalboaMessage.StatusUpdateMessage) message).getFilterState()) {
                    case 0x01:
                        state = StringType.valueOf("1");
                        break;
                    case 0x02:
                        state = StringType.valueOf("2");
                        break;
                    case 0x03:
                        state = StringType.valueOf("1+2");
                        break;
                    default:
                        state = StringType.valueOf("OFF");
                        break;
                }
                // Make the update
                updateState(getChannelUID(), state);
            }
        }
    }

    /**
     * Handles the fault code channel, showing the most recent entry from the Balboa unit's fault log (a numeric
     * code, e.g. "sensor A fault" or "low flow"), or "NONE" if the log is empty.
     *
     * @author Larsen-Locke
     */
    private class FaultCodeChannel extends BaseBalboaChannel {
        // Maps the numeric fault/message codes used in the fault log to a short, readable description. Codes not
        // in this map (Balboa does not publish the full list) are shown as "Fault <code>" instead.
        // Note: not static - inner classes may not declare static fields other than compile-time constants, and a
        // Map is not one; this is cheap to build once per (singleton) channel instance.
        // @formatter:off
        private final Map<Integer, String> faultCodes = Map.ofEntries(
                Map.entry(15, "Sensors out of sync"),
                Map.entry(16, "Low flow"),
                Map.entry(17, "Flow failed"),
                Map.entry(18, "Settings reset"),
                Map.entry(19, "Priming mode"),
                Map.entry(20, "Clock failed"),
                Map.entry(21, "Settings reset"),
                Map.entry(22, "Memory failure"),
                Map.entry(26, "Sensor sync (service)"),
                Map.entry(27, "Heater dry"),
                Map.entry(28, "Heater may be dry"),
                Map.entry(29, "Water too hot"),
                Map.entry(30, "Heater too hot"),
                Map.entry(31, "Sensor A fault"),
                Map.entry(32, "Sensor B fault"),
                Map.entry(34, "Pump stuck on"),
                Map.entry(35, "Hot fault"),
                Map.entry(36, "GFCI test failed"),
                Map.entry(37, "Standby mode"));
        // @formatter:on

        protected FaultCodeChannel() {
            super("fault-code", "Last Fault", "fault-code", "String");
        }

        /**
         * The channel is read only, no action will be taken.
         */
        @Override
        public void handleCommand(Command command) {
            if (!(command instanceof RefreshType)) {
                logger.debug("Fault code channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from fault log messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof BalboaMessage.FaultLogResponseMessage) {
                BalboaMessage.FaultLogResponseMessage fault = (BalboaMessage.FaultLogResponseMessage) message;
                if (fault.getFaultCount() == 0) {
                    updateState(getChannelUID(), StringType.valueOf("NONE"));
                } else {
                    int code = fault.getFaultCode();
                    updateState(getChannelUID(), StringType.valueOf(faultCodes.getOrDefault(code, "Fault " + code)));
                }
            }
        }
    }

    /**
     * Handles the fault time channel, showing when the most recent fault log entry occurred (best effort - the
     * unit only reports "days ago" plus a time of day, not an exact date), or "-" if the log is empty.
     *
     * @author Larsen-Locke
     */
    private class FaultTimeChannel extends BaseBalboaChannel {
        protected FaultTimeChannel() {
            super("fault-time", "Last Fault Time", "fault-time", "String");
        }

        /**
         * The channel is read only, no action will be taken.
         */
        @Override
        public void handleCommand(Command command) {
            if (!(command instanceof RefreshType)) {
                logger.debug("Fault time channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from fault log messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof BalboaMessage.FaultLogResponseMessage) {
                BalboaMessage.FaultLogResponseMessage fault = (BalboaMessage.FaultLogResponseMessage) message;
                if (fault.getFaultCount() == 0) {
                    updateState(getChannelUID(), StringType.valueOf("-"));
                } else {
                    int daysAgo = fault.getDaysAgo();
                    String when = daysAgo == 0 ? "today" : daysAgo == 1 ? "1 day ago" : daysAgo + " days ago";
                    updateState(getChannelUID(), StringType
                            .valueOf(String.format("%s at %02d:%02d", when, fault.getHour(), fault.getMinute())));
                }
            }
        }
    }

    /**
     * Reports the timestamp of the most recently received message from the unit, regardless of its type. Updated
     * centrally in {@link #onMessage(BalboaMessage)} rather than in {@link #handleUpdate(BalboaMessage)}, since it
     * does not depend on the message content. Useful to detect a stale connection independently of the binding's
     * own watchdog, e.g. via the Expire binding/profile.
     *
     * @author Larsen-Locke
     */
    private class LastSeenChannel extends BaseBalboaChannel {
        // Minimum time between two state updates. The unit can report a new message several times a minute while
        // active (see BalboaProtocol#babble()), but last-seen only needs to be accurate enough for staleness
        // detection (e.g. via the Expire binding) - updating on every single message would just be event/persistence
        // noise for no benefit.
        private static final long MIN_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(30);

        private volatile long lastMarkedMillis = 0;

        protected LastSeenChannel() {
            super("last-seen", "Last Seen", "last-seen", "DateTime");
        }

        /**
         * The channel is read only, no action will be taken.
         */
        @Override
        public void handleCommand(Command command) {
            if (!(command instanceof RefreshType)) {
                logger.debug("Last seen channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Not tied to any particular message type, see markSeen().
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
        }

        /**
         * Records that a message was just received from the unit. Throttled to MIN_INTERVAL_MILLIS - see its
         * Javadoc.
         */
        protected void markSeen() {
            long now = System.currentTimeMillis();
            if (now - lastMarkedMillis >= MIN_INTERVAL_MILLIS) {
                lastMarkedMillis = now;
                updateState(getChannelUID(), new DateTimeType());
            }
        }
    }

    /**
     * Selects which field of a filter cycle a {@link FilterCycleTimeChannel} reports.
     *
     * @author Larsen-Locke
     */
    private enum FilterCycleField {
        START,
        DURATION
    }

    /**
     * Handles the filter cycle start/duration channels (four of these are instantiated: start and duration, for
     * filter cycles 1 and 2).
     *
     * @author Larsen-Locke
     */
    private class FilterCycleTimeChannel extends BaseBalboaChannel {
        private final int cycle;
        private final FilterCycleField field;

        /**
         * Instantiate a filter cycle time channel.
         *
         * @param cycle 1 or 2
         * @param field whether this channel reports the start time or the duration
         */
        protected FilterCycleTimeChannel(int cycle, FilterCycleField field, String id, String description) {
            super(id, description, "filter-cycle-time", "String");
            this.cycle = cycle;
            this.field = field;
        }

        /**
         * Changes the start time or duration of the filter cycle ("HH:MM"). Durations must be between 00:15 and
         * 24:00 (as pybalboa enforces).
         */
        @Override
        public void handleCommand(Command command) {
            if (command instanceof StringType) {
                int minutes = parseHourMinute(command.toString(), field == FilterCycleField.START ? 23 : 24);
                if (minutes < 0 || (field == FilterCycleField.DURATION && minutes < 15)) {
                    logger.warn("Invalid {} for {}: '{}' (expected HH:MM{})",
                            field == FilterCycleField.START ? "start time" : "duration", getChannelUID().getId(),
                            command, field == FilterCycleField.DURATION ? " between 00:15 and 24:00" : "");
                    return;
                }
                if (field == FilterCycleField.START) {
                    sendFilterCycles(cycle, minutes, -1, null);
                } else {
                    sendFilterCycles(cycle, -1, minutes, null);
                }
            } else if (!(command instanceof RefreshType)) {
                logger.debug("Filter cycle channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from filter cycle messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof BalboaMessage.FilterCyclesResponseMessage) {
                BalboaMessage.FilterCyclesResponseMessage cycles = (BalboaMessage.FilterCyclesResponseMessage) message;
                byte hour = field == FilterCycleField.START ? cycles.getStartHour(cycle)
                        : cycles.getDurationHour(cycle);
                byte minute = field == FilterCycleField.START ? cycles.getStartMinute(cycle)
                        : cycles.getDurationMinute(cycle);
                updateState(getChannelUID(), StringType.valueOf(String.format("%02d:%02d", hour, minute)));
            }
        }
    }

    /**
     * Handles the filter cycle 2 enabled channel. Filter cycle 1 has no such flag - it is always active.
     *
     * @author Larsen-Locke
     */
    private class Filter2EnabledChannel extends BaseBalboaChannel {
        protected Filter2EnabledChannel() {
            super("filter-2-enabled", "Filter 2 Enabled", "filter2-enabled", "Contact");
        }

        /**
         * The channel is read only, no action will be taken.
         */
        @Override
        public void handleCommand(Command command) {
            if (!(command instanceof RefreshType)) {
                logger.debug("Filter cycle 2 enabled channel received update of type {}",
                        command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from filter cycle messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof BalboaMessage.FilterCyclesResponseMessage) {
                BalboaMessage.FilterCyclesResponseMessage cycles = (BalboaMessage.FilterCyclesResponseMessage) message;
                updateState(getChannelUID(), cycles.isFilter2Enabled() ? OpenClosedType.OPEN : OpenClosedType.CLOSED);
            }
        }
    }

    /**
     * Plain Switch view of the Temperature Range (ON = HIGH, OFF = LOW). Lets a Switch item be linked directly to
     * this channel, instead of needing a second item bound to the String-valued {@link TemperatureRange} channel
     * through a profile that maps HIGH/LOW to ON/OFF - two items on one channel like that is what was causing
     * spurious state bounces, which is also why this channel independently re-derives the active range from each
     * message instead of relying on {@link BalboaHandler#temperatureHighRange} (channel iteration order across
     * {@link ChannelMap} is not guaranteed, so that field is not necessarily fresh yet when this runs).
     *
     * @author Larsen-Locke
     */
    private class TemperatureRangeSwitch extends BaseBalboaChannel {
        private boolean highRange;

        protected TemperatureRangeSwitch() {
            super("temperature-range-switch", "Temp. Range (Switch)", "temperature-range-switch", "Switch");
        }

        /**
         * Set the temperature range to the desired state
         */
        @Override
        public void handleCommand(Command command) {
            if (command instanceof OnOffType) {
                boolean wantHigh = command == OnOffType.ON;
                if (wantHigh != highRange) {
                    protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.TEMPERATURE_RANGE, 0));
                }
            } else if (!(command instanceof RefreshType)) {
                logger.debug("Temperature Range switch channel received update of type {}",
                        command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                highRange = ((BalboaMessage.StatusUpdateMessage) message).getItem(ItemType.TEMPERATURE_RANGE,
                        0) != 0x00;
                updateState(getChannelUID(), highRange ? OnOffType.ON : OnOffType.OFF);
            }
        }
    }

    /**
     * Plain Switch view of the Heat Mode (ON = READY, OFF = REST or READY_IN_REST). Lets a Switch item be linked
     * directly to this channel instead of needing a second item bound to the String-valued {@link HeatMode} channel
     * through a profile - see {@link TemperatureRangeSwitch} for why that pattern is best avoided.
     *
     * @author Larsen-Locke
     */
    private class HeatModeSwitch extends BaseBalboaChannel {
        private byte rawState;

        protected HeatModeSwitch() {
            super("heat-mode-switch", "Heat Mode (Switch)", "heat-mode-switch", "Switch");
        }

        /**
         * Set the item to the desired state (ON = READY, OFF = REST)
         */
        @Override
        public void handleCommand(Command command) {
            if (command instanceof OnOffType) {
                boolean wantReady = command == OnOffType.ON;
                boolean isReady = (rawState & 0x01) == 0;
                if (wantReady != isReady) {
                    protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.HEAT_MODE, 0));
                    // READY_IN_REST -> READY takes two toggles, see HeatMode#handleCommand
                    if (wantReady && rawState == 0x03) {
                        protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.HEAT_MODE, 0));
                    }
                }
            } else if (!(command instanceof RefreshType)) {
                logger.debug("Heat Mode switch channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                rawState = ((BalboaMessage.StatusUpdateMessage) message).getReadyState();
                // READY (0x00) -> ON; REST (0x01) and READY_IN_REST (0x03) both have the low bit set -> OFF.
                updateState(getChannelUID(), (rawState & 0x01) == 0 ? OnOffType.ON : OnOffType.OFF);
            }
        }
    }

    /**
     * Handles a per-range desired target temperature (High or Low). The Balboa unit's "set temperature" message has
     * no range selector of its own: reading {@link BalboaMessage.SetTemperatureMessage}, the outbound payload is a
     * single byte carrying only the temperature value - the constructor's {@code highRange} parameter is used
     * purely to clamp the value to that range's valid limits before sending, it is never put on the wire. So a "set
     * temperature" command always lands on whichever range is currently active, both here and on the physical
     * control panel - this is a genuine property of the unit, not a limitation of this binding.
     * <p>
     * What the binding CAN do is remember a separate desired value per range and apply the right one automatically:
     * writing to the channel for the currently active range is sent immediately; writing to the channel for the
     * other range is only remembered (in {@link BalboaHandler#desiredHighTarget}/{@code desiredLowTarget}) and
     * pushed once the unit reports a switch into that range (see {@link TemperatureRange#handleUpdate}). This lets
     * e.g. {@code target-temperature-high} always show and accept "the temperature I want when in High range",
     * regardless of which range happens to be active right now - unlike the plain {@code target-temperature}
     * channel, which always reflects the live target of whatever range is currently active.
     *
     * @author Larsen-Locke
     */
    private class DesiredTemperatureChannel extends BaseBalboaChannel {
        private final boolean highRange;

        /**
         * Instantiate a desired temperature item.
         *
         * @param highRange whether this channel represents the High range's desired target (otherwise Low)
         */
        protected DesiredTemperatureChannel(boolean highRange, String id, String description) {
            super(id, description, "target-temperature", "Number:Temperature");
            this.highRange = highRange;
        }

        /**
         * Remembers the desired value for this range, and sends it immediately if this range is currently active.
         */
        @Override
        public void handleCommand(Command command) {
            if (command instanceof QuantityType<?>) {
                QuantityType<?> target = (QuantityType<?>) command;
                target = celciusDisplay ? target.toUnit(SIUnits.CELSIUS) : target.toUnit(ImperialUnits.FAHRENHEIT);
                if (target == null) {
                    return;
                }
                double value = target.doubleValue();
                if (highRange) {
                    desiredHighTarget = value;
                } else {
                    desiredLowTarget = value;
                }
                if (highRange == temperatureHighRange) {
                    sendTargetTemperature(value, highRange);
                }
            } else if (!(command instanceof RefreshType)) {
                logger.debug("{} received update of type {}", this.getChannelUID().getId(),
                        command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from status update messages. While this range is active, the broadcast target
         * temperature is authoritative and also becomes the new desired value (so a fresh binding start picks up
         * whatever is actually on the unit, instead of showing nothing until a value is explicitly commanded).
         * While this range is not active, the channel keeps showing the last known desired value for it.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof BalboaMessage.StatusUpdateMessage) {
                BalboaMessage.StatusUpdateMessage status = (BalboaMessage.StatusUpdateMessage) message;
                boolean active = status.getItem(ItemType.TEMPERATURE_RANGE, 0) != 0x00;
                double rawState = status.getTemperature(true);
                // The unit reports negative numbers (0xFF, casts to -1.0) if the reading is unreliable - discard.
                if (active == highRange && rawState >= 0) {
                    if (highRange) {
                        desiredHighTarget = rawState;
                    } else {
                        desiredLowTarget = rawState;
                    }
                }
                double desired = highRange ? desiredHighTarget : desiredLowTarget;
                if (!Double.isNaN(desired)) {
                    QuantityType<Temperature> state = status.getCelciusDisplay()
                            ? new QuantityType<Temperature>(desired, SIUnits.CELSIUS)
                            : new QuantityType<Temperature>(desired, ImperialUnits.FAHRENHEIT);
                    updateState(getChannelUID(), state);
                }
            }
        }
    }

    /**
     * Read only channel whose state is derived from one message type by a function. The function returns null for
     * messages it does not handle (the channel then keeps its state).
     *
     * @author Thomas Binder
     */
    private class DerivedChannel extends BaseBalboaChannel {
        private final Function<BalboaMessage, @Nullable State> derive;

        protected DerivedChannel(String id, String description, String channelType, String itemType,
                Function<BalboaMessage, @Nullable State> derive) {
            super(id, description, channelType, itemType);
            this.derive = derive;
        }

        /**
         * The channel is read only, no action will be taken.
         */
        @Override
        public void handleCommand(Command command) {
            if (!(command instanceof RefreshType)) {
                logger.debug("{} received update of type {}", getChannelUID().getId(),
                        command.getClass().getSimpleName());
            }
        }

        /**
         * Updates the channel state from the messages the function handles.
         */
        @Override
        public void handleUpdate(BalboaMessage message) {
            State state = derive.apply(message);
            if (state != null) {
                updateState(getChannelUID(), state);
            }
        }
    }

    /**
     * Clears a pending reminder or notification on the panel (toggle item code 0x03, see pybalboa ToggleItemCode
     * CLEAR_NOTIFICATION). Behaves like a push button: ON sends the request, the channel then falls back to OFF.
     * Whether something is pending is shown by the notification and reminder channels.
     *
     * @author Thomas Binder
     */
    private class ClearNotificationChannel extends BaseBalboaChannel {
        protected ClearNotificationChannel() {
            super("clear-notification", "Clear Notification", "clear-notification", "Switch");
        }

        @Override
        public void handleCommand(Command command) {
            if (command == OnOffType.ON) {
                protocol.sendMessage(new BalboaMessage.ToggleMessage(ItemType.CLEAR_NOTIFICATION, 0));
                updateState(getChannelUID(), OnOffType.OFF);
            } else if (!(command instanceof RefreshType) && command != OnOffType.OFF) {
                logger.debug("Clear notification channel received update of type {}",
                        command.getClass().getSimpleName());
            }
        }

        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof StatusUpdateMessage) {
                updateState(getChannelUID(), OnOffType.OFF);
            }
        }
    }

    /**
     * The unit's clock as "HH:MM". Accepts "HH:MM" or a DateTime to set it. With the thing configuration
     * {@code syncClock} enabled, the clock is also corrected automatically whenever it is off by more than a minute
     * (at most every {@link BalboaHandler#CLOCK_SYNC_INTERVAL_MILLIS}).
     *
     * @author Thomas Binder
     */
    private class SpaClockChannel extends BaseBalboaChannel {
        protected SpaClockChannel() {
            super("spa-time", "Spa Time", "spa-time", "String");
        }

        @Override
        public void handleCommand(Command command) {
            int minutes = -1;
            if (command instanceof DateTimeType) {
                ZonedDateTime time = ((DateTimeType) command).getZonedDateTime(timeZoneProvider.getTimeZone());
                minutes = time.getHour() * 60 + time.getMinute();
            } else if (command instanceof StringType) {
                minutes = parseHourMinute(command.toString(), 23);
            } else if (command instanceof RefreshType) {
                return;
            }
            if (minutes < 0) {
                logger.warn("Invalid time for spa-time: '{}' (expected HH:MM or a DateTime)", command);
                return;
            }
            protocol.sendMessage(new BalboaMessage.SetTimeMessage(minutes / 60, minutes % 60, spaTime24h));
        }

        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof StatusUpdateMessage status) {
                spaHour = status.getTimeHour();
                spaMinute = status.getTimeMinute();
                spaTime24h = status.getTime24h();
                updateState(getChannelUID(), StringType.valueOf(String.format("%02d:%02d", spaHour, spaMinute)));

                // The status only carries whole minutes, so a difference of one minute is normal around the minute
                // change. Anything more is corrected if enabled.
                long now = System.currentTimeMillis();
                if (config.syncClock && Math.abs(clockOffsetMinutes(status)) > 1
                        && now - lastClockSyncMillis >= CLOCK_SYNC_INTERVAL_MILLIS) {
                    lastClockSyncMillis = now;
                    LocalTime local = LocalTime.now(timeZoneProvider.getTimeZone());
                    logger.info("Spa clock is {} minutes off, setting it to {}", clockOffsetMinutes(status),
                            String.format("%02d:%02d", local.getHour(), local.getMinute()));
                    protocol.sendMessage(
                            new BalboaMessage.SetTimeMessage(local.getHour(), local.getMinute(), spaTime24h));
                }
            }
        }
    }

    /**
     * Whether the unit displays the time in 24h format (ON) or 12h format (OFF).
     *
     * @author Thomas Binder
     */
    private class Clock24hChannel extends BaseBalboaChannel {
        protected Clock24hChannel() {
            super("clock-24h", "24h Clock", "clock-24h", "Switch");
        }

        @Override
        public void handleCommand(Command command) {
            if (command instanceof OnOffType) {
                if (spaHour < 0) {
                    logger.warn("Spa time not received yet, cannot change the clock format");
                    return;
                }
                protocol.sendMessage(new BalboaMessage.SetTimeMessage(spaHour, spaMinute, command == OnOffType.ON));
            } else if (!(command instanceof RefreshType)) {
                logger.debug("24h clock channel received update of type {}", command.getClass().getSimpleName());
            }
        }

        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof StatusUpdateMessage status) {
                updateState(getChannelUID(), status.getTime24h() ? OnOffType.ON : OnOffType.OFF);
            }
        }
    }

    /**
     * A range limit reported by the unit (see {@link SetupParametersResponseMessage}), in the unit's display scale.
     *
     * @author Thomas Binder
     */
    private class RangeLimitChannel extends BaseBalboaChannel {
        private final boolean highRange, max;

        protected RangeLimitChannel(boolean highRange, boolean max, String id, String description) {
            super(id, description, "range-limit", "Number:Temperature");
            this.highRange = highRange;
            this.max = max;
        }

        /**
         * The channel is read only, no action will be taken.
         */
        @Override
        public void handleCommand(Command command) {
            if (!(command instanceof RefreshType)) {
                logger.debug("{} received update of type {}", getChannelUID().getId(),
                        command.getClass().getSimpleName());
            }
        }

        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof SetupParametersResponseMessage limits) {
                double value = limits.getLimit(highRange, max, celciusDisplay);
                updateState(getChannelUID(), celciusDisplay ? new QuantityType<Temperature>(value, SIUnits.CELSIUS)
                        : new QuantityType<Temperature>(value, ImperialUnits.FAHRENHEIT));
            }
        }
    }

    /**
     * Switch view of whether filter cycle 2 is enabled, writable (unlike the Contact channel filter-2-enabled, which
     * is kept so existing links keep working).
     *
     * @author Thomas Binder
     */
    private class Filter2EnabledSwitch extends BaseBalboaChannel {
        protected Filter2EnabledSwitch() {
            super("filter-2-enabled-switch", "Filter 2 (Switch)", "filter2-enabled-switch", "Switch");
        }

        @Override
        public void handleCommand(Command command) {
            if (command instanceof OnOffType) {
                sendFilterCycles(2, -1, -1, command == OnOffType.ON);
            } else if (!(command instanceof RefreshType)) {
                logger.debug("Filter cycle 2 enabled switch received update of type {}",
                        command.getClass().getSimpleName());
            }
        }

        @Override
        public void handleUpdate(BalboaMessage message) {
            if (message instanceof FilterCyclesResponseMessage cycles) {
                updateState(getChannelUID(), cycles.isFilter2Enabled() ? OnOffType.ON : OnOffType.OFF);
            }
        }
    }
}
