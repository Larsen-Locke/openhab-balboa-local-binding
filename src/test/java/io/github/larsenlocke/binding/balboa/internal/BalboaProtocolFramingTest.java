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

import static io.github.larsenlocke.binding.balboa.internal.BalboaMessageTest.frame;
import static io.github.larsenlocke.binding.balboa.internal.BalboaMessageTest.statusUpdate;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.PanelConfigurationResponseMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.StatusUpdateMessage;

/**
 * Tests for the message framing of the {@link BalboaProtocol}: how a byte stream from the unit, arriving in arbitrary
 * chunks and possibly garbled, is cut into messages.
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
public class BalboaProtocolFramingTest {

    private final List<BalboaMessage> messages = new ArrayList<>();
    private final List<BalboaProtocol.Status> states = new ArrayList<>();
    private final BalboaProtocol protocol = new BalboaProtocol(new BalboaProtocol.Handler() {
        @Override
        public void onStateChange(BalboaProtocol.Status status, String detail) {
            states.add(status);
        }

        @Override
        public void onMessage(BalboaMessage message) {
            messages.add(message);
        }
    });

    private static byte[] status(int target) {
        return statusUpdate(34, 14, 0x01, 25, target);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private double target(int index) {
        return ((StatusUpdateMessage) messages.get(index)).getTemperature(true);
    }

    @Test
    void singleMessage() {
        protocol.receive(status(76));
        assertEquals(1, messages.size());
        assertEquals(38.0, target(0));
    }

    @Test
    void severalMessagesInOneRead() {
        protocol.receive(concat(status(70), status(72), status(74)));
        assertEquals(3, messages.size());
        assertEquals(37.0, target(2));
    }

    @Test
    void messageSplitAcrossReads() {
        byte[] message = status(76);
        protocol.receive(Arrays.copyOfRange(message, 0, 10));
        assertTrue(messages.isEmpty());
        protocol.receive(Arrays.copyOfRange(message, 10, message.length));
        assertEquals(1, messages.size());
    }

    @Test
    void strayBytesBeforeAMessageAreSkipped() {
        protocol.receive(concat(new byte[] { 0x00, 0x13, 0x42 }, status(76)));
        assertEquals(1, messages.size());
    }

    @Test
    void messageWithBadCrcIsDroppedAndTheNextOneStillArrives() {
        byte[] corrupt = status(70);
        corrupt[corrupt.length - 2] ^= 0x01;
        protocol.receive(concat(corrupt, status(76)));
        assertEquals(1, messages.size());
        assertEquals(38.0, target(0));
    }

    @Test
    void wrongLengthByteCostsOnlyAFewMessages() {
        byte[] garbled = status(70);
        garbled[1] = 0x10; // the end of this "message" is not a separator
        byte[][] good = new byte[6][];
        for (int i = 0; i < good.length; i++) {
            good[i] = status(80 + i);
        }
        protocol.receive(concat(garbled, concat(good)));
        assertTrue(messages.size() >= good.length - 1, "decoded " + messages.size());
        assertEquals(42.5, target(messages.size() - 1));
    }

    @Test
    void panelConfigurationTakesTheProtocolOnline() {
        protocol.receive(frame(PanelConfigurationResponseMessage.MESSAGE_TYPE, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00));
        assertEquals(List.of(BalboaProtocol.Status.ONLINE), states);
    }

    @Test
    void onlineRepeatedMessagesAreSuppressedUnlessBabbling() {
        protocol.receive(frame(PanelConfigurationResponseMessage.MESSAGE_TYPE, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00));
        messages.clear();

        protocol.receive(concat(status(76), status(76), status(78)));
        assertEquals(2, messages.size(), "the repeated status update is dropped");

        messages.clear();
        protocol.babble(true);
        protocol.receive(concat(status(76), status(76)));
        assertEquals(2, messages.size());
    }
}
