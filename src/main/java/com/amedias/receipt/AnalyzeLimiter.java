package com.amedias.receipt;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Protege la cuota de IA: una lectura a la vez y un máximo por minuto para cada hogar. */
@Component
public class AnalyzeLimiter {

    private static final int MAX_PER_MINUTE = 10;
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    private static final class State {
        final Deque<Long> calls = new ArrayDeque<>();
        boolean busy;
    }

    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

    /** Reserva un turno; el llamador debe invocar {@link #release} en un finally. */
    public void acquire(UUID household) {
        State s = states.computeIfAbsent(household, k -> new State());
        synchronized (s) {
            long now = System.nanoTime();
            while (!s.calls.isEmpty() && s.calls.peekFirst() < now - WINDOW_NANOS) {
                s.calls.pollFirst();
            }
            if (s.busy || s.calls.size() >= MAX_PER_MINUTE) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Espera a que termine la lectura anterior");
            }
            s.busy = true;
            s.calls.addLast(now);
        }
    }

    public void release(UUID household) {
        State s = states.get(household);
        if (s != null) {
            synchronized (s) {
                s.busy = false;
            }
        }
    }
}
