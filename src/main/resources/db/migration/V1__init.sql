-- A medias · esquema inicial (PostgreSQL). Importes siempre en céntimos (bigint).

create table hogares (
    id         uuid primary key default gen_random_uuid(),
    nombre     text not null,
    creado_en  timestamptz not null default now()
);

-- Identidades fijas de la pareja (ids estables: 'adela', 'martin').
create table participantes (
    id              varchar(32) primary key,
    hogar_id        uuid not null references hogares(id) on delete cascade,
    nombre_mostrado varchar(60) not null,
    posicion        smallint not null,
    unique (hogar_id, posicion)
);

create table usuarios (
    id              uuid primary key default gen_random_uuid(),
    hogar_id        uuid not null references hogares(id) on delete cascade,
    participante_id varchar(32) not null references participantes(id),
    email           text not null,
    contrasena_hash text not null,
    creado_en       timestamptz not null default now()
);
create unique index ux_usuarios_email on usuarios (lower(email));

create table gastos (
    id                 uuid primary key default gen_random_uuid(),
    hogar_id           uuid not null references hogares(id) on delete cascade,
    comercio           varchar(120) not null,
    fecha_compra       date not null,
    moneda             char(3) not null check (moneda in ('EUR', 'USD', 'GBP')),
    modo_reparto       varchar(10) not null check (modo_reparto in ('igual', 'ponderado')),
    total_centimos     bigint not null check (total_centimos > 0),  -- suma de las líneas seleccionadas
    registrado_por_id  varchar(32) not null references participantes(id),
    pagado_por_id      varchar(32) not null references participantes(id),
    liquidado_en       timestamptz,                                  -- null = pendiente
    clave_idempotencia varchar(128) not null,
    huella_solicitud   char(64) not null,                            -- SHA-256 de la solicitud normalizada
    creado_en          timestamptz not null default now(),
    unique (hogar_id, clave_idempotencia)
);
create index ix_gastos_hogar_creado on gastos (hogar_id, creado_en desc);

-- Todas las líneas del ticket se guardan, también las no seleccionadas.
-- Los descuentos son líneas negativas. propietario_id null = compartida.
create table lineas_gasto (
    gasto_id         uuid not null references gastos(id) on delete cascade,
    id_linea         varchar(100) not null,                -- id que envía el cliente, se conserva tal cual
    posicion         int not null,
    nombre           varchar(120) not null,
    importe_centimos bigint not null check (importe_centimos between -100000000 and 100000000),
    seleccionada     boolean not null,
    propietario_id   varchar(32) references participantes(id),
    primary key (gasto_id, id_linea)
);
create index ix_lineas_gasto_orden on lineas_gasto (gasto_id, posicion);

-- Participantes del reparto de cada gasto, en el orden recibido (desempata los céntimos sobrantes).
create table participantes_gasto (
    gasto_id        uuid not null references gastos(id) on delete cascade,
    participante_id varchar(32) not null references participantes(id),
    nombre          varchar(60) not null,
    peso            int not null check (peso between 1 and 10000),
    posicion        smallint not null,
    primary key (gasto_id, participante_id)
);

-- Cuota final calculada en el servidor (parte común + productos personales).
create table cuotas_gasto (
    gasto_id        uuid not null,
    participante_id varchar(32) not null,
    cuota_centimos  bigint not null check (cuota_centimos >= 0),
    primary key (gasto_id, participante_id),
    foreign key (gasto_id, participante_id) references participantes_gasto (gasto_id, participante_id) on delete cascade
);
