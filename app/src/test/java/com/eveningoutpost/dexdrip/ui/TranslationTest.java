package com.eveningoutpost.dexdrip.ui;


import static com.eveningoutpost.dexdrip.xdrip.gs;
import static com.google.common.truth.Truth.assertWithMessage;

import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import com.eveningoutpost.dexdrip.xdrip;

import org.junit.Test;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import lombok.val;

public class TranslationTest extends RobolectricTestWithConfig {

    /**
     * Check that MessageFormat strings are still working after resource translation
     */


    @Test
    public void testFormatStrings() throws IOException {
        xdrip.setContextAlways(RuntimeEnvironment.getApplication());
        val config = xdrip.getAppContext().getResources().getConfiguration();
        val internal = xdrip.getAppContext().getResources().getStringArray(R.array.LocaleChoicesValues);
        val extra = new String[]{"ar", "cs", "de", "el", "en", "es", "fi", "fr", "he", "hr", "it", "iw", "ja", "ko", "nb", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "zh"};
        Set<String> locales = new TreeSet<>(Arrays.asList(internal));
        val size = locales.size();
        // AGP 8 no longer creates merged-not-compiled-resources. Gradle runs this
        // task in the app module, so inspect the checked-in resource directories.
        // This also works when AssetManager.getLocales() is unimplemented by a shadow.
        final Path resourcePath = Paths.get("src/main/res");
        assertWithMessage("Missing app resource source directory").that(Files.isDirectory(resourcePath)).isTrue();
        try (Stream<Path> directories = Files.list(resourcePath)) {
            directories.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("values-[a-z]{2}(-r[a-zA-Z]{2})?"))
                    .map(name -> name.substring("values-".length()).replace("-r", "-"))
                    .forEach(locales::add);
        }
        assertWithMessage("No locales added from resources - this seems unlikely").that(locales.size()).isGreaterThan(size);
        locales.addAll(Arrays.asList(extra));
        String fmt;
        String result;

        for (val language : locales) {

            System.out.println("Trying choice patterns for language: " + language);
            val langCountry = language.split("-");
            config.setLocale(new Locale(langCountry[0], langCountry.length > 1 ? langCountry[1] : "", ""));
            xdrip.getAppContext().getResources().updateConfiguration(config, xdrip.getAppContext().getResources().getDisplayMetrics());

            try {
                // check minutes ago days
                fmt = gs(R.string.minutes_ago);
                result = MessageFormat.format(fmt, 123);
                assertWithMessage("minutes_ago choice message format failed to contain value").that(result).contains("123");
            } catch (IllegalArgumentException e) {
                restoreLocale();
                throw new RuntimeException("Failed minutes ago test with language " + language + " with exception: " + e);
            }

            try {
                // check expires days
                fmt = gs(R.string.expires_days);
                result = MessageFormat.format(fmt, 123.4f);
                assertWithMessage("expires_days choice message format failed to contain value").that(result).contains("123.4");
            } catch (IllegalArgumentException e) {
                restoreLocale();
                throw new RuntimeException("Failed expires days test with language " + language + " with exception: " + e);
            }

            assertWithMessage("megabyte format " + language).that(gs(R.string.megabyte_format_string)).contains("%.1f");
            assertWithMessage("time format " + language).that(gs(R.string.format_string_ago)).contains("%s");

        }

        restoreLocale();
    }

    private void restoreLocale() {
        // restore after test
        val config = xdrip.getAppContext().getResources().getConfiguration();
        config.setLocale(new Locale("en", "", ""));
        xdrip.getAppContext().getResources().updateConfiguration(config, xdrip.getAppContext().getResources().getDisplayMetrics());
    }
}
