# Green AI — Monitoring Service

Primer incremento funcional del Grupo A para PMV1: dominio y casos de uso Java puros, API MVC y fixtures deterministas. **No es el PMV1 completo**: faltan la integración real con Prometheus y las métricas de workloads del Grupo B, entre otros componentes del experimento integrado.

Spring Boot 4.1.1 · Java objetivo 21 · Maven Wrapper 3.9.16. Maven puede ejecutarse con Java 24 en el host; las pruebas de portabilidad usan Java 21 en contenedor. Sin adaptador Supabase, JPA/JDBC, Gateway, simulador, autenticación, Kubernetes ni Dockerfile.

## Ejecutar

En PowerShell, desde este repositorio:

```powershell
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=fixture' '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

El perfil **fixture debe activarse expresamente**. Sin él, el catálogo funciona y current/history responden 503 `METRICS_SOURCE_UNAVAILABLE`. No se cargan fixtures como fallback. El servicio no llama a Prometheus todavía.

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/metrics/catalog'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/metrics/current?metric=node.cpu.utilization&resourceType=node&cluster=fixture-lab'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/metrics/history?metric=node.filesystem.used&resourceType=node&cluster=fixture-lab&resourceId=fixture-node-01&start=2026-09-21T15:00:00Z&end=2026-09-21T15:00:45Z&stepSeconds=15'
```

El ejemplo histórico debe estar en el pasado respecto al reloj de ejecución. Fixtures: clúster `fixture-lab`, nodos `fixture-node-01` y `fixture-node-02`; interfaces eth0/eth1; filesystems `/` ext4 y `/data` xfs, con devices ficticios. En el nodo 01, cada minuto tiene fases de 15 s: cero válido, ausencia, NaN y valor base. Nodo 02 mantiene valores base. Son datos pequeños de prueba, no mediciones ni simulador. Todos llevan `origin=simulated`, `source=fixture`.

## Contrato

- `GET /api/v1/metrics/catalog`: cinco definiciones del Grupo A, unidades, denominadores, etiquetas y ventanas.
- `GET /api/v1/metrics/current`: métrica obligatoria; filtros opcionales resourceType (node), cluster y resourceId.
- `GET /api/v1/metrics/history`: además start/end RFC3339 con zona y segundos enteros; stepSeconds por defecto 15.
- OpenAPI estático: `GET /openapi/monitoring-v0.1.json`; fuente versionada en [monitoring-v0.1.json](src/main/resources/static/openapi/monitoring-v0.1.json).
- Scraping técnico: `/actuator/prometheus`; salud del proceso: `/actuator/health/liveness` y `/actuator/health/readiness`. La salud de ciclo de vida no certifica disponibilidad de datos; en modo sin fuente las consultas dan 503.

Rutas internas; el futuro Gateway expondrá otro prefijo. Frontend solo usa Gateway; Data Processing puede consultar directamente Monitoring. No hay llamadas desde Monitoring a esos componentes ni a Supabase.

Reglas y límites completos: [contrato v0.1](docs/contrato-monitoring-v0.1.md). Rechazo de parámetros desconocidos/repetidos, sin PromQL del cliente. UTC, múltiples series, cero distinto de null, problemas JSON y X-Request-Id generado por solicitud. Máximo 24 h, 100 series, 10 000 puntos incluyendo huecos, respuesta 5 MiB, consulta de fuente 5 s. Máximo 16 consultas simultáneas a la fuente, sin cola ni reintentos; saturación da 503. No hay autenticación ni CORS abierto; uso en desarrollo autorizado.

## Arquitectura

```text
adapter/in/web -> application/port/in <- application/MonitoringService
                                             |
                                  application/port/out/MetricsSource
                                             ^
                   adapter/out/fixture o adapter/out/support (sin fuente)
```

`domain`: catálogo, consulta validada, series/calidad y fallos tipados; sin Spring ni Jackson. `application`: reloj inyectable, filtros, presupuesto y normalización de huecos; sin red. `configuration`: conecta beans/perfiles. El adaptador de soporte acota tiempo y concurrencia. Web valida sintaxis HTTP, limita serialización, añade request ID y traduce fallos a `application/problem+json`.

## Verificación

```powershell
.\mvnw.cmd -B -ntp test
.\scripts\Test-Java21.ps1
```

El script requiere Docker con motor Linux e Internet para Maven Central. Usa una imagen JDK 21 fijada por digest, monta las fuentes de solo lectura y copia exclusivamente pom, wrapper y src a un directorio temporal dentro del contenedor. Ejecuta `verify`, incluidas pruebas HTTP con servidor real y cliente dentro del contenedor; exporta informes y JAR a `target/java21-evidence/`. No usa la caché/credenciales Maven del host, no publica puertos ni crea imagen de aplicación, clústeres o volúmenes persistentes. Contenedor limitado a 2 CPU/2 GiB y eliminado al terminar.

OpenAPI es estático y no necesita springdoc. Única dependencia añadida: `com.networknt:json-schema-validator:3.0.7`, scope test, para comprobar respuestas contra JSON Schema 2020-12 del contrato. La [documentación oficial de NetworkNT](https://github.com/networknt/json-schema-validator) declara compatibilidad de la línea 3.x con Java 17+ y Jackson 3; el BOM Boot mantiene Jackson 3.1.5. No hay dependencia adicional en runtime.

Resultados y pendientes: [validación del incremento](docs/validacion-incremento-1.md). Arquitectura general y decisiones: [propuesta PMV1](docs/propuesta-pmv1.md). Reglas vigentes: [AGENTS.md](AGENTS.md).
