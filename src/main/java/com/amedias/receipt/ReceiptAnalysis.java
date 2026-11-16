package com.amedias.receipt;

import java.time.LocalDate;
import java.util.List;

/** Respuesta de POST /api/receipts/analyze según docs/CONTRATO-API.md. */
public record ReceiptAnalysis(
        String merchant,
        LocalDate date,
        String currency,
        long totalCents,
        List<Item> items,
        List<String> warnings) {

    public record Item(String name, long amountCents) {}
}
