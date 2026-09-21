# Contrato Monitoring v0.1 — propuesta

Dirección aprobada por el usuario; contrato interno provisional para revisión con Data Processing. Incremento autorizado: Grupo A, adaptador fixture explícito, sin Prometheus real. OpenAPI 3.1.0 estático en `src/main/resources/static/openapi/monitoring-v0.1.json`; no requiere biblioteca de generación ni dependencia adicional de Spring Boot. Referencia: https://spec.openapis.org/oas/v3.1.0.html.

## Rutas

| Método y ruta | Parámetros | Resultado |
| --- | --- | --- |
| GET `/api/v1/metrics/catalog` | Ninguno | Catálogo, unidades, descripción, agregación, ventana y tipos de recurso |
| GET `/api/v1/metrics/current` | `metric` obligatorio; `resourceType=node`, `cluster`, `resourceId` opcionales | Evaluación en el instante UTC capturado una vez al recibir la petición |
| GET `/api/v1/metrics/history` | Los anteriores, `start`, `end` obligatorios, `stepSeconds=15` | Evaluaciones históricas por serie |

Los filtros son igualdad exacta con patrón `[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}`; no admiten regex, etiquetas arbitrarias o expresiones PromQL. Parámetros desconocidos o repetidos se rechazan con 400. Una petición consulta una métrica y puede devolver varias series. `resourceType` explicita el tipo; esta versión admite node. Pods/servicios se añadirán con identificadores y métricas propios, sin reinterpretar resourceId. `resourceId` identifica un nodo dentro de cluster; sin cluster preserva coincidencias de varios clústeres. No se aceptan filtros origin ni source del cliente.

Estas son rutas internas. Gateway publicará posteriormente, por ejemplo, `/api/monitoring/v1/metrics/*` con reescritura. Monitoring no incorpora ese prefijo. Frontend usa Gateway; Data Processing puede consultar Monitoring directamente por DNS interno. Monitoring no llama a ninguno de ellos.

Scraping técnico: `/actuator/prometheus`. Salud: `/actuator/health/liveness` y `/actuator/health/readiness`. Son rutas independientes del catálogo funcional; métricas JVM/HTTP no representan uso del centro de datos. El alcance inicial no incluye publicación pública ni CORS abierto.

## Grupo A — implementado en el primer incremento

| Identificador | Unidad | Definición |
| --- | --- | --- |
| `node.cpu.utilization` | ratio (0–1) | 1 menos la media entre CPU lógicas de `rate(node_cpu_seconds_total{mode="idle"}[1m])`, por clúster/nodo. Denominador: capacidad total de todas las CPU lógicas del nodo, no requests/limits de pods. Iowait cuenta como no-idle; no equivale a energía |
| `node.memory.used` | bytes | `node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes`, por clúster/nodo; no confundir disponible con memoria libre |
| `node.network.receive` | bytes/s | `rate(node_network_receive_bytes_total[1m])`, una serie por nodo/interfaz; no sumar interfaces virtuales y físicas de forma implícita |
| `node.network.transmit` | bytes/s | `rate(node_network_transmit_bytes_total[1m])`, misma identidad por interfaz |
| `node.filesystem.used` | bytes | `node_filesystem_size_bytes - node_filesystem_free_bytes` por nodo/filesystem. Medida absoluta, sin denominador porcentual; capacidad de referencia size del mismo device/mountpoint/fstype. Free incluye bloques reservados, por lo que no equivale a size - avail |

Almacenamiento conserva `device`, `mountpoint`, `fstype`; nunca suma filesystems. Se admiten explícitamente fstype `ext2`, `ext3`, `ext4`, `xfs`, `btrfs`, `zfs`. Esta lista conservadora excluye tmpfs, devtmpfs, overlay, proc, sysfs, cgroup/cgroup2, squashfs y fuentes desconocidas/remotas. Otros tipos necesitan revisión; no se incorporan silenciosamente. Se descartan montajes `/proc`, `/sys`, `/dev`, `/run` y descendientes; errores de lectura del filesystem deben producir ausencia/advertencia, no cero. La regla será aplicada por el adaptador Prometheus futuro; los fixtures solo incluyen filesystems admisibles. Definiciones base: https://github.com/prometheus/node_exporter/blob/master/collector/filesystem_common.go.

