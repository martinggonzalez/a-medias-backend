package com.amedias.expense;

import com.amedias.expense.ExpenseDtos.ExpenseListResponse;
import com.amedias.expense.ExpenseDtos.ExpenseRequest;
import com.amedias.expense.ExpenseDtos.ExpenseResponse;
import com.amedias.expense.ExpenseDtos.SettlementRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    @GetMapping
    public ExpenseListResponse list(@AuthenticationPrincipal Jwt jwt) {
        return new ExpenseListResponse(service.list(household(jwt)));
    }

    @PostMapping
    public ResponseEntity<ExpenseResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ExpenseRequest body) {
        var result = service.save(household(jwt), body, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.expense());
    }

    @PatchMapping("/{id}/settlement")
    public ExpenseResponse settlement(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody SettlementRequest body) {
        return service.setSettled(household(jwt), id, body.settled())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gasto no encontrado"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!service.delete(household(jwt), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gasto no encontrado");
        }
        return ResponseEntity.noContent().build();
    }

    private static UUID household(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("hid"));
    }
}
