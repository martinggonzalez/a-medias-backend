package com.amedias.config;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Crea la unidad doméstica, las dos identidades fijas y sus cuentas si no existen. */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public DataInitializer(JdbcClient jdbc, PasswordEncoder encoder, AppProperties props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        UUID household = jdbc.sql("select id from hogares order by creado_en limit 1")
                .query(UUID.class).optional().orElse(null);
        if (household == null) {
            household = UUID.randomUUID();
            jdbc.sql("insert into hogares (id, nombre) values (:id, 'Casa')").param("id", household).update();
            jdbc.sql("insert into participantes (id, hogar_id, nombre_mostrado, posicion) values ('adela', :h, 'Adela', 1)")
                    .param("h", household).update();
            jdbc.sql("insert into participantes (id, hogar_id, nombre_mostrado, posicion) values ('martin', :h, 'Martín', 2)")
                    .param("h", household).update();
            log.info("Unidad doméstica creada");
        }
        var seed = props.seed();
        createUserIfMissing(household, "adela", seed.adelaEmail(), seed.adelaPassword());
        createUserIfMissing(household, "martin", seed.martinEmail(), seed.martinPassword());
    }

    private void createUserIfMissing(UUID household, String participantId, String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        boolean exists = jdbc.sql("select count(*) from usuarios where lower(email) = lower(:e)")
                .param("e", email).query(Long.class).single() > 0;
        if (exists) {
            return;
        }
        jdbc.sql("insert into usuarios (hogar_id, participante_id, email, contrasena_hash) values (:h, :p, :e, :x)")
                .param("h", household).param("p", participantId).param("e", email.trim())
                .param("x", encoder.encode(password)).update();
        log.info("Usuario creado para {}", participantId);
    }
}