## Grupo B — pendiente dentro del PMV1 integrado

| Familia | Definición propuesta a acordar con instrumentación |
| --- | --- |
| Solicitudes | Tasa de contador HTTP en requests/s, ventana 60 s, por servicio |
| Throughput | Operaciones de negocio completadas/s; no duplicar solicitudes sin declarar equivalencia |
| Concurrencia | Gauge de solicitudes/operaciones en curso; unidad operaciones |
| Latencia | Segundos, p95 sobre histogramas durante 5 min; definir buckets y agregación por servicio, no promediar percentiles |
| Errores | Tasa de errores/s y/o ratio errores/solicitudes con denominador explícito, si se expone |
| Pods, réplicas, workloads | Conteos y estados de objetos; identidad cluster/namespace/pod o servicio |

Node Exporter proporciona infraestructura del nodo. kube-state-metrics describe objetos/estado Kubernetes y no produce por sí solo latencia ni throughput HTTP. La instrumentación de aplicación aporta solicitudes, concurrencia, throughput, latencia y errores. Fuente de métricas de contenedores pendiente de confirmar en el clúster. Grupo B no se publica aún como consultable en el catálogo; identificadores no registrados dan 400. Una métrica permitida cuya fuente no tenga series da no_data, nunca fixture de recuperación. La falta de fuente configurada da 503, no no_data.

Propuesta inicial centrada en nodos; pods/workloads requieren catálogo y denominadores propios en una revisión posterior. Node Exporter y mapeo de etiquetas son precondiciones por confirmar para el adaptador real. Una etiqueta `cluster` y un identificador estable de nodo deben incorporarse en la recolección/configuración; no inventarlos a partir de la IP del desarrollador. Series de red conservan `device`.

No se ofrece potencia ni energía hasta validar una fuente, unidades y método. Fixtures representan las cinco métricas del Grupo A, siempre origin=simulated y source=fixture; no son inventario real ni un simulador. Perfil fixture explícito y reloj controlable en pruebas. Sin ese perfil el catálogo funciona, pero las consultas dan 503 hasta implementar una fuente real.

## Tiempo, calidad y límites

- Entrada `start`/`end`: RFC3339 con zona, segundos enteros; normalización y salida UTC con `Z`. `start < end <= now`.
- Intervalo histórico inclusivo `[start,end]`; rejilla `start + k * stepSeconds <= end`. El último punto puede quedar antes de `end`.
- `stepSeconds`: entero entre 15 y 3600. Es frecuencia de evaluación; no implica promedio de buckets. CPU y red usan siempre ventana móvil de 60 s; memoria usa valor instantáneo.
- Rango máximo 24 h; máximo 100 series; máximo 10 000 puntos totales, incluyendo posiciones ausentes. Un rango puede ser válido y superar el presupuesto por su cardinalidad.
- No interpolar ni reemplazar ausencias por cero. Para una serie que aparece en algún instante, posiciones faltantes llevan `value:null`, `quality:missing`. Cero válido lleva `quality:valid`.
- Sin ninguna serie, devolver 200, `series:[]`, `dataStatus:no_data`. Esto no demuestra que el recurso no exista; no hay inventario autoritativo.
- `NaN`/infinito no se serializan como números JSON; se convierten a null con `quality:non_finite` y aviso. Conservar procedencia y advertencias.
- En gauges, lookback propuesto 30 s; una evaluación puede reutilizar una muestra reciente. `timestamp` es instante de evaluación, no necesariamente de scrape. En tasas se exige información suficiente en la ventana de 60 s; no prometer detectar cada scrape perdido mediante una tasa.
- Presupuesto de consulta al puerto de salida 5 s, sin reintentos automáticos. Tamaño de respuesta funcional máximo 5 MiB validado antes de escribir el cuerpo. Para el adaptador Prometheus futuro: conexión 1 s, evaluación upstream 3 s y cuerpo upstream 5 MiB limitado durante lectura; estos tres controles HTTP quedan pendientes porque no hay cliente upstream en este incremento.
- Al superar cardinalidad/puntos, rechazar con 422 sin truncar. Pedir hasta 101 series al backend permite detectar exceso frente al máximo 100; verificar también el presupuesto total al procesar.

