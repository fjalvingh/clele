package com.clele.parts.imports;

import com.cognitect.transit.Named;
import com.cognitect.transit.Reader;
import com.cognitect.transit.TransitFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a Partsbox WebSocket capture (data.txt): a Sente/Transit+JSON message log. Extracts
 * every {@code core/initial-data} frame and returns its rows grouped by table name
 * ({@code "parts"}, {@code "storage"}, …).
 *
 * <p>The Transit structures are deep-converted to plain {@code String}-keyed maps / lists so
 * callers navigate with {@code map.get("part/name")} instead of Transit Keyword objects.
 */
public class PartsboxTransitReader {

    private static final Logger log = LoggerFactory.getLogger(PartsboxTransitReader.class);
    private static final String EVENT = "core/initial-data";

    /** Returns table name -> list of row maps, concatenated across all initial-data frames. */
    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, Object>>> readInitialData(Path file) throws Exception {
        Map<String, List<Map<String, Object>>> byTable = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (!line.contains(EVENT)) {
                continue;
            }
            String payload = stripFraming(line);
            if (payload == null) {
                continue;
            }
            Object decoded;
            try {
                Reader reader = TransitFactory.reader(TransitFactory.Format.JSON,
                        new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
                decoded = convert(reader.read());
            } catch (Exception e) {
                log.warn("Skipping unparseable frame ({} chars): {}", payload.length(), e.getMessage());
                continue;
            }
            // Sente envelope: [ [ [ "core/initial-data", {:table .., :data [..]} ], ... ], reply ]
            if (!(decoded instanceof List<?> envelope) || envelope.isEmpty()
                    || !(envelope.get(0) instanceof List<?> events)) {
                continue;
            }
            for (Object ev : events) {
                if (!(ev instanceof List<?> event) || event.size() < 2
                        || !EVENT.equals(event.get(0)) || !(event.get(1) instanceof Map<?, ?> body)) {
                    continue;
                }
                Object table = body.get("table");
                Object data = body.get("data");
                if (table instanceof String t && data instanceof List<?> rows) {
                    List<Map<String, Object>> bucket = byTable.computeIfAbsent(t, k -> new ArrayList<>());
                    for (Object row : rows) {
                        if (row instanceof Map<?, ?> m) {
                            bucket.add((Map<String, Object>) m);
                        }
                    }
                }
            }
        }
        return byTable;
    }

    /** A payload line ends with the rendered Transit value then a tab + byte count. */
    private static String stripFraming(String line) {
        int end = line.lastIndexOf(']');
        if (end < 0) {
            return null;
        }
        int start = line.indexOf('[');
        return start < 0 ? null : line.substring(start, end + 1);
    }

    /** Deep-convert Transit output: Keyword/Symbol -> "ns/name", Map -> String-keyed, Set -> List. */
    @SuppressWarnings("unchecked")
    private static Object convert(Object o) {
        if (o instanceof Named named) {
            String ns = named.getNamespace();
            return ns == null ? named.getName() : ns + "/" + named.getName();
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>(m.size() * 2);
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(convert(e.getKey())), convert(e.getValue()));
            }
            return out;
        }
        if (o instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) {
                out.add(convert(e));
            }
            return out;
        }
        if (o instanceof Set<?> set) {
            List<Object> out = new ArrayList<>(set.size());
            for (Object e : set) {
                out.add(convert(e));
            }
            return out;
        }
        return o;
    }
}
