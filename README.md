# Green AI — Monitoring Service

Monitoring implementa el Grupo A para PMV1: dominio y casos de uso Java puros, API MVC, fixtures deterministas y adaptador HTTP de Prometheus. El cierre técnico incluye una prueba reproducible con Prometheus/Node Exporter reales en Docker. **No es el PMV1 completo**: faltan la validación del entorno del laboratorio y las métricas de workloads del Grupo B, entre otros componentes del experimento integrado.

Spring Boot 4.1.1 · Java objetivo 21 · Maven Wrapper 3.9.16. Maven puede ejecutarse con Java 24 en el host; la aplicación se compila y ejecuta con Java 21 en Docker. Monitoring no incorpora adaptadores Supabase, JPA/JDBC, autenticación o Kubernetes.

## Ejecutar

En PowerShell, desde este repositorio:

```powershell
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=fixture' '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

El perfil **fixture debe activarse expresamente** y tiene prioridad sobre la URL de Prometheus. Sin él, Monitoring usa Prometheus solo cuando `monitoring.prometheus.url` está configurado. Sin URL, el catálogo funciona y current/history responden 503 `METRICS_SOURCE_UNAVAILABLE`. No se cargan fixtures como fallback.

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

Estas son rutas internas. El Gateway publica las mismas operaciones bajo `/api/monitoring/v1/metrics/*` y reescribe el prefijo; el Frontend nunca utiliza estas rutas internas. Data Processing sí consulta directamente Monitoring mediante DNS interno, porque sus pipelines no deben volver a entrar por el Gateway. Monitoring no llama a Gateway, Data Processing, Prediction o Supabase.

## Uso desde otros servicios

Monitoring proporciona JSON, no DataFrames ni textos de presentación. Conserva unidades, timestamps UTC, identidades, procedencia y calidad. Cada consumidor decide su transformación sin alterar el significado original:

| Consumidor | URL | Responsabilidad |
| --- | --- | --- |
| Frontend | Gateway `/api/monitoring/v1/metrics/*` | Visualización y formato humano |
| Data Processing | Monitoring `/api/v1/metrics/*` | Limpieza, alineación, agregación y features |
| Gateway | Monitoring `/api/v1/metrics/*` | Proxy y reescritura, sin análisis |

Data Processing debe tratar `value:null` según `quality=missing|non_finite`; nunca convertirlo automáticamente en cero. Debe preservar `origin`, distinguir `no_data` de errores upstream y no mezclar series de red/filesystem sin una política documentada. Para rangos mayores de 24 horas dividirá las consultas respetando que los extremos son inclusivos. El cliente no envía PromQL ni filtros arbitrarios.

Petición desde la red interna:

```http
GET http://monitoring:8080/api/v1/metrics/history?metric=node.cpu.utilization&resourceType=node&cluster=sim-run-123&resourceId=node-01&start=2026-09-21T15:00:00Z&end=2026-09-21T16:00:00Z&stepSeconds=15
```

Petición equivalente del Frontend mediante Gateway:

```http
GET http://gateway:8081/api/monitoring/v1/metrics/history?metric=node.cpu.utilization&resourceType=node&cluster=sim-run-123&resourceId=node-01&start=2026-09-21T15:00:00Z&end=2026-09-21T16:00:00Z&stepSeconds=15
```

Reglas y límites completos: [contrato v0.1](docs/contrato-monitoring-v0.1.md). Rechazo de parámetros desconocidos/repetidos, sin PromQL del cliente. UTC, múltiples series, cero distinto de null, problemas JSON y X-Request-Id generado por solicitud. Máximo 24 h, 100 series, 10 000 puntos incluyendo huecos, respuesta 5 MiB, consulta de fuente 5 s. Máximo 16 consultas simultáneas a la fuente, sin cola ni reintentos; saturación da 503. No hay autenticación ni CORS abierto; uso en desarrollo autorizado.

## Arquitectura

```text
adapter/in/web -> application/port/in <- application/MonitoringService
                                             |
                                  application/port/out/MetricsSource
                                             ^
             adapter/out/fixture | adapter/out/prometheus | sin fuente
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

## Configurar Prometheus

Ejemplo local, si el operador ya dispone de Prometheus en ese puerto:

```powershell
$env:MONITORING_PROMETHEUS_URL = 'http://127.0.0.1:9090'
$env:MONITORING_PROMETHEUS_DEFAULT_CLUSTER = 'lab-01'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

En Kubernetes se configurará DNS interno, por ejemplo `http://prometheus.observability.svc.cluster.local:9090`, sujeto a nombres/namespaces reales. No usar localhost entre pods. No se desplegó Kubernetes en esta entrega.

| Propiedad (`monitoring.prometheus.*`) | Valor por defecto | Uso |
| --- | --- | --- |
| `url` | Ausente | Activa Prometheus; HTTP(S), sin credenciales/query/fragmento; puede incluir prefijo de ruta |
| `default-cluster` | `lab-01` | Clúster para series sin etiqueta; sobreescribible con `MONITORING_PROMETHEUS_DEFAULT_CLUSTER` |
| `cluster-label` | `cluster` | Etiqueta de clúster; su valor real tiene prioridad sobre default-cluster |
| `instance-label` | `instance` | Identidad exacta del nodo, incluyendo puerto; usar una etiqueta estable `node` si existe |
| `origin-label` | `origin` | Procedencia: observed, simulated, estimated o unknown |
| `default-origin` | `unknown` | Solo cambiar si el operador acredita la procedencia de todas las series sin etiqueta |

Para un Prometheus de múltiples clústeres, cada serie debe tener etiqueta de clúster correcta. `external_labels` por sí sola no añade esa etiqueta a las consultas locales: usar labels o relabel_configs del scrape. El filtro por default-cluster incluye series sin etiqueta y series que lo declaran; otros filtros son exactos. Si ambas fuentes producen identidades duplicadas, la consulta da 502 y exige corregir el etiquetado, sin fusionarlas.

No se elimina el puerto de instance. Si instance contiene caracteres fuera del contrato actual (por ejemplo IPv6 con corchetes), configurar una etiqueta de nodo estable y compatible. CPU agrupa por clúster, recurso y origen; red y almacenamiento preservan sus dimensiones. Filesystem usa size - free y requiere device_error=0; excluye los tipos/montajes documentados. Falta de métricas produce no_data, no cero ni fixture.

HttpClient JDK 21, sin nuevas dependencias runtime. Consultas PromQL cerradas, `/api/v1/query` y `/api/v1/query_range`, sin redirecciones ni reintentos. Conexión 1 s, evaluación 3 s, plazo HTTP 3 s incluyendo cuerpo, presupuesto externo 5 s. Lectura limitada a 5 MiB, `limit=101` para detectar exceso de 100 series, `lookback_delta=30s`. La frecuencia de scrape debe permitir la ventana rate de 60 s; acordarla con el laboratorio. Mensajes internos del upstream se reemplazan por avisos/errores controlados.

Las [decisiones consolidadas y el punto de integración del simulador](docs/integracion-simulator.md) registran la identidad, procedencia, métricas y reloj que espera Monitoring.

## Cierre con Prometheus real

```powershell
.\scripts\Test-Prometheus.ps1
```

PowerShell 7 y Docker Linux. Recompila y ejecuta la suite con Java 21 en contenedor; después arranca el JAR, Prometheus 3.13.3 LTS y Node Exporter 1.12.1, todos fijados por digest, en una red temporal. El cliente de prueba también corre con Java 21 dentro de Docker. Verifica las cinco consultas current/history, filtros, scrape técnico y 503 al detener la fuente. No publica puertos, no monta la raíz del host, no usa Dockerfile ni clústeres/volúmenes existentes. El historial del test vive en tmpfs y solo se eliminan contenedores/red identificados como propios de esa ejecución.

Evidencias en `target/prometheus-smoke/<run-id>/`. Las observaciones describen el Linux visible para Node Exporter dentro de Docker; no constituyen inventario ni mediciones de los equipos universitarios o del host Windows. Scrape de prueba 5 s; el periodo real se acordará en el laboratorio.

## Imagen de aplicación

El Dockerfile compila y ejecuta con Java 21, sin depender del JDK ni de un JAR precompilado del host. La etapa final utiliza un usuario sin privilegios:

```powershell
docker build -t green-ai-monitoring .
docker run --rm -p 8080:8080 -e MONITORING_PROMETHEUS_URL=http://host.docker.internal:9090 green-ai-monitoring
```

En Docker Compose debe usarse el nombre del servicio Prometheus en lugar de `host.docker.internal`.

## Modelo de base de datos

El [modelo de BD](docs/modelo-bd.md) incluye el diagrama recibido el 2026-09-22, las tres tablas completas y las correspondencias de identidad, unidades y procedencia. Documenta los límites actuales y los requisitos del futuro adaptador de inventario.
