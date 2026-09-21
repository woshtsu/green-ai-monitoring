# Validación — Monitoring Grupo A y adaptador Prometheus

Fecha: 2026-09-21. Alcance: incremento funcional inicial, segmento Prometheus y arreglos finales previos al simulador. Las decisiones del plan se consolidaron en `integracion-simulator.md`. No es cierre del PMV1 integrado.

## Cambios entregados

- AGENTS.md activo en raíz; la propuesta duplicada se retiró. Documentos de alcance/contrato actualizados con flujos externos/internos y propiedad de datos.
- Dominio Java puro y puertos de entrada/salida; casos de uso con reloj inyectable y límites. Controlador MVC y respuestas problem+json con X-Request-Id.
- Grupo A: CPU, memoria, red recibida/transmitida y bytes usados de filesystem (device/mountpoint/fstype). Grupo B sigue pendiente.
- Adaptador fixture determinista solo con perfil explícito. Sin fuente configurada, catálogo disponible y consultas 503.
- Adaptador Prometheus dividido en constructor de consultas, cliente HTTP acotado, mapper y orquestación. Sin dependencias runtime adicionales.
- URL explícita y propiedades validadas; clúster por defecto lab-01 sobreescribible; instance completo; origen desconocido por defecto. Fixture tiene prioridad, sin recuperación silenciosa.
- OpenAPI 3.1 estático versionado y pruebas JSON Schema 2020-12 sobre respuestas HTTP. NetworkNT 3.0.7 solo en test.

## Pruebas ejecutadas

| Comprobación | Resultado |
| --- | --- |
| Inspección Initializr, dependencias y versiones | Boot 4.1.1, Maven 3.9.16, objetivo Java 21 |
| Incremento funcional inicial, `mvnw.cmd -B -ntp test` | 62 pruebas, 0 fallos/errores/omitidas, Java 24.0.2 host con release 21 |
| Grupo A + Prometheus, `mvnw.cmd -B -ntp verify` | 112 pruebas, 0 fallos/errores/omitidas, Java 24.0.2 host con release 21; JAR empaquetado |
| Java 21 en contenedor, `scripts/Test-Java21.ps1` | **112 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS**. Temurin 21.0.12+8, Maven 3.9.16, Linux amd64; compilación release 21 desde fuentes y JAR empaquetado. Finalizó 2026-09-21T19:55:45Z; Maven 49.770 s |

Cobertura funcional: catálogo, filtros exactos/tipo de recurso, rechazo de PromQL y parámetros repetidos/desconocidos, UTC y zonas, rejilla inclusiva, cero/hueco/no finito, ausencia total, identidades múltiples, filesystem, rango/puntos/cardinalidad, serialización acotada, timeout/concurrencia y errores.

Cobertura Prometheus: expresiones de las cinco métricas, clúster ausente/default/explícito, etiquetado configurable, instance con puerto, origen explícito/desconocido, vector/matrix, NaN/Inf, respuesta vacía, errores HTTP/JSON, redirección rechazada, conexión rechazada, cuerpo de 5 MiB y rechazo durante lectura chunked, timeout con cuerpo bloqueado después de cabeceras. Las pruebas no simulan un timeout de conexión TCP a una red externa; el connectTimeout=1s está configurado y se prueba conexión rechazada localmente.

Pruebas HTTP con servidor real en puerto efímero: perfil fixture, sin URL y adaptador Prometheus contra HttpServer JDK. Las pruebas de contrato no requieren Data Processing/Prediction/Gateway. Salud de proceso y scraping técnico verificados por separado.

## Evidencia local reproducible

- Host: `target/host-tests.log` (incremento inicial), `target/prometheus-tests.log`, `target/surefire-reports/`.
- Contenedor: `target/java21-evidence/container.log`, informes Surefire y JAR generado en ese contenedor.
- Imagen de herramientas: `eclipse-temurin:21-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438`.
- La compilación dentro del contenedor copia fuentes y wrapper, nunca target del host. Imagen de herramientas, no imagen final de aplicación. No se creó Dockerfile.
- Diff completo de trabajo (incluidos archivos nuevos) exportado a `target/monitoring-incremento.patch` para revisión. El incremento inicial ya estaba registrado por el usuario en Git al comenzar el segmento Prometheus; este diff parte del HEAD existente, sin modificar commits ni índice.

