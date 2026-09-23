# Punto de integración para green-ai-simulator

Monitoring Grupo A queda como consumidor de Prometheus; el simulador será un servicio y repositorio independientes. Este documento fija lo que el contrato actual puede consumir, no implementa el simulador ni define contratos de otros compañeros.

## Flujo y separación

Simulator/exporter → scrape de Prometheus → API interna de Monitoring → Data Processing o Gateway.

El simulador no inserta métricas en Monitoring ni escribe en logs reales de Supabase. Monitoring no llama al simulador ni a Supabase. El futuro adaptador de inventario podrá leer hardware con permisos mínimos cuando se implemente el adaptador y existan permisos mínimos y credenciales rotadas; hasta entonces usar un inventario fixture explícito. Que hardware sea observado no convierte los resultados simulados en observados.

## Identidad y procedencia

- Cada serie simulada declara `origin="simulated"`. Reservar observed para mediciones y estimated para estimaciones documentadas; nunca inferir energía medida desde CPU.
- Declarar `cluster="sim-run-<id>"` para separar una ejecución de otras y del entorno observado. Debe cumplir `[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}`. El identificador completo de ejecución y semilla también se guardarán en el registro persistente del simulador.
- `instance` identifica el equipo modelado, no el proceso exporter ni un nodo físico disponible. Para un endpoint con varios equipos, exponer una etiqueta estable `node` y configurar `monitoring.prometheus.instance-label=node`; Prometheus gestiona instance del target sin sobrescribir esa identidad.
- Si todos los targets de un Prometheus usan esa configuración, los exporters observados también necesitan la etiqueta node. No mezclar esquemas de identidad dentro de un Monitoring sin mapeo acordado.
- Monitoring preserva cluster/recurso y device/mountpoint/fstype según la métrica. Etiquetas adicionales como run_id no forman parte de la identidad pública; por eso la ejecución debe separarse mediante cluster.
- No depender de default-cluster=lab-01 para simulación: es un respaldo para etiquetas ausentes, no un mecanismo de aislamiento. External_labels no sustituye labels/relabel_configs de scrape para consultas locales.

## Métricas compatibles con Grupo A

Para reutilizar el catálogo actual, un adaptador de exposición del simulador puede implementar explícitamente este subconjunto compatible con Node Exporter, marcado como simulated:

| Familia expuesta a Prometheus | Tipo/unidad | Condiciones |
| --- | --- | --- |
| node_cpu_seconds_total | Counter, segundos por CPU lógica/modo | Etiquetas cpu y mode; idle necesario. Incrementos consistentes con tiempo/CPU modelados; Monitoring usa 1 - media(rate(idle[1m])) |
| node_memory_MemTotal_bytes, node_memory_MemAvailable_bytes | Gauges, bytes | Misma identidad; 0 <= available <= total. Monitoring calcula total - available |
| node_network_receive_bytes_total, node_network_transmit_bytes_total | Counters, bytes | Etiqueta device por interfaz; aumentan con la transferencia simulada. Monitoring calcula rate en 60 s |
| node_filesystem_size_bytes, node_filesystem_free_bytes | Gauges, bytes | Misma identidad y device/mountpoint/fstype; 0 <= free <= size. Monitoring calcula size - free |
| node_filesystem_device_error | Gauge, 0 o 1 | Mismas etiquetas del filesystem; 0 habilita el dato, 1 lo excluye. No inventar cero si hay fallo |

Almacenamiento admite ext2/ext3/ext4/xfs/btrfs/zfs y excluye /proc,/sys,/dev,/run y descendientes. No sumar filesystems ni interfaces. Si se elige un prefijo propio greenai_sim_* en lugar del formato compatible, será necesaria una revisión explícita del catálogo/adaptador Monitoring; no asumir compatibilidad por el nombre del servicio.

## Reloj y reproducibilidad

El núcleo del simulador tendrá semilla y reloj lógico controlados. Sus logs persistentes registrarán tiempos lógicos, configuración, versiones y resultados. El endpoint scrape describe el estado actual; Prometheus asigna timestamps de recolección.

Para comparar rate de Monitoring con utilización lógica, el modo de exportación compatible deberá avanzar a ritmo 1x respecto al tiempo real. Simulación acelerada/replay no equivale a rate sobre segundos reales: conservar esos resultados en el registro de simulación o acordar otro contrato. No publicar timestamps históricos como si /metrics fuera una API de backfill.

Scrape sugerido para integración: 15 s o menor, con suficientes muestras para ventanas de rate de 60 s. Tras un arranque puede haber no_data mientras se reúnen muestras. No sustituirlo por cero. Evitar reiniciar contadores dentro de la misma ejecución; si se reinician, registrar el hecho y distinguir el run.

## Primer incremento recomendado del simulador

1. Núcleo Python hexagonal sin HTTP/Prometheus/Kubernetes; inventario validado, unidades y procedencia por parámetro.
2. Escenarios baja/media/alta/pico, semilla/reloj controlados, límites de duración/recursos y parada.
3. Registro persistente por ejecución y fixtures identificados; pruebas de determinismo lógico.
4. Adaptador de métricas con las identidades/unidades anteriores; validar el flujo Simulator → Prometheus → Monitoring sin cambios silenciosos en contratos.

Carga real, lectura Supabase y acciones Kubernetes se incorporarán mediante adaptadores separados cuando se confirmen permisos/datos. Las métricas obligatorias del Grupo B permanecen en PMV1 integrado y requieren instrumentación; el paso al simulador no declara cerrado PMV1.

## Decisiones consolidadas del adaptador Prometheus

Se conservó HttpClient JDK 21 sin dependencias runtime nuevas. Se corrigió el plan inicial: no recortar puerto de instance, origin unknown por defecto, agrupar CPU por clúster/recurso/origen, excluir únicamente los árboles de montajes indicados y aplicar deadline también al cuerpo HTTP. URL explícita, sin redirecciones/reintentos, default-cluster configurable, sin recuperación ficticia ante errores.

## Referencia de BD actualizada — 2026-09-22

Consultar el [modelo de BD](modelo-bd.md): diagrama y campos completos de `usuario`, `hardware` y `logs`, con mapeos y limitaciones de integración. El diagrama aporta tipos y relaciones; la extracción SQL del usuario confirma tipos y nulabilidad. La extracción completa confirma defaults, longitudes/precisión, restricciones, índices y RLS; verificación documental del esquema cerrada. Monitoring conserva Prometheus como fuente y Simulator conserva JSON/JSONL como persistencia; el acceso a inventario SQL es futuro. Esta referencia actualiza las suposiciones del esquema, sin ampliar el catálogo de métricas ni implementar acceso a BD.
