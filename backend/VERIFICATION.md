# Verificación de la refactorización

Fecha: 6 de octubre de 2026.

## Comprobaciones

- python -m pytest backend -q -p no:cacheprovider: **96 pruebas correctas**.
- Se conservan los 31 escenarios originales con dependencias inyectadas.
- npm run build: correcto; sin modificaciones del frontend.
- git diff --check: correcto.
- SDK instalado con transporte simulado: esquema Task, bearer por usuario,
  consultas/filtros, conflictos y completados.
- Ocho hilos y dos usuarios: aislamiento de credenciales en 80 peticiones.
- Caducidad por TTL/exp, límites LRU, conexiones expulsadas en uso, apagado y
  liberación de ámbitos tras errores.
- Medianoche de Madrid y cambios de horario de 23/25 horas.
- Límites de importación del dominio, aplicación y puertos.

No se han modificado SQL, rachas de producción ni políticas RLS. La prueba SQL
no se ejecutó: estas verificaciones usan transporte simulado y no prueban RLS
contra una base de datos real.

## Navegador

Frontend original con Vite en 5180 y fixture local en memoria en 5001:

- Crear categoría y tareas; editar tarea y proyecto.
- Crear proyecto y tarea asociada; verificar historial tras recarga.
- Calendario semanal/mensual; arrastrar una tarea del lunes al miércoles.
- Completar una tarea y comprobar que se oculta en Todas las tareas.
- Crear rutina sin horas; completar/desmarcar y sincronizar contadores/rachas.
- Pausar/reactivar conservando historial y máximo.
- Fecha inicial futura: definición visible en Rutina y ausente de pendientes hoy.

El diálogo nativo de confirmación de borrado bloqueó la herramienta de navegador;
no se completó ese paso manual. Los endpoints de borrado, recursos ausentes y
limpieza de asociaciones se comprobaron mediante pytest. La fixture se detiene
al terminar y no modifica Supabase.

## Mediciones locales

GET a tareas, calendario, bootstrap y rutinas, 60 segundos por nivel, rango
2026-10-05 a 2026-10-11. Datos en memoria sobre el servidor de desarrollo Flask.
Estas cifras no representan capacidad de producción ni mejoras respecto al backend
anterior.

Con pausa de 0,2 segundos entre lecturas de cada usuario:

| Usuarios concurrentes | Peticiones/s | p95 (ms) | Errores |
| --- | ---: | ---: | ---: |
| 1 | 4,650 | 23,206 | 0 |
| 10 | 45,849 | 33,763 | 0 |
| 25 | 115,761 | 29,792 | 0 |

La ejecución sin pausas registró 0, 1.169 y 12.971 errores de conexión,
respectivamente. No se ha establecido su causa: no se atribuyen a la arquitectura
ni se consideran errores de respuesta de Supabase. Una repetición corta de
25 usuarios no reprodujo los errores y mostró latencia variable.

Los informes locales completos se guardan en load-results.local,
load-paced-results.local y load-diagnostic-results.local, ignorados por Git.
El script ahora informa también tipos/códigos de fallo de conexión y throughput
exitoso, sin exponer credenciales.

La comparación antes/después sobre WSGI y Supabase queda preparada en el script:
requiere un entorno de pruebas, usuarios/datos representativos y la versión
anterior ejecutándose bajo las mismas condiciones.