El primer verify del host necesitó descargar plugins de empaquetado y falló por permisos de caché Maven; la repetición autorizada completó correctamente. La corrección inicial de compilación en tests fue adaptar deepCopy al tipo de Jackson 3. No son fallos de portabilidad ocultados.

## Pendientes y límites de la evidencia

- Entorno universitario: validar inventario, nombres/etiquetas, retención, reloj y scrape reales del laboratorio. La prueba Docker descrita abajo ejecutó las cinco reglas PromQL con Prometheus real, pero no confirma instrumentación del laboratorio.
- Grupo B del PMV1: solicitudes, throughput, concurrencia, latencia/percentiles, errores disponibles, pods/réplicas/estado; fuentes de aplicación, kube-state-metrics y contenedores por confirmar.
- Potencia/energía: no se publican sin fuente y método validados. Los datos hardware/logs de Supabase siguen fuera del servicio.
- Revisión con Data Processing de rutas internas, IDs, límites, semántica temporal y futura evolución node/pod/service. Default-cluster no reemplaza el etiquetado correcto en instalaciones multiclúster.
- Dockerfile, imagen final JRE 21, ejecución en laboratorio, Gateway, simulador, Kubernetes, persistencia/backups externos y experimento de dos semanas siguen pendientes. El JAR ya arrancó con Java 21 en la prueba Docker descrita abajo. Sin publicación ni push.

No se garantiza rendimiento idéntico entre equipos. Ni health/readiness ni estas pruebas cortas acreditan continuidad de datos durante dos semanas o cierre de PMV1.

## Arreglos finales y prueba real (2026-09-21)

- `Test-Java21.ps1` normaliza LF en el comando Linux para soportar checkout CRLF en Windows.
- Nuevo `Test-Prometheus.ps1` con red temporal, nombres únicos, límites de recursos, fuentes de solo lectura y limpieza limitada a IDs/etiquetas de esa ejecución. Sin puertos publicados ni volúmenes/históricos existentes.
- Prometheus 3.13.3 LTS y Node Exporter 1.12.1, versiones verificadas en https://prometheus.io/download/ y fijadas por digest en el script. Node Exporter observa únicamente el Linux visible dentro de Docker; no representa inventario universitario ni el host Windows.
- Suite completa recompilada de nuevo bajo Java 21.0.12: **112 pruebas satisfactorias**, BUILD SUCCESS a 2026-09-21T20:05:44Z.
- El JAR empaquetado arrancó con Java 21 y consumió Prometheus real. **Pasaron current e history para las cinco métricas**, incluidos filtros cluster/resourceId, origen observed declarado en scrape y etiquetas filesystem. Evidencia: `target/prometheus-smoke/`, respuestas JSON y smoke.log del primer intento.
- El intento completo terminó con fallo al exigir inmediatamente `up{job="smoke-monitoring"}=1`. La aplicación acababa de arrancar; se añadió espera acotada del primer scrape correcto y captura de `/api/v1/targets` para diagnóstico. Esa corrección todavía no se reejecutó: el usuario rechazó el permiso de la segunda ejecución Docker.
- Por ello **NO se declara aprobado el smoke completo**. Quedan por repetir el scrape técnico real y el tramo que detiene Prometheus para comprobar 503 sin fallback (este manejo sí tiene pruebas HTTP simuladas satisfactorias).
- Se retiraron referencias rotas al archivo de plan ausente. `integracion-simulator.md` fija el punto de conexión: identidad por equipo modelado, cluster por ejecución, origin=simulated, métricas/counters compatibles, reloj lógico frente a scrape y propiedad de datos.

Para completar la comprobación pendiente, ejecutar en PowerShell 7 `./scripts/Test-Prometheus.ps1`. No requiere modificar el dominio ni diseñar de nuevo la API para comenzar el repositorio del simulador.
