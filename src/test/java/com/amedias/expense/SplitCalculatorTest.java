package com.amedias.expense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.amedias.expense.SplitCalculator.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ejemplos literales de docs/CONTRATO-API.md. */
class SplitCalculatorTest {

    private static final List<String> IDS = List.of("adela", "martin");

    private static List<Long> shares(List<Line> lines, int wAdela, int wMartin) {
        return SplitCalculator.compute(lines, IDS, List.of(wAdela, wMartin)).shares();
    }

    @Test
    void oddCentGoesToFirstOnTie() {
        assertEquals(List.of(51L, 50L), shares(List.of(new Line(101, true, null)), 1, 1));
    }

    @Test
    void weighted60_40() {
        assertEquals(List.of(1491L, 994L), shares(List.of(new Line(2485, true, null)), 60, 40));
    }

    @Test
    void personalProductEqual() {
        // 4000 compartidos + 1000 solo de la primera persona
        assertEquals(List.of(3000L, 2000L), shares(List.of(new Line(4000, true, null), new Line(1000, true, "adela")), 1, 1));
    }

    @Test
    void personalProductWeighted() {
        assertEquals(List.of(3400L, 1600L), shares(List.of(new Line(4000, true, null), new Line(1000, true, "adela")), 60, 40));
    }

    @Test
    void unselectedLinesAreIgnored() {
        var r = SplitCalculator.compute(List.of(new Line(1000, true, null), new Line(9999, false, "martin")), IDS, List.of(1, 1));
        assertEquals(1000, r.totalCents());
        assertEquals(List.of(500L, 500L), r.shares());
    }

    @Test
    void negativeSharedSubtotalKeepsSignAfterDistribution() {
        // -101 compartidos + 2000 personales de cada uno
        var r = SplitCalculator.compute(
                List.of(new Line(-101, true, null), new Line(2000, true, "adela"), new Line(2000, true, "martin")),
                IDS, List.of(1, 1));
        assertEquals(List.of(1949L, 1950L), r.shares());
        assertEquals(3899, r.totalCents());
    }

    @Test
    void sharesSumToTotal() {
        var r = SplitCalculator.compute(List.of(new Line(9999, true, null), new Line(333, true, "martin")), IDS, List.of(37, 63));
        assertEquals(r.totalCents(), r.shares().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void discountCannotLeaveNegativeShare() {
        assertThrows(IllegalArgumentException.class, () -> shares(
                List.of(new Line(1000, true, "adela"), new Line(-3000, true, "martin")), 1, 1));
    }

    @Test
    void unknownOwnerIsRejectedEvenWhenUnselected() {
        assertThrows(IllegalArgumentException.class, () -> shares(List.of(new Line(100, false, "otra")), 1, 1));
    }
}
