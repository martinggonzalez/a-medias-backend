package com.amedias.expense;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Algoritmo de reparto de docs/CONTRATO-API.md, idéntico a splitItems/splitAmount del frontend:
 * 1. Solo cuentan las líneas seleccionadas: subtotal compartido (sin propietario) y subtotal personal por persona.
 * 2. El subtotal compartido se reparte en valor absoluto según los pesos efectivos (1 si el modo es equal).
 * 3. Los céntimos sobrantes van a los mayores restos; en empate, antes quien aparece primero en participants.
 * 4. Si el subtotal compartido era negativo, se cambia el signo de las cuotas compartidas.
 * 5. A cada cuota se le suma íntegro su subtotal personal. Una cuota final negativa se rechaza.
 */
public final class SplitCalculator {

    public record Line(long amountCents, boolean selected, String ownerId) {}

    public record Result(long totalCents, List<Long> shares) {}

    private SplitCalculator() {}

    /**
     * @param ids     ids de los participantes, en el orden de la solicitud
     * @param weights pesos efectivos en el mismo orden (todos 1 en modo equal)
     */
    public static Result compute(List<Line> lines, List<String> ids, List<Integer> weights) {
        if (ids.size() < 2 || ids.size() != weights.size()) {
            throw new IllegalArgumentException("Participantes inválidos");
        }
        Map<String, Long> personal = new HashMap<>();
        ids.forEach(id -> personal.put(id, 0L));
        long shared = 0;
        long total = 0;
        for (Line l : lines) {
            if (l.ownerId() != null && !personal.containsKey(l.ownerId())) {
                throw new IllegalArgumentException("El propietario del producto debe ser una de las personas del reparto");
            }
            if (!l.selected()) {
                continue;
            }
            total += l.amountCents();
            if (l.ownerId() == null) {
                shared += l.amountCents();
            } else {
                personal.merge(l.ownerId(), l.amountCents(), Long::sum);
            }
        }

        long sign = shared < 0 ? -1 : 1;
        long abs = Math.abs(shared);
        long weightTotal = 0;
        for (int w : weights) {
            if (w < 1 || w > 10_000) {
                throw new IllegalArgumentException("Los pesos deben ser enteros entre 1 y 10000");
            }
            weightTotal += w;
        }

        int n = ids.size();
        long[] part = new long[n];
        long[] remainder = new long[n];
        long assigned = 0;
        for (int i = 0; i < n; i++) {
            long numerator = Math.multiplyExact(abs, (long) weights.get(i));
            part[i] = numerator / weightTotal;
            remainder[i] = numerator % weightTotal;
            assigned += part[i];
        }
        long left = abs - assigned;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingLong(i -> remainder[i]).reversed().thenComparingInt(i -> i));
        for (int k = 0; k < left; k++) {
            part[order.get(k)] += 1;
        }

        List<Long> shares = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long share = sign * part[i] + personal.get(ids.get(i));
            if (share < 0) {
                throw new IllegalArgumentException(
                        "Los descuentos no pueden dejar a una persona con un importe negativo. Revisa a quién asignas cada descuento");
            }
            shares.add(share);
        }
        return new Result(total, shares);
    }
}
