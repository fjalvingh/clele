package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One spec value of one part, typed — replacing the loose {@code part.specs} JSONB entry.
 *
 * <h2>Parsed or raw, never both</h2>
 *
 * A row holds exactly one of three shapes, enforced by the {@code part_spec_value_one_shape} check
 * constraint and by the mutators here (each clears the other two):
 *
 * <ul>
 *   <li><b>scalar</b> — {@link #valueNum}, in the definition's base SI unit. {@code "100nF"} against
 *       a capacitance field is {@code 1e-7}, so it compares equal to {@code "0.1 uF"} and to
 *       {@code "1e-7"}, which as strings are three unrelated values.</li>
 *   <li><b>range</b> — {@link #valueMin}/{@link #valueMax}, either bound open. This is what makes the
 *       ~1,500 Partsbox range strings ({@code "4.5..null"}, {@code "3..16"}) first-class: they answer
 *       containment queries the JSONB could not express and the convert-to-number tool has to
 *       refuse.</li>
 *   <li><b>text</b> — {@link #valueText}, for TEXT/SELECT/BOOLEAN definitions and for anything that
 *       did not parse. Nothing was extracted from it, so nothing can drift.</li>
 * </ul>
 *
 * <p><b>BOOLEAN is text</b> ({@code "true"}/{@code "false"}), not 0/1: booleans are filtered by
 * equality and never by range, so they need no numeric column and share the text index.
 *
 * <p><b>No stored rendering.</b> Display is derived from the number every time, by
 * {@code MetricUnitFormatter} — the exact inverse of the parser. A stored {@code display} column
 * would be denormalisation with nothing keeping it in step when the value is edited.
 */
// Deliberately no class-level @Setter: the three shapes are mutually exclusive, and a generated
// setValueNum() beside setValueMin() is an invitation to build a row the check constraint rejects.
// The shape mutators below are the only way in.
@Entity
@Table(name = "part_spec_value")
@IdClass(PartSpecValue.Key.class)
@Getter
@NoArgsConstructor
public class PartSpecValue {

    /** Composite key — a part holds at most one value per spec field. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private Long part;
        private Long specDefinition;

        public Key(Long part, Long specDefinition) {
            this.part = part;
            this.specDefinition = specDefinition;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return Objects.equals(part, other.part) && Objects.equals(specDefinition, other.specDefinition);
        }

        @Override
        public int hashCode() {
            return Objects.hash(part, specDefinition);
        }
    }

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spec_definition_id", nullable = false)
    private SpecDefinition specDefinition;

    @Column(name = "value_num")
    private BigDecimal valueNum;

    @Column(name = "value_min")
    private BigDecimal valueMin;

    @Column(name = "value_max")
    private BigDecimal valueMax;

    @Column(name = "value_text", columnDefinition = "TEXT")
    private String valueText;

    private PartSpecValue(Part part, SpecDefinition specDefinition) {
        this.part = part;
        this.specDefinition = specDefinition;
    }

    public static PartSpecValue scalar(Part part, SpecDefinition def, BigDecimal value) {
        PartSpecValue v = new PartSpecValue(part, def);
        v.setScalar(value);
        return v;
    }

    public static PartSpecValue range(Part part, SpecDefinition def, BigDecimal min, BigDecimal max) {
        PartSpecValue v = new PartSpecValue(part, def);
        v.setRange(min, max);
        return v;
    }

    public static PartSpecValue text(Part part, SpecDefinition def, String value) {
        PartSpecValue v = new PartSpecValue(part, def);
        v.setText(value);
        return v;
    }

    /** Set the scalar shape, clearing the other two — the check constraint permits only one. */
    public void setScalar(BigDecimal value) {
        this.valueNum = value;
        this.valueMin = null;
        this.valueMax = null;
        this.valueText = null;
    }

    /** Set the range shape. At least one bound must be present; the other stays open. */
    public void setRange(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            throw new IllegalArgumentException("a range needs at least one bound");
        }
        this.valueMin = min;
        this.valueMax = max;
        this.valueNum = null;
        this.valueText = null;
    }

    /** Set the raw shape — the value nothing was extracted from. */
    public void setText(String value) {
        this.valueText = value;
        this.valueNum = null;
        this.valueMin = null;
        this.valueMax = null;
    }

    public boolean isRange() {
        return valueMin != null || valueMax != null;
    }

    public boolean isNumeric() {
        return valueNum != null || isRange();
    }
}
