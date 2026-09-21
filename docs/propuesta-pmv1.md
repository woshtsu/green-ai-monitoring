# Monitoring PMV1: inspección y propuesta

Fecha: 2026-09-21. Estado: dirección aprobada con aclaraciones del usuario. Autorizados el incremento funcional del Grupo A y posteriormente el adaptador Prometheus según el plan revisado. El contrato sigue sujeto a revisión de integración con Data Processing.

## Inspección del punto de partida

- Se revisaron `../project-docs/2. Conocimiento de Ingenieria.md`, `../project-docs/4. Gestión de Proyecto.md`, el texto adjunto, `pom.xml` y los tres archivos de `src/`. Las imágenes embebidas de los documentos no se evaluaron visualmente.
- Repositorio Git propio en `green-ai-monitoring/.git`; estado inicial limpio. No se encontró AGENTS.md en el workspace. Las otras tres carpetas ya existen y no se modifican. No se crea repositorio padre.
- Coordenadas: `pe.edu.continental.greenai:monitoring-service:0.0.1-SNAPSHOT`.
- Parent Spring Boot **4.1.1**, objetivo Java **21**, Maven Wrapper **3.3.4** con distribución Maven **3.9.16**.
- Dependencias directas: starters `actuator`, `validation`, `webmvc`; `micrometer-registry-prometheus` en runtime; starters `actuator-test`, `validation-test`, `webmvc-test` en test. Las versiones se heredan del parent/BOM; se conservará esa gestión.
- Árbol resuelto con éxito mediante `mvnw.cmd dependency:tree`: Spring Framework 7.0.9, Micrometer 1.17.1, Jackson Databind 3.1.5 (`tools.jackson`), Tomcat 11.0.24, Hibernate Validator 9.1.3.Final y JUnit Jupiter 6.0.3. La implementación debe respetar las APIs de Boot 4/Jackson 3; no copiar supuestos de Boot 3/Jackson 2.
- Código: clase principal, `application.yaml` con nombre del servicio y prueba `contextLoads`. No hay endpoints funcionales, adaptadores ni Dockerfile.
- `mvn` no está en PATH. `mvnw.cmd -version` funciona con Oracle Java **24.0.2** y Maven **3.9.16**. Esto no demuestra portabilidad a Java 21.
- Docker Desktop **4.91.0**, cliente/motor **29.8.0**, contexto `desktop-linux`, motor `linux/amd64`: disponibles. El acceso al motor requiere salir del sandbox.
- `kubectl` está disponible; `kind` no se encontró en PATH. No se consultó ni modificó ningún clúster.
- La consulta de RAM por CIM fue denegada; memoria disponible y asignación a Docker pendientes.

