package com.amedias.auth;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Límite de intentos fallidos de login en memoria (una sola instancia): por IP y por email. */
@Component
public class LoginThrottle {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_PER_IP = 10;
    private static final int MAX_PER_EMAIL = 20;

    private final ConcurrentHashMap<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public void check(String ip, String email) {
        if (count("ip:" + ip) >= MAX_PER_IP || count("email:" + email) >= MAX_PER_EMAIL) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos. Espera unos minutos");
        }
    }

    public void recordFailure(String ip, String email) {
        add("ip:" + ip);
        add("email:" + email);
    }

    public void reset(String email) {
        failures.remove("email:" + email);
    }

    private int count(String key) {
        Deque<Long> d = failures.get(key);
        if (d == null) {
            return 0;
        }
        synchronized (d) {
            prune(d);
            return d.size();
        }
    }

    private void add(String key) {
        if (failures.size() > 10_000) { // evita crecimiento sin límite
            failures.clear();
        }
        Deque<Long> d = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            prune(d);
            d.addLast(System.nanoTime());
        }
    }

    private static void prune(Deque<Long> d) {
        long limit = System.nanoTime() - WINDOW.toNanos();
        while (!d.isEmpty() && d.peekFirst() < limit) {
            d.pollFirst();
        }
    }
}
