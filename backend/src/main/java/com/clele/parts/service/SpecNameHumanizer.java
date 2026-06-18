package com.clele.parts.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@code part.specs} JSON key into a human-readable spec title.
 *
 * <p>Most Octopart-derived keys are lowercase-concatenated words with no separators
 * (e.g. {@code numberofbits}, {@code operatingsupplyvoltage}), so a plain underscore split
 * is not enough. This splits on separators and camelCase boundaries, then segments each
 * remaining lowercase run against a curated electronics vocabulary via a minimum-word-count
 * dynamic program, and finally renders acronyms (DC, I2C, RoHS, …) and lowercases short
 * connector words ("of", "to", "per") for natural title casing.
 *
 * <p>Best-effort: a token that cannot be fully segmented falls back to a single capitalized
 * word, so an unknown key never throws — it just yields a less pretty title (which the user
 * can edit; rescans preserve edits).
 */
final class SpecNameHumanizer {

    private SpecNameHumanizer() {
    }

    /** Constituent words used to segment concatenated keys. Single, unknown tokens fall back. */
    private static final Set<String> DICT = Set.of(
            "access", "accuracy", "actuator", "adc", "address", "ambient", "amplifiers", "angle",
            "architecture", "assembly", "average", "bandwidth", "base", "batteries", "baud", "bias",
            "bidirectional", "bits", "body", "breakdown", "bus", "capacitance", "case", "cells",
            "channel", "channels", "circuit", "circuits", "clamping", "clock", "code", "coefficient",
            "coil", "collector", "color", "common", "compliant", "configuration", "connector",
            "consumption", "contact", "contacts", "continuous", "converters", "core", "country",
            "current", "cycle",
            "dac", "dark", "data", "date", "dc", "delay", "dielectric", "differential", "diode",
            "direction", "dissipation", "dominant", "drain", "drivers", "dropout", "dual", "duty",
            "eccn", "edge", "eeprom", "electrical", "element", "elements", "emitter", "esd",
            "evaluation", "fabrication", "factor", "fall", "fastening", "fault", "finish",
            "flammability", "flash", "kit", "status", "style", "travel", "type",
            "force", "format", "forward", "free", "frequency", "function", "fuse", "gain", "gates",
            "gender", "glow", "grade", "halogen", "hardening", "height", "hfe", "high", "hold",
            "housing", "hs", "hts", "hysteresis", "i2c", "illumination", "independent", "inductance",
            "input", "inputs", "insulation", "intensity", "interrupts", "intro", "isolation",
            "junction", "leakage", "lead", "leds", "length", "lens", "level", "life", "limit", "line",
            "lines", "load", "logic", "low", "luminous", "macro", "manufacturer", "material", "mating",
            "max", "mechanical", "memory", "military", "min", "mode", "monitored", "mount", "natural",
            "nominal", "number", "of", "on", "operate", "operating", "origin", "oscillator", "output",
            "outputs", "over", "package", "peak", "per", "phases", "pins", "pitch", "plating",
            "polarity", "poles", "ports", "positions", "post", "power", "product", "propagation",
            "protection", "pulse", "pwm", "q", "quantity", "quiescent", "radiation", "ram", "range",
            "rate", "rating", "ratio", "reach", "receiver", "receivers", "recovery", "rectified",
            "rectifier", "reference", "regulators", "rejection", "relay", "release", "reset",
            "resistance", "resistors", "resolution", "resonant", "response", "reverse", "ripple",
            "rise", "row", "rows", "rds", "saturation", "schmitt", "self", "sensor", "series",
            "settling", "shell", "size", "slew", "source", "spacing", "speed", "spi", "stability",
            "stack", "standard", "standoff", "supply", "surge", "svhc", "switch", "switching", "sync",
            "synchronous", "temperature", "terminal", "terminals", "test", "thermal", "thickness",
            "threshold", "throw", "time", "timeout", "timer", "to", "tolerance", "transceivers",
            "transfer", "transition", "transmitters", "transparency", "trigger", "trip", "turns",
            "uart", "under", "unidirectional", "unity", "usart", "usb", "viewing", "vgs", "voltage",
            "voltages", "watchdog", "wavelength", "width", "wire", "word", "words", "working", "zener");