Spring Boot 4.1.1 admite Java 17–26 y Maven desde 3.6.3 según sus [requisitos oficiales](https://docs.spring.io/spring-boot/system-requirements.html), consultados el 2026-09-21. Java 21 y Maven 3.9.16 satisfacen esos requisitos. No se propone cambiar el parent ni regenerar Initializr.

## Requisitos y diferencias documentales

| Base | Requisito documentado | Tratamiento en esta entrega |
| --- | --- | --- |
| Ingeniería: Observabilidad | Consulta actual e histórica y manejo de fuentes no disponibles | API REST y puerto de consulta de métricas |
| Ingeniería: arquitectura | Prometheus conserva series; responsabilidades separadas | Sin base de datos propia, ETL o entrenamiento |
| Gestión: PMV1 | CPU, memoria, red y potencia cada 15 s durante al menos 2 semanas | Objetivo del experimento integrado; las pruebas cortas no acreditan esas dos semanas |
| Ingeniería: sostenibilidad | CPU no equivale a energía; distinguir medición y estimación | Procedencia explícita; potencia pendiente de fuente validada |
| Gestión frente a Ingeniería | TimescaleDB en una sección, PostgreSQL/MinIO en otras; Prometheus para series | Equipo debe acordar almacenamiento de datos procesados; Monitoring consulta Prometheus |
| Gestión: seguridad | Registro de usuarios aparece en PMV1; hardening también en PMV3 | Instrucción actual limita a entorno autorizado, sin autenticación empresarial; no implementar gestión de usuarios |
| Arquitectura global | RabbitMQ y numerosos servicios | Fuera del incremento autorizado; mensajería aparece en PMV2 |
| Gestión: baseline | ARIMA/media móvil frente a naive/persistencia en otras secciones | Acuerdo pendiente de los responsables de Prediction; no decidir por ellos |
| Gestión: infraestructura | Se propone un clúster de al menos 3 nodos | No implica 3 servidores físicos; validar RAM y laboratorio antes de Kind |

La condición documental «sin pérdida» debe medirse, no lograrse rellenando huecos con cero. Los intervalos ausentes se preservarán como tales.

## Incrementos verificables

1. **Contrato y reglas (aprobado):** incorporar aclaraciones y activar `AGENTS.md` en la raíz como única versión normativa.
2. **Núcleo y adaptador de prueba (autorizado):** dominio Java puro, casos de uso, puertos de entrada/salida y fixture determinista con perfil explícito. Implementar Grupo A: CPU, memoria, red y almacenamiento por filesystem. Reloj inyectable, API MVC, límites, errores, OpenAPI estático y pruebas unitarias, HTTP y de contrato.
3. **Adaptador Prometheus (autorizado por mensaje posterior):** consultas controladas, límites de lectura y timeout HTTP. URL explícita, default-cluster configurable, origen unknown salvo etiqueta/configuración confiable, sin recuperación silenciosa con fixtures. Las decisiones consolidadas y el siguiente punto de integración están en `integracion-simulator.md`.
4. **Empaquetado (posterior):** no crear Dockerfile hasta terminar y validar el incremento funcional. Luego JDK 21 para compilar, JRE 21 para ejecutar e imágenes fijadas por digest. Mientras tanto pueden ejecutarse pruebas con JDK 21 en un contenedor efímero, sin Dockerfile ni imagen de aplicación.

El Grupo B (solicitudes, throughput, concurrencia, latencia, errores disponibles, pods, réplicas y estado de workloads) pertenece al PMV1 integrado y depende de instrumentación. El incremento de Grupo A no completa PMV1. No declarar cierre mientras falten almacenamiento o métricas de workloads requeridas.

El cierre exige tests unitarios/integración/contrato bajo Java 21 en contenedor y pruebas HTTP de la imagen final con un cliente también en contenedor. Registrar versiones, digest, comandos, resultados y limitaciones. Un build local con Java 24 no sustituye esa evidencia. No publicar imágenes.

Gateway, simulador, Kustomize y despliegue integrado quedan para incrementos posteriores. Una prueba a través del Gateway permanece pendiente hasta su incremento autorizado.

## Decisiones propuestas

- **ADR-001 — Java y framework:** conservar Boot 4.1.1 y `java.version=21`; verificar `release=21` efectivo, sin APIs preview. Java 24 del host solo como herramienta auxiliar.
- **ADR-002 — Hexagonal:** `domain`, `application` (casos de uso y puertos), `adapter.in.web`, `adapter.out.fixture`, `adapter.out.prometheus`, `configuration`. Dominio y casos de uso sin dependencias Spring/HTTP/Prometheus; Spring conecta componentes.
- **ADR-003 — Fuente y persistencia:** Prometheus externo conserva históricos; Monitoring es fachada de lectura controlada. Retención, volúmenes y respaldo se acuerdan en despliegue; no se incluyen en la imagen.
- **ADR-004 — Contrato:** propuesta v0.1 para revisión con Data Processing; sin endpoints de sus servicios. OpenAPI versionado y ejemplos serán artefactos del repositorio; el contrato textual adjunto define esta primera revisión.
- **ADR-005 — Configuración:** URL de Prometheus por entorno, validada al arrancar. En Kubernetes usar DNS `servicio.namespace.svc.cluster.local`; namespace y nombre reales pendientes. Sin IP de PC ni localhost entre pods.

## Información pendiente

- RAM total/disponible y memoria asignada a Docker; arquitectura, sistema, Docker/Kubernetes y permisos del laboratorio.
- Exporters disponibles, etiquetas de clúster/nodo, interfaces de red incluidas y frecuencia de scrape real.
- Inventario real y mecanismo de medición de potencia. No se necesitan para desarrollar fixtures inequívocamente sintéticos.
- Acuerdo con Data Processing sobre identidad de recursos, catálogo, límites y semántica temporal.

## Evidencias y estado

Inspección de archivos, Java, Wrapper y Docker ejecutada. La resolución offline del árbol de dependencias falló porque faltaba el plugin; el primer intento online fue bloqueado por permisos de escritura en `.m2`, no por incompatibilidad del proyecto. La repetición autorizada fuera del sandbox terminó con BUILD SUCCESS (solo resolución de dependencias, no pruebas). Árbol completo en `target/inspection-dependencies.txt`, archivo temporal no versionado. `git diff --exit-code -- pom.xml src .mvn` confirmó que la base permanece intacta; únicamente se agregaron propuestas en `docs/`.

El registro anterior corresponde a la inspección inicial. La aprobación posterior habilita el incremento funcional y sus pruebas; resultados de esta entrega se registran en `validacion-incremento-1.md`. No se declara PMV1 completado.

## Comunicación y propiedad de datos acordadas

Gateway atiende tráfico externo (norte-sur). Frontend → Gateway → Monitoring. Data Processing consulta directamente Monitoring → Prometheus y llama a Prediction por REST. En Kubernetes se permite HTTP directo por DNS interno; no es obligatorio atravesar Gateway. Monitoring no depende ni llama a Gateway, Data Processing o Prediction. RabbitMQ/METRICS_AVAILABLE queda para una etapa posterior.

Frontend no accede directamente a microservicios ni Supabase. Gateway no ejecuta SQL ni posee credenciales Supabase. Las rutas internas `/api/v1/metrics/*` son distintas de las externas propuestas `/api/monitoring/v1/metrics/*`; el Gateway futuro realizará la reescritura.

Monitoring consulta el API HTTP de Prometheus; Prometheus recolecta las métricas técnicas de Monitoring en `/actuator/prometheus`. Son interacciones distintas. JVM/HTTP/proceso Java describen Monitoring, no el centro de datos.

Existe Supabase/PostgreSQL real. Los nombres inferidos del prototipo son:

- `usuario`: usuario_id, nombre_completo, email, password, password_hash, rol, fecha_creacion.
- `hardware`: hardware_id, hostname, ip_address, cpu_cores, ram_gb, max_watts, estado, usuario_id.
- `logs`: hardware_id, timestamp, cpu_utilization_pct, ram_utilization_pct, temperatura_celsius, energia_watts.

DDL, tipos, claves, restricciones e índices están pendientes de confirmación; no se infieren migraciones. `hardware` y `logs` contienen datos reales, tratados como observed aunque su procedimiento/procedencia aún deben documentarse. El nombre `energia_watts` requiere aclaración: watts es potencia, no energía.

Monitoring y Gateway no acceden a Supabase. Simulator podrá leer hardware; Data Processing podrá leer logs; Prediction recibe datos preparados y solo tendrá tablas propias si se acuerdan. Usuario queda fuera de este incremento. Cada servicio tendrá permisos mínimos por datos, incluso compartiendo instancia física. No añadir JPA/JDBC ni credenciales. Las credenciales publicadas están comprometidas: no se usan ni se prueban; rotación y limpieza se gestionan aparte.

El simulador futuro será otro repositorio/servicio: inventario real, escenarios baja/media/alta/pico, simulación lógica separada de carga real. Fixtures pequeños de Monitoring solo prueban dominio/contrato; no son ese simulador ni se mezclan con logs reales. Preservar observed/simulated/estimated/unknown.
