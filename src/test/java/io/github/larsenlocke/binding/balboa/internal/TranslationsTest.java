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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Keeps the translation files in step with each other, with the code and with the XML resources. Nothing at build
 * time catches a missing key otherwise; the UI just shows the raw key - or, since openHAB prefers the properties
 * file over the inline XML text, an outdated label.
 *
 * The English file is generated from the XML with openHAB's i18n tool:
 * {@code mvn org.openhab.core.tools:i18n-maven-plugin:5.2.1:generate-default-translations}
 *
 * @author Larsen-Locke - Initial contribution
 */
@NonNullByDefault
class TranslationsTest {

    private static final String BASE = "balboa";
    // Keys appear as @text/... or as the first argument of BalboaProtocol.text(...)
    private static final Pattern TEXT_KEY = Pattern.compile("(?:@text/|text\\(\")([a-z0-9.-]+)");
    private static final Pattern XML_TEXT = Pattern.compile("<(label|description)>(.*?)</\\1>", Pattern.DOTALL);

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Objects
                .requireNonNull(TranslationsTest.class.getResourceAsStream("/OH-INF/i18n/" + name))) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static Set<String> matches(Path root, String suffix, Pattern pattern, int group) throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(suffix)).toList()) {
                Matcher matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) {
                    found.add(matcher.group(group));
                }
            }
        }
        return found;
    }

    @Test
    void germanTranslationHasTheSameKeys() throws IOException {
        assertEquals(new TreeSet<>(load(BASE + ".properties").stringPropertyNames()),
                new TreeSet<>(load(BASE + "_de.properties").stringPropertyNames()));
    }

    @Test
    void everyTextKeyUsedInCodeIsTranslated() throws IOException {
        Set<String> used = matches(Path.of("src/main/java"), ".java", TEXT_KEY, 1);
        assertFalse(used.isEmpty());
        Set<String> defined = load(BASE + ".properties").stringPropertyNames();
        for (String key : used) {
            assertTrue(defined.contains(key), "missing translation for " + key);
        }
    }

    @Test
    void englishFileMatchesTheXmlTexts() throws IOException {
        Properties english = load(BASE + ".properties");
        Set<String> values = new HashSet<>();
        for (String key : english.stringPropertyNames()) {
            values.add(normalize(english.getProperty(key)));
        }
        for (String text : matches(Path.of("src/main/resources/OH-INF"), ".xml", XML_TEXT, 2)) {
            String expected = normalize(text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                    .replace("&quot;", "\"").replace("&apos;", "'"));
            assertTrue(values.contains(expected), "not in " + BASE + ".properties (regenerate it): " + expected);
        }
    }
}
