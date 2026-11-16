package com.amedias.expense;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() {}

    /** Importe decimal (p. ej. el que lee la IA) → céntimos enteros, sin pasar por double. */
    public static long roundToCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }
}
