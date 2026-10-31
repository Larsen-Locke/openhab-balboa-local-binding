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

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests that the writer's failure path and {@link BalboaProtocol#disconnect()} cannot deadlock.
 *
 * Observed in production: when the connection broke while a write was in progress, the socket reported the failure
 * directly from inside {@code socket.write()}. The writer then held its own monitor and needed the protocol's for
 * {@code disconnect()}, while the watchdog was inside {@code disconnect()}, holding the protocol's monitor and
 * needing the writer's for {@code writer.reset()}. Both threads waited forever; every later command and every
 * {@code dispose()} blocked behind them.
 *
 * The test recreates that interleaving deterministically: one thread holds the protocol's monitor (as
 * {@code disconnect()} does) while another runs the writer's failure callback. Once that callback is blocked, the
 * first thread resets the writer (as {@code disconnect()} does). With separate monitors this never returns.
 *
 * @author Thomas Binder - Initial contribution
 */
@NonNullByDefault
public class BalboaProtocolDeadlockTest {

    private static final BalboaProtocol.Handler NO_OP_HANDLER = new BalboaProtocol.Handler() {
        @Override
        public void onStateChange(BalboaProtocol.Status status, String detail) {
        }

        @Override
        public void onMessage(BalboaMessage message) {
        }
    };

    @Test
    public void failingWriteAndDisconnectDoNotDeadlock() throws Exception {
        BalboaProtocol protocol = new BalboaProtocol(NO_OP_HANDLER);
        Object writer = field(protocol, "writer");
        Method failed = writer.getClass().getMethod("failed", Throwable.class, ByteBuffer.class);
        Method reset = writer.getClass().getMethod("reset");
        failed.setAccessible(true);
        reset.setAccessible(true);

        Thread failing = new Thread(
                () -> invoke(failed, writer, new IOException("broken pipe"), ByteBuffer.allocate(1)), "failing-write");
        failing.setDaemon(true);

        Thread disconnecting = new Thread(() -> {
            synchronized (protocol) {
                failing.start();
                // Wait until the failure callback is blocked on a monitor this thread holds
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (failing.getState() != Thread.State.BLOCKED && System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                invoke(reset, writer);
            }
        }, "disconnect");
        disconnecting.setDaemon(true);

        disconnecting.start();
        disconnecting.join(TimeUnit.SECONDS.toMillis(10));
        failing.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(disconnecting.isAlive(), "disconnect path deadlocked against the writer's failure callback");
        assertFalse(failing.isAlive(), "writer's failure callback deadlocked against the disconnect path");
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(target);
        assertNotNull(value);
        return value;
    }

    private static void invoke(Method method, Object target, Object... args) {
        try {
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