El adaptador utilizará consultas instantáneas y de rango del [API oficial de Prometheus](https://prometheus.io/docs/prometheus/latest/querying/api/), y convertirá los resultados vector/matrix al contrato propio. Validará la respuesta y preservará sus advertencias. El catálogo y los filtros serán las únicas entradas para construir consultas.

## Ejemplo de respuesta histórica

Petición: `GET /api/v1/metrics/history?metric=node.memory.used&cluster=fixture-lab&start=2026-09-21T15:00:00Z&end=2026-09-21T15:00:15Z&stepSeconds=15`

```json
{
  "metric": "node.memory.used",
  "unit": "bytes",
  "aggregation": "instant",
  "windowSeconds": null,
  "start": "2026-09-21T15:00:00Z",
  "end": "2026-09-21T15:00:15Z",
  "stepSeconds": 15,
  "dataStatus": "partial",
  "series": [
    {
      "resource": {"type": "node", "cluster": "fixture-lab", "id": "fixture-node-01"},
      "labels": {},
      "source": "fixture",
      "origin": "simulated",
      "samples": [
        {"timestamp": "2026-09-21T15:00:00Z", "value": 0, "quality": "valid"},
        {"timestamp": "2026-09-21T15:00:15Z", "value": null, "quality": "missing"}
      ]
    }
  ],
  "warnings": []
}
```

`origin`: `observed|simulated|estimated|unknown`; se deriva de configuración confiable/etiquetado acordado, nunca del cliente ni solo del hecho de provenir de Prometheus. `source`: `fixture|prometheus`. `dataStatus`: `complete|partial|no_data`; complete significa que la rejilla retornada está cubierta, no garantía de ausencia de scrapes perdidos. Advertencias o valores ausentes/no finitos producen partial. Current conserva el mismo sobre con `start=end=instanteEvaluado`, `stepSeconds=null` y una posición por serie devuelta. Una serie completamente ausente no se sintetiza.

## Errores

Formato `application/problem+json`: `type`, `title`, `status`, `detail`, `instance`, `code`, `requestId`. Sin PromQL, URLs internas o trazas en mensajes al cliente.

| HTTP | Código | Condición |
| --- | --- | --- |
| 400 | `INVALID_QUERY` | Métrica no permitida, filtro/fecha/resolución inválidos o parámetros desconocidos |
| 422 | `QUERY_LIMIT_EXCEEDED` | Rango, series, puntos o tamaño superiores a límites |
| 502 | `UPSTREAM_INVALID_RESPONSE` | JSON/esquema inesperado o error de consulta interna |
| 503 | `METRICS_SOURCE_UNAVAILABLE` | Fuente inaccesible/no disponible |
| 504 | `METRICS_SOURCE_TIMEOUT` | Plazo agotado |

No devolver 200 con lista vacía cuando falla Prometheus. Generar `X-Request-Id` para correlación. No depender del Gateway para validaciones o límites.

## Pruebas de aceptación previstas

1. Catálogo y filtros permitidos; rechazo de PromQL, parámetros extra y valores que intenten escapar selectores.
2. Dos nodos y múltiples interfaces preservan identidades y unidades.
3. Cero, hueco, ausencia total, valores no finitos y advertencias producen resultados distintos.
4. Límites inclusivos de intervalo, reloj fijo UTC, paso no divisor del rango y presupuesto de puntos.
5. Timeout, conexión rechazada, error upstream y JSON inválido producen errores contractuales distintos.
6. HTTP y contrato estático verificados con fixture y sin perfil fixture. Pruebas del adaptador Prometheus quedan pendientes, no se implementa ahora.
7. `verify` y pruebas HTTP con Java 21 dentro de un contenedor de herramientas, sin Dockerfile. Imagen final y smoke de esa imagen quedan pendientes del incremento de empaquetado.
