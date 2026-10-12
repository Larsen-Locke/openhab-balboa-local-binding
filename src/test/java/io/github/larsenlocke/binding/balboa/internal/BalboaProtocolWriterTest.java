/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package io.github.larsenlocke.binding.balboa.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SettingsRequestMessage;
import io.github.larsenlocke.binding.balboa.internal.BalboaMessage.SettingsRequestMessage.SettingsType;

/**
 * Tests for the writer of the {@link BalboaProtocol}.
 *
 * A command can arrive while a (re)connect attempt is still running: the socket exists but is not connected yet.
 * Writing on it throws synchronously, so neither the completion nor the failure callback runs. The writer must not
 * stay "busy" after that, otherwise it never sends anything again, not even the configuration request on the next
 * connection - the thing then sits on ONLINE (CONFIGURATION_PENDING) forever.
 *
 * @author Carsten Mogge - Initial contribution
 */
@NonNullByDefault
public class BalboaProtocolWriterTest {

    private static final BalboaProtocol.Handler NO_OP_HANDLER = new BalboaProtocol.Handler() {
        @Override
        public void onStateChange(BalboaProtocol.Status status, String detail) {
        }

        @Override
        public void onMessage(BalboaMessage message) {
        }
    };

    @Test
    public void noExceptionEscapesWhenTheSocketIsNotConnectedYet() throws Exception {
        BalboaProtocol protocol = new BalboaProtocol(NO_OP_HANDLER);
        try (AsynchronousSocketChannel socket = AsynchronousSocketChannel.open()) {
            setSocket(protocol, socket);

            assertDoesNotThrow(() -> protocol.sendMessage(request()));
        }
    }

    @Test
    public void writerSendsAgainOnceTheSocketIsConnected() throws Exception {
        BalboaProtocol protocol = new BalboaProtocol(NO_OP_HANDLER);
        try (AsynchronousSocketChannel socket = AsynchronousSocketChannel.open();
                ServerSocketChannel server = ServerSocketChannel.open()
                        .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            setSocket(protocol, socket);

            // Whether this call throws is covered by the test above, here only the state it leaves behind matters.
            sendIgnoringFailure(protocol);

            socket.connect(server.getLocalAddress()).get(5, TimeUnit.SECONDS);
            protocol.sendMessage(request());

            try (SocketChannel peer = server.accept()) {
                peer.socket().setSoTimeout(5000);
                int received = peer.socket().getInputStream().read(new byte[64]);
                assertTrue(received > 0, "nothing was sent on the connection that was established afterwards");
            }
        }
    }

    private static SettingsRequestMessage request() {
        return new SettingsRequestMessage(SettingsType.INFORMATION);
    }

    private static void sendIgnoringFailure(BalboaProtocol protocol) {
        try {
            protocol.sendMessage(request());
        } catch (RuntimeException e) {
            // expected before the writer was fixed, see the comment at the call site
        }
    }

    private static void setSocket(BalboaProtocol protocol, AsynchronousSocketChannel socket)
            throws ReflectiveOperationException {
        Field field = BalboaProtocol.class.getDeclaredField("socket");
        field.setAccessible(true);
        field.set(protocol, socket);
    }
}
