package com.amedias.auth;

import com.amedias.config.AppProperties;
import com.amedias.config.SecurityConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    /** Contrato: solo {accessToken, expiresIn}; expiresIn en segundos (1..86400). */
    public record LoginResponse(String accessToken, long expiresIn) {}

    private record UserRow(UUID id, UUID householdId, String participantId, String hash) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwtEncoder;
    private final AppProperties props;
    private final LoginThrottle throttle;
    // Hash de relleno para que el tiempo de respuesta no delate si el email existe.
    private final String dummyHash;

    public AuthController(JdbcClient jdbc, PasswordEncoder encoder, JwtEncoder jwtEncoder, AppProperties props,
                          LoginThrottle throttle) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.throttle = throttle;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String email = req.email().trim().toLowerCase(java.util.Locale.ROOT);
        String ip = http.getRemoteAddr();
        throttle.check(ip, email);

        UserRow user = jdbc.sql("""
                select id, hogar_id, participante_id, contrasena_hash
                from usuarios where lower(email) = :e""")
                .param("e", email)
                .query((rs, i) -> new UserRow(rs.getObject("id", UUID.class), rs.getObject("hogar_id", UUID.class),
                        rs.getString("participante_id"), rs.getString("contrasena_hash")))
                .optional().orElse(null);

        boolean ok = encoder.matches(req.password(), user != null ? user.hash() : dummyHash);
        if (user == null || !ok) {
            throttle.recordFailure(ip, email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales no válidas");
        }
        throttle.reset(email);

        Duration ttl = Duration.ofMinutes(props.jwtTtlMinutes());
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(SecurityConfig.ISSUER)
                .audience(List.of(SecurityConfig.AUDIENCE))
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("hid", user.householdId().toString())
                .claim("pid", user.participantId())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new LoginResponse(token, ttl.toSeconds());
    }
}
