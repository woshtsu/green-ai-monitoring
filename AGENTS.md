# Reglas de green-ai-monitoring

- Trabajar únicamente en este repositorio. No crear Git padre, modificar proyectos hermanos, publicar ni hacer push.
- Conservar Java objetivo 21, Spring Boot 4.1.1 y Maven Wrapper fijado. Sin APIs posteriores a Java 21 o preview. Compilar/ejecutar Docker con Java 21 cuando se autorice empaquetado.
- Dominio y aplicación Java puros; puertos de entrada/salida; adaptadores y configuración separados. No depender de Spring/HTTP/Prometheus desde el núcleo.
- Alcance actual: Grupo A completo (CPU, memoria, red, almacenamiento), fixture explícito, MVC, validaciones, errores, pruebas y OpenAPI. Sin adaptador real Prometheus, Dockerfile, Kubernetes, autenticación o servicios adicionales en este incremento.
- Gateway atiende tráfico externo; microservicios pueden comunicarse directamente por REST/DNS Kubernetes. Frontend solo usa Gateway, nunca microservicios o Supabase directamente.
- Rutas internas Monitoring `/api/v1/metrics/*`; rutas externas futuras Gateway `/api/monitoring/v1/metrics/*`. Monitoring no compensa la reescritura ni llama a Gateway, Data Processing o Prediction.
- Monitoring consulta Prometheus externo en una etapa posterior. Prometheus recolecta `/actuator/prometheus`; JVM/HTTP/proceso describen Monitoring, no el centro de datos.
- Monitoring no usa Supabase: sin adaptadores, JPA, JDBC o credenciales. Gateway tampoco usa SQL/Supabase. No utilizar ni probar credenciales filtradas; rotación se gestiona aparte.
- Datos reales hardware/logs son observed con procedencia de medición pendiente. Respetar propiedad de tablas y mínimos permisos; no mezclar simulación con logs reales.
- Fixtures pequeños deterministas solo prueban dominio/contrato, no son Simulator Service. Activación solo por perfil fixture; origin=simulated, source=fixture; no fallback ficticio.
- Preservar observed/simulated/estimated/unknown. No equiparar CPU con energía ni potencia con energía. Unidades, denominadores, UTC, agregaciones y ausencias documentados.
- Catálogo permitido, filtros tipados, sin PromQL ni URL upstream del cliente. Límites de rango, resolución, cardinalidad, puntos, cuerpo y timeout; sin truncamiento silencioso.
- Contratos versionados sujetos a revisión de integración; errores problem+json y X-Request-Id. API funcional separada de Actuator; sin exposición de secretos/configuración.
- Sin ETL, entrenamiento, base de datos propia, RabbitMQ o simulador en este repositorio.
- No declarar PMV1 completo sin almacenamiento y métricas obligatorias de workloads (Grupo B). Registrar pruebas ejecutadas y pendientes; cierre de portabilidad en contenedor Java 21.
- No borrar clústeres, volúmenes o históricos. Entornos de prueba aislados; comprobar contexto antes de futuros despliegues.
