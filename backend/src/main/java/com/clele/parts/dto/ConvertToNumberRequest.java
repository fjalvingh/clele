package com.clele.parts.dto;

import lombok.*;

import java.util.HashMap;
import java.util.Map;

/** Request to convert a TEXT spec definition to NUMBER by parsing its part values into a base unit. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConvertToNumberRequest {

    /** Principal (base) SI unit to parse values into, e.g. "A". Blank = scan only, suggest a unit. */
    private String unit;

    /** Enable metric-prefix display/edit on the resulting NUMBER spec. */
    private boolean metricPrefix;

    /** Replacement text for distinct original values that failed to parse (originalValue -> replacement). */
    private Map<String, String> overrides = new HashMap<>();

    /** When true, apply the conversion; otherwise dry-run (scan + report only). */
    private boolean commit;
}
