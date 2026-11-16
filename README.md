# A medias · API (Spring Boot)

Backend del frontend `a-medias-next`, implementado según su `docs/CONTRATO-API.md`
(importes en céntimos, `selected`, `participants`, `splitMode`, `shares`, idempotencia con 409…).
Postgres + Flyway + JdbcClient. Base de datos en español.

## Arranque local

```
docker compose up -d                  # Postgres local
export JWT_SECRET="cadena-aleatoria-de-32-o-mas-caracteres"
export ADELA_EMAIL=... ADELA_PASSWORD=... MARTIN_EMAIL=... MARTIN_PASSWORD=...
export GEMINI_API_KEY=...             # solo para leer tickets
mvn spring-boot:run
```

Spring no lee `.env` por sí solo: exporta las variables en la misma terminal (o ponlas en tu IDE).
Al arrancar, Flyway crea las tablas (`hogares`, `participantes`, `usuarios`, `gastos`, `lineas_gasto`,
`participantes_gasto`, `cuotas_gasto`) y se crean las identidades `adela` y `martin` con sus cuentas.
No hay registro público.

## Endpoints

| Método | Ruta | Respuesta |
|---|---|---|
| POST | `/api/auth/login` | 200 `{accessToken, expiresIn}` · 401 · 429 tras demasiados intentos |
| POST | `/api/receipts/analyze` | 200 `{merchant,date,currency,totalCents,items[{name,amountCents}],warnings}` · 413/415/422/429/502/503 |
| GET | `/api/expenses` | 200 `{items:[…]}`, máx. 500, por `createdAt` descendente |
| POST | `/api/expenses` | 201, o 200 si es la misma operación · 400 · 409 si la clave se reutiliza con otro cuerpo |
| PATCH | `/api/expenses/{id}/settlement` | 200 con el gasto completo (`settledAt` o `null`) · 404 |
| DELETE | `/api/expenses/{id}` | 204 · 404 |

- El reparto se recalcula siempre en el servidor con el algoritmo del contrato (`SplitCalculator`);
  el cliente nunca aporta totales ni cuotas.
- `Idempotency-Key` es obligatoria en `POST /api/expenses`. Se guarda con un hash SHA-256 de la solicitud
  normalizada y una restricción única `(hogar, clave)`.
- Se validan las identidades fijas (`adela`/Adela, `martin`/Martín), límites (100 líneas, 120/60 caracteres,
  ±100.000.000 céntimos por línea, pesos 1–10000) y propietarios incluso en líneas no seleccionadas.
- La foto se valida por su firma real y dimensiones (≤ 12000 px por lado), no se guarda y solo se envía a Gemini.
  Una lectura a la vez y máximo 10 por minuto por hogar.
- JWT HS256 con emisor y audiencia verificados; `expiresIn` ≤ 86400 s (`JWT_TTL_MINUTES`, 1–1440).
- Jackson estricto: `"true"` no vale como booleano ni `12.5` como entero de céntimos.

## Despliegue

1. **Neon**: URL JDBC `jdbc:postgresql://<host>/<db>?sslmode=require`. Usa el host **directo** (sin `-pooler`) para Flyway.
2. **Render** (Web Service, Docker): `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET`,
   `FRONTEND_ORIGINS` (URL exacta de Vercel), `ADELA_EMAIL/PASSWORD`, `MARTIN_EMAIL/PASSWORD`,
   `GEMINI_API_KEY`, `GEMINI_MODEL`. Health check: `/actuator/health`.
3. **Vercel**: `NEXT_PUBLIC_DEMO_MODE=false` y `NEXT_PUBLIC_API_URL=https://<servicio>.onrender.com` (sin barra final) y redeploy.

## Límites conocidos

- Los límites de intentos de login y de lecturas IA están en memoria: valen para una sola instancia (Render free).
- Sin auditoría de cambios ni control de concurrencia entre dos sesiones sobre el mismo ticket (el contrato lo
  pide para producción); la liquidación es idempotente por estado.
- `GEMINI_MODEL` cambia con el tiempo: comprueba el modelo vigente en AI Studio.
