package com.amedias.expense;

import com.amedias.expense.ExpenseDtos.ExpenseResponse;
import com.amedias.expense.ExpenseDtos.ItemDto;
import com.amedias.expense.ExpenseDtos.ParticipantDto;
import com.amedias.expense.ExpenseDtos.ShareDto;
import com.amedias.expense.ExpenseDtos.SplitMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExpenseRepository {

    public record HouseholdParticipant(String id, String name) {}

    public record NewExpense(UUID id, UUID householdId, String merchant, LocalDate date, String currency,
                             SplitMode mode, long totalCents, String registeredById, String paidById,
                             String idempotencyKey, String requestHash) {}

    public record NewItem(String clientId, String name, long amountCents, boolean selected, String ownerId) {}

    public record NewParticipant(String id, String name, int weight) {}

    public record NewShare(String participantId, long amountCents) {}

    public record KeyMatch(UUID id, String requestHash) {}

    private record ExpenseRow(UUID id, String merchant, LocalDate date, String currency, SplitMode mode, long totalCents,
                              String registeredById, String paidById, Instant settledAt, Instant createdAt) {}

    private record Keyed<T>(UUID expenseId, T value) {}

    private static final String SELECT = """
            select id, comercio, fecha_compra, moneda, modo_reparto, total_centimos,
                   registrado_por_id, pagado_por_id, liquidado_en, creado_en
            from gastos""";

    private final JdbcClient jdbc;

    public ExpenseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- escritura (llamar dentro de una transacción) ----------

    public void insert(NewExpense e, List<NewItem> items, List<NewParticipant> participants, List<NewShare> shares) {
        jdbc.sql("""
                insert into gastos (id, hogar_id, comercio, fecha_compra, moneda, modo_reparto, total_centimos,
                                    registrado_por_id, pagado_por_id, clave_idempotencia, huella_solicitud)
                values (:id, :h, :merchant, :date, :currency, :mode, :total, :reg, :paid, :key, :hash)""")
                .param("id", e.id()).param("h", e.householdId()).param("merchant", e.merchant())
                .param("date", e.date()).param("currency", e.currency())
                .param("mode", e.mode() == SplitMode.equal ? "igual" : "ponderado")
                .param("total", e.totalCents()).param("reg", e.registeredById()).param("paid", e.paidById())
                .param("key", e.idempotencyKey()).param("hash", e.requestHash())
                .update();

        int pos = 0;
        for (NewParticipant p : participants) {
            jdbc.sql("""
                    insert into participantes_gasto (gasto_id, participante_id, nombre, peso, posicion)
                    values (:e, :p, :name, :w, :pos)""")
                    .param("e", e.id()).param("p", p.id()).param("name", p.name()).param("w", p.weight())
                    .param("pos", pos++).update();
        }
        pos = 0;
        for (NewItem it : items) {
            jdbc.sql("""
                    insert into lineas_gasto (gasto_id, id_linea, posicion, nombre, importe_centimos, seleccionada, propietario_id)
                    values (:e, :lid, :pos, :name, :amount, :sel, :owner)""")
                    .param("e", e.id()).param("lid", it.clientId()).param("pos", pos++).param("name", it.name())
                    .param("amount", it.amountCents()).param("sel", it.selected())
                    .param("owner", it.ownerId(), Types.VARCHAR)
                    .update();
        }
        for (NewShare s : shares) {
            jdbc.sql("insert into cuotas_gasto (gasto_id, participante_id, cuota_centimos) values (:e, :p, :c)")
                    .param("e", e.id()).param("p", s.participantId()).param("c", s.amountCents()).update();
        }
    }

    /** Idempotente por estado: al repetir true conserva la fecha original; false la deja a null. */
    public boolean setSettled(UUID household, UUID id, boolean settled) {
        return jdbc.sql("""
                update gastos
                set liquidado_en = case when :s then coalesce(liquidado_en, now()) else null end
                where id = :id and hogar_id = :h""")
                .param("s", settled).param("id", id).param("h", household).update() > 0;
    }

    public boolean delete(UUID household, UUID id) {
        return jdbc.sql("delete from gastos where id = :id and hogar_id = :h")
                .param("id", id).param("h", household).update() > 0;
    }

    // ---------- lectura ----------

    public List<HouseholdParticipant> householdParticipants(UUID household) {
        return jdbc.sql("select id, nombre_mostrado from participantes where hogar_id = :h order by posicion")
                .param("h", household)
                .query((rs, i) -> new HouseholdParticipant(rs.getString("id"), rs.getString("nombre_mostrado")))
                .list();
    }

    public Optional<KeyMatch> findKey(UUID household, String key) {
        return jdbc.sql("select id, huella_solicitud from gastos where hogar_id = :h and clave_idempotencia = :k")
                .param("h", household).param("k", key)
                .query((rs, i) -> new KeyMatch(rs.getObject("id", UUID.class), rs.getString("huella_solicitud").trim()))
                .optional();
    }

    public List<ExpenseResponse> list(UUID household) {
        List<ExpenseRow> rows = jdbc.sql(SELECT + " where hogar_id = :h order by creado_en desc, id limit 500")
                .param("h", household).query(this::mapRow).list();
        return hydrate(rows);
    }

    public Optional<ExpenseResponse> find(UUID household, UUID id) {
        List<ExpenseRow> rows = jdbc.sql(SELECT + " where hogar_id = :h and id = :id")
                .param("h", household).param("id", id).query(this::mapRow).list();
        return hydrate(rows).stream().findFirst();
    }

    private ExpenseRow mapRow(ResultSet rs, int i) throws SQLException {
        return new ExpenseRow(rs.getObject("id", UUID.class), rs.getString("comercio"),
                rs.getObject("fecha_compra", LocalDate.class), rs.getString("moneda"),
                "igual".equals(rs.getString("modo_reparto")) ? SplitMode.equal : SplitMode.weighted,
                rs.getLong("total_centimos"), rs.getString("registrado_por_id"), rs.getString("pagado_por_id"),
                instant(rs.getObject("liquidado_en", OffsetDateTime.class)),
                instant(rs.getObject("creado_en", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private List<ExpenseResponse> hydrate(List<ExpenseRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(ExpenseRow::id).toList();

        Map<UUID, List<ItemDto>> items = group(jdbc.sql("""
                select gasto_id, id_linea, nombre, importe_centimos, seleccionada, propietario_id
                from lineas_gasto where gasto_id in (:ids) order by gasto_id, posicion""")
                .param("ids", ids)
                .query((rs, i) -> new Keyed<>(rs.getObject("gasto_id", UUID.class),
                        new ItemDto(rs.getString("id_linea"), rs.getString("nombre"), rs.getLong("importe_centimos"),
                                rs.getBoolean("seleccionada"), rs.getString("propietario_id"))))
                .list());

        Map<UUID, List<ParticipantDto>> participants = group(jdbc.sql("""
                select gasto_id, participante_id, nombre, peso
                from participantes_gasto where gasto_id in (:ids) order by gasto_id, posicion""")
                .param("ids", ids)
                .query((rs, i) -> new Keyed<>(rs.getObject("gasto_id", UUID.class),
                        new ParticipantDto(rs.getString("participante_id"), rs.getString("nombre"), rs.getInt("peso"))))
                .list());

        Map<UUID, List<ShareDto>> shares = group(jdbc.sql("""
                select s.gasto_id, s.participante_id, p.nombre, s.cuota_centimos
                from cuotas_gasto s
                join participantes_gasto p on p.gasto_id = s.gasto_id and p.participante_id = s.participante_id
                where s.gasto_id in (:ids) order by s.gasto_id, p.posicion""")
                .param("ids", ids)
                .query((rs, i) -> new Keyed<>(rs.getObject("gasto_id", UUID.class),
                        new ShareDto(rs.getString("participante_id"), rs.getString("nombre"), rs.getLong("cuota_centimos"))))
                .list());

        List<ExpenseResponse> out = new ArrayList<>();
        for (ExpenseRow r : rows) {
            out.add(new ExpenseResponse(r.id().toString(), r.merchant(), r.date(), r.currency(),
                    items.getOrDefault(r.id(), List.of()), participants.getOrDefault(r.id(), List.of()), r.mode(),
                    r.totalCents(), shares.getOrDefault(r.id(), List.of()), r.createdAt(),
                    r.registeredById(), r.paidById(), r.settledAt()));
        }
        return out;
    }

    private static <T> Map<UUID, List<T>> group(List<Keyed<T>> keyed) {
        Map<UUID, List<T>> map = new HashMap<>();
        for (Keyed<T> k : keyed) {
            map.computeIfAbsent(k.expenseId(), x -> new ArrayList<>()).add(k.value());
        }
        return map;
    }
}