    /** Tokens rendered as fixed-case acronyms instead of being capitalized. */
    private static final Map<String, String> ACRONYMS = Map.ofEntries(
            Map.entry("ac", "AC"), Map.entry("dc", "DC"), Map.entry("adc", "ADC"),
            Map.entry("dac", "DAC"), Map.entry("i2c", "I2C"), Map.entry("pwm", "PWM"),
            Map.entry("spi", "SPI"), Map.entry("uart", "UART"), Map.entry("usart", "USART"),
            Map.entry("usb", "USB"), Map.entry("esd", "ESD"), Map.entry("pll", "PLL"),
            Map.entry("ram", "RAM"), Map.entry("eeprom", "EEPROM"), Map.entry("eccn", "ECCN"),
            Map.entry("hs", "HS"), Map.entry("hts", "HTS"), Map.entry("svhc", "SVHC"),
            Map.entry("reach", "REACH"), Map.entry("rohs", "RoHS"), Map.entry("hfe", "hFE"),
            Map.entry("rds", "RDS"), Map.entry("vgs", "VGS"), Map.entry("vcbo", "VCBO"),
            Map.entry("vceo", "VCEO"), Map.entry("id", "ID"), Map.entry("eol", "EOL"),
            Map.entry("q", "Q"), Map.entry("led", "LED"), Map.entry("leds", "LEDs"),
            Map.entry("ic", "IC"));

    /** Short connector words kept lowercase unless they lead the title. */
    private static final Set<String> SMALL_WORDS = Set.of("of", "to", "on", "per", "and", "the", "for");

    /** Splits camelCase / acronym-then-word boundaries (e.g. "ProjectedEOLDate" -> 3 parts). */
    private static final String CAMEL_SPLIT = "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])";

    static String humanize(String jsonName) {
        if (jsonName == null || jsonName.isBlank()) return jsonName;

        List<String> words = new ArrayList<>();
        for (String part : jsonName.split("[_-]+")) {
            if (part.isEmpty()) continue;
            for (String token : part.split(CAMEL_SPLIT)) {
                if (token.isEmpty()) continue;
                if (token.equals(token.toLowerCase())) {
                    List<String> seg = segment(token);
                    if (seg != null) words.addAll(seg);
                    else words.add(token);
                } else {
                    words.add(token); // already mixed/upper case: treat as a unit
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(renderWord(words.get(i), i == 0));
        }
        return sb.length() == 0 ? jsonName : sb.toString();
    }

    /** Segments a lowercase run into dictionary words minimizing word count; null if impossible. */
    private static List<String> segment(String s) {
        int n = s.length();
        @SuppressWarnings("unchecked")
        List<String>[] best = new List[n + 1];
        int[] count = new int[n + 1];
        for (int i = 0; i <= n; i++) count[i] = Integer.MAX_VALUE;
        best[n] = new ArrayList<>();
        count[n] = 0;
        for (int i = n - 1; i >= 0; i--) {
            for (int j = i + 1; j <= n; j++) {
                if (count[j] == Integer.MAX_VALUE) continue;
                if (!DICT.contains(s.substring(i, j))) continue;
                if (count[j] + 1 < count[i]) {
                    count[i] = count[j] + 1;
                    List<String> next = new ArrayList<>();
                    next.add(s.substring(i, j));
                    next.addAll(best[j]);
                    best[i] = next;
                }
            }
        }
        return best[0];
    }

    private static String renderWord(String word, boolean first) {
        String lower = word.toLowerCase();
        String acronym = ACRONYMS.get(lower);
        if (acronym != null) return acronym;
        if (!first && SMALL_WORDS.contains(lower)) return lower;
        if (word.equals(lower)) {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
        return word; // preserve existing capitalization (camelCase remainder)
    }
}
