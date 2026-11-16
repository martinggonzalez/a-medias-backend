package com.amedias.expense;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** DTOs exactos de docs/CONTRATO-API.md (camelCase, importes en céntimos). */
public final class ExpenseDtos {

    private ExpenseDtos() {}

    public enum SplitMode { equal, weighted }

    public record ItemDto(
            @NotBlank String id,
            @NotBlank String name,
            @NotNull Long amountCents,
            @NotNull Boolean selected,
            String ownerId) {}

    public record ParticipantDto(
            @NotBlank String id,
            @NotBlank String name,
            @NotNull Integer weight) {}

    public record ShareDto(String participantId, String name, long amountCents) {}

    public record ExpenseRequest(
            @NotBlank String merchant,
            @NotNull LocalDate date,
            @NotBlank String currency,
            @NotEmpty @Size(max = 100) List<@Valid ItemDto> items,
            @NotEmpty @Size(max = 8) List<@Valid ParticipantDto> participants,
            @NotNull SplitMode splitMode,
            @NotBlank String registeredById,
            @NotBlank String paidById) {}

    public record ExpenseResponse(
            String id,
            String merchant,
            LocalDate date,
            String currency,
            List<ItemDto> items,
            List<ParticipantDto> participants,
            SplitMode splitMode,
            long totalCents,
            List<ShareDto> shares,
            Instant createdAt,
            String registeredById,
            String paidById,
            Instant settledAt) {}

    public record ExpenseListResponse(List<ExpenseResponse> items) {}

    public record SettlementRequest(@NotNull Boolean settled) {}
}
