package com.amedias.expense;

import com.amedias.expense.ExpenseDtos.ExpenseRequest;
import com.amedias.expense.ExpenseDtos.ExpenseResponse;
import com.amedias.expense.ExpenseDtos.ItemDto;
import com.amedias.expense.ExpenseDtos.ParticipantDto;
import com.amedias.expense.ExpenseDtos.SplitMode;
import com.amedias.expense.ExpenseRepository.HouseholdParticipant;
import com.amedias.expense.ExpenseRepository.KeyMatch;
import com.amedias.expense.ExpenseRepository.NewExpense;
import com.amedias.expense.ExpenseRepository.NewItem;
import com.amedias.expense.ExpenseRepository.NewParticipant;
import com.amedias.expense.ExpenseRepository.NewShare;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExpenseService {

    private static final long MAX_LINE_CENTS = 100_000_000L;
    private static final Set<String> CURRENCIES = Set.of("EUR", "USD", "GBP");

    public record SaveResult(ExpenseResponse expense, boolean created) {}

    private record Prepared(String merchant, String currency, SplitMode mode, String registeredById, String paidById,
                            List<NewItem> items, List<NewParticipant> participants, List<NewShare> shares,
                            long totalCents, String hash, ExpenseRequest normalized) {}

    private final ExpenseRepository repo;
    private final TransactionTemplate tx;

    public ExpenseService(ExpenseRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
    }

    public List<ExpenseResponse> list(UUID household) {
        return repo.list(household);
    }

    /**
     * Guarda un gasto recalculando el reparto en el servidor (nunca se acepta un reparto del cliente).
     * Misma Idempotency-Key + mismo cuerpo → mismo gasto (200). Misma clave + distinto cuerpo → 409.
     */
    public SaveResult save(UUID household, ExpenseRequest req, String rawKey) {
        String key = requireKey(rawKey);
        Prepared p = prepare(household, req);

        Optional<KeyMatch> existing = repo.findKey(household, key);
        if (existing.isPresent()) {
            return replay(household, existing.get(), p.hash());
        }

        UUID id = UUID.randomUUID();
        NewExpense expense = new NewExpense(id, household, p.merchant(), req.date(), p.currency(), p.mode(),
                p.totalCents(), p.registeredById(), p.paidById(), key, p.hash());
        try {
            tx.executeWithoutResult(s -> repo.insert(expense, p.items(), p.participants(), p.shares()));
        } catch (DuplicateKeyException e) {
            // Dos peticiones simultáneas con la misma clave: la restricción única deja pasar solo a una.
            KeyMatch winner = repo.findKey(household, key).orElseThrow(() -> e);
            return replay(household, winner, p.hash());
        }
        return new SaveResult(repo.find(household, id).orElseThrow(), true);
    }

    public Optional<ExpenseResponse> setSettled(UUID household, UUID id, boolean settled) {
        boolean found = Boolean.TRUE.equals(tx.execute(s -> repo.setSettled(household, id, settled)));
        return found ? repo.find(household, id) : Optional.empty();
    }

    public boolean delete(UUID household, UUID id) {
        return Boolean.TRUE.equals(tx.execute(s -> repo.delete(household, id)));
    }

    // ---------- validación y cálculo ----------

    private Prepared prepare(UUID household, ExpenseRequest req) {
        String merchant = clean(req.merchant());
        require(!merchant.isEmpty() && merchant.length() <= 120, "Comercio no válido");
        require(CURRENCIES.contains(req.currency()), "Moneda no admitida");

        // Participantes: exactamente las identidades fijas de la pareja, con su nombre.
        List<HouseholdParticipant> house = repo.householdParticipants(household);
        List<ParticipantDto> participants = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        require(req.participants().size() == house.size(), "Usa las identidades fijas de la pareja");
        for (ParticipantDto dto : req.participants()) {
            String name = clean(dto.name());
            HouseholdParticipant known = house.stream().filter(h -> h.id().equals(dto.id())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Participante desconocido: " + dto.id()));
            require(ids.add(dto.id()), "Hay identificadores de persona repetidos");
            require(names.add(name.toLowerCase(Locale.ROOT)), "Usa un nombre distinto para cada persona");
            require(name.equals(clean(known.name())), "El nombre de la persona no coincide con su identidad fija");
            require(dto.weight() != null && dto.weight() >= 1 && dto.weight() <= 10_000,
                    "Los pesos deben ser enteros entre 1 y 10000");
            participants.add(new ParticipantDto(dto.id(), name, dto.weight()));
        }
        require(ids.contains(req.registeredById()), "Quien registra debe pertenecer al reparto");
        require(ids.contains(req.paidById()), "Quien paga debe pertenecer al reparto");

        // Líneas.
        List<ItemDto> items = new ArrayList<>();
        List<SplitCalculator.Line> lines = new ArrayList<>();
        Set<String> itemIds = new HashSet<>();
        for (ItemDto dto : req.items()) {
            String id = dto.id();
            String name = clean(dto.name());
            require(id.length() <= 100 && itemIds.add(id), "Hay identificadores de producto repetidos o demasiado largos");
            require(!name.isEmpty() && name.length() <= 120, "Nombre de producto no válido");
            long cents = dto.amountCents();
            require(Math.abs(cents) <= MAX_LINE_CENTS, "Importe de línea fuera de rango");
            String owner = dto.ownerId();
            require(owner == null || (!owner.isEmpty() && ids.contains(owner)),
                    "El propietario del producto debe ser una de las personas del reparto");
            items.add(new ItemDto(id, name, cents, dto.selected(), owner));
            lines.add(new SplitCalculator.Line(cents, dto.selected(), owner));
        }

        List<String> orderedIds = participants.stream().map(ParticipantDto::id).toList();
        List<Integer> weights = participants.stream()
                .map(p -> req.splitMode() == SplitMode.equal ? 1 : p.weight()).toList();
        SplitCalculator.Result result = SplitCalculator.compute(lines, orderedIds, weights);
        require(result.totalCents() > 0, "Selecciona productos con un total mayor que cero");

        List<NewItem> newItems = items.stream()
                .map(i -> new NewItem(i.id(), i.name(), i.amountCents(), i.selected(), i.ownerId())).toList();
        List<NewParticipant> newParticipants = participants.stream()
                .map(p -> new NewParticipant(p.id(), p.name(), p.weight())).toList();
        List<NewShare> shares = new ArrayList<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            shares.add(new NewShare(orderedIds.get(i), result.shares().get(i)));
        }

        ExpenseRequest normalized = new ExpenseRequest(merchant, req.date(), req.currency(), items, participants,
                req.splitMode(), req.registeredById(), req.paidById());
        return new Prepared(merchant, req.currency(), req.splitMode(), req.registeredById(), req.paidById(),
                newItems, newParticipants, shares, result.totalCents(), hash(normalized), normalized);
    }

    private SaveResult replay(UUID household, KeyMatch match, String hash) {
        if (!match.requestHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La clave de idempotencia ya se usó con otro contenido");
        }
        return new SaveResult(repo.find(household, match.id()).orElseThrow(), false);
    }

    /** Hash canónico de la solicitud ya normalizada: mismo contenido → misma huella. */
    private static String hash(ExpenseRequest r) {
        String us = "\u001f";
        String rs = "\u001e";
        String canonical = String.join(us, r.merchant(), r.date().toString(), r.currency(), r.splitMode().name(),
                r.registeredById(), r.paidById(),
                r.items().stream().map(i -> String.join(us, i.id(), i.name(), String.valueOf(i.amountCents()),
                        String.valueOf(i.selected()), i.ownerId() == null ? "" : i.ownerId()))
                        .collect(Collectors.joining(rs)),
                r.participants().stream().map(p -> String.join(us, p.id(), p.name(), String.valueOf(p.weight())))
                        .collect(Collectors.joining(rs)));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String requireKey(String key) {
        require(key != null && !key.isBlank(), "Falta la cabecera Idempotency-Key");
        String k = key.trim();
        require(k.length() <= 128, "Idempotency-Key demasiado larga");
        return k;
    }

    private static String clean(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFC).trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
