# Correspondencia del historial compartido

Comprobado el 10 de octubre de 2026 en el proyecto `nppktgavaulgidxqegse`
(FinanceTracker), cuyo esquema `Task` usa StudyCalendar. Los identificadores
históricos locales difieren de los registrados en Supabase. Esta tabla documenta
su correspondencia funcional; no modifica registros remotos ni vuelve a ejecutar
DDL histórico. No usar `db push` sobre esta carpeta sin normalizar primero todo
el historial en un cambio separado y probarlo en un entorno aislado.

| Archivo local | Versión remota aplicada |
| --- | --- |
| `202610040001_create_tasks.sql` | `20261004103131` create_tasks, `20261004103201` harden_tasks_updated_at_search_path |
| `202610040002_move_tasks_to_task_schema.sql` | `20261004104022` move_tasks_to_task_schema |
| `202610040003_create_categories.sql` | `20261004111110` create_categories |
| `202610040004_add_task_type.sql` | `20261004120808` add_task_type |
| `202610040005_create_routines.sql` | `20261004125316` create_routines |
| `202610040006_remove_routine_categories.sql` | `20261004134037` remove_routine_categories |
| `202610040007_close_inactive_routine_periods.sql` | `20261004140404` close_inactive_routine_periods |
| `202610040008_add_routine_period_lookup_idx.sql` | `20261004173436` add_routine_period_lookup_idx |
| `202610040009_create_projects.sql` | `20261004184555` create_projects |
| `20261005133423_routine_habits_and_streaks.sql` | `20261005133423` routine_habits_and_streaks |
| `20261009220556_task_all_day.sql` | `20261009220556` task_all_day, aplicada el 10 de octubre (Europe/Madrid) |
| `20261009141547_user_settings.sql` | `20261009141547` user_settings, aplicada el 9 de octubre |
| `20261010164339_project_status.sql` | `20261010164339` project_status, aplicada el 10 de octubre |

La migración de tareas inicial remota creó `public.tasks`; la siguiente endureció
su función y la posterior trasladó ambos objetos a `Task`. El archivo inicial
local ya incorpora el esquema `Task` y `search_path = pg_catalog` en la función.
No son versiones que deban volver a aplicarse al proyecto existente.

Para preferencias se aplicó únicamente el SQL `user_settings`, tras confirmar
el usuario que ya disponía de una copia privada reciente. El conector asignó la
versión `20261009141547`; el archivo generado inicialmente como
`20261009134142_user_settings.sql` se renombró para coincidir con el historial
remoto. La migración `task_all_day` es independiente y se aplicó después,
el 10 de octubre (Europe/Madrid), registrada como `20261009220556`. Su archivo
local anterior `20261007144641_task_all_day.sql` se renombró para coincidir,
conservando el SQL. No se modificaron los registros de las versiones antiguas.

La migración `user_settings` y `backend/test_user_settings.sql` pasaron el
9 de octubre en PostgreSQL aislado (PGlite), con roles `anon` y `authenticated` y
una función `auth.uid()` que interpreta las claims de las fixtures. Se verificaron
defaults, restricciones, PATCH parcial, aislamiento, permisos, denegación anónima
y rollback de las fixtures. No se ejecutaron fixtures contra la base compartida.

Después de aplicar se comprobaron defaults, nulabilidad, RLS y las tres políticas
de propiedad, permisos de lectura/inserción y actualización solo de preferencias,
y denegación de acceso anónimo, borrado y reasignación del propietario. La nueva
tabla estaba vacía; los recuentos de las seis tablas anteriores se conservaron,
la definición de `routine_summaries` no cambió y los avisos del asesor de seguridad
fueron idénticos a los previos, sin avisos sobre `user_settings`.

Antes de aplicar `task_all_day`, su prueba SQL pasó en PostgreSQL aislado (PGlite)
con rollback completo. Después se verificaron tipo, default y nulabilidad, RLS
y las políticas originales de tareas. La huella de todos los campos anteriores
de las 14 tareas coincidió antes y después; los recuentos de las demás tablas y
la función de rachas también se conservaron. No aparecieron avisos de seguridad
nuevos. Ver `DEPLOYMENT.md` para las comprobaciones y sus límites.

La migración `project_status` añade únicamente `Task.projects.status`, con
`text NOT NULL DEFAULT 'active'` y un check para `active`, `inactive` y `completed`.
Fue generada con el CLI y pasó `backend/test_project_status.sql` en PostgreSQL
isolado (PGlite), con rollback, antes de aplicarse al proyecto compartido.
Se aplicó solo este cambio, sin reparar el historial antiguo. El archivo generado
inicialmente como `20261010163533_project_status.sql` se alineó con la versión
registrada por el conector: `20261010164339`.

Los 3 proyectos existentes quedaron activos. Las huellas de sus campos anteriores
y de las 14 tareas coincidieron antes y después. También se conservaron los
recuentos de categorías, rutinas, períodos, completaciones y preferencias,
las cuatro políticas de proyectos, RLS y la función de rachas. El asesor de
seguridad no añadió avisos. Las transiciones de estado de la UI se prueban con
proyectos desechables en memoria, sin cambiar el estado de los proyectos reales.


## Planes de proyectos: aplicada el 6 de noviembre de 2026

`20261106122252_project_plans.sql` corresponde a la versión remota `20261106122252` (`project_plans`). El CLI generó inicialmente `20261106115213_project_plans.sql`; se renombró para coincidir con el registro remoto, conservando su SQL. Se aplicó únicamente esta migración tras confirmación del usuario de una copia privada reciente y tras pasar las pruebas SQL en PostgreSQL aislado con rollback. No se reparó ni reejecutó ninguna versión histórica.

Se conservaron las huellas de los campos anteriores de 36 tareas, 4 proyectos, 4 categorías, 7 rutinas, 7 períodos, 44 completaciones y 1 preferencia. Las 36 tareas empiezan con `prerequisite_ids=[]`. RLS sigue activo en las siete tablas, con 27 políticas; `routine_summaries` conserva su huella. Los tres triggers nuevos usan SECURITY INVOKER y `search_path=pg_catalog`. El asesor de seguridad conserva solo su aviso previo sobre protección de contraseñas filtradas, sin hallazgos nuevos.

El historial remoto contiene además migraciones de FinanceTracker posteriores a `project_status`; pertenecen al otro proyecto compartido y no se modificaron. El usuario pidió iniciar StudyCalendar localmente y aplazar la publicación en Render.

## Sincronización offline: aplicada el 14 de noviembre de 2026

El CLI 3.1.0 generó `20261113224726_offline_sync.sql`. Se aplicó solo esta migración tras confirmación de copia privada y autorización expresa del usuario, después del CI y los despliegues compatibles de la PR #26. Se renombró como `20261114133419_offline_sync.sql` para coincidir con la versión remota, sin cambiar el SQL (SHA256 `9AD2EC0C996DC9A5DECB5F70CBC6BBE4C4751F564BD990790F770A874C9EF7EA`). No reejecutarla ni reparar el historial anterior o las entradas de FinanceTracker.

Las huellas completas y recuentos de las siete tablas de negocio coincidieron: 40 tareas, 4 proyectos, 4 categorías, 7 rutinas, 7 períodos, 44 completaciones y 1 preferencia. También se conservaron las 27 políticas originales y `routine_summaries`. Las tres tablas nuevas tienen RLS y solo SELECT propio para authenticated; no permiten escrituras directas ni acceso anónimo. `sync_snapshot` devolvió protocolo 1 con rol authenticated y todas las filas del propietario. El asesor señala la exposición deliberada de `sync_apply` como SECURITY DEFINER; su validación de identidad/propiedad, permisos restringidos y justificación se documentan en `docs/sync-protocol.md` y `DEPLOYMENT.md`.

## Funcionalidades de proyectos — aplicada el 18 de noviembre de 2026

La migración se probó en PostgreSQL aislado sobre `offline_sync` y se aplicó después de la confirmación de una copia privada. El conector registró la versión `20261118105622` con el nombre `project_features`. Se renombró el archivo original `20261118000759_project_features.sql` sin cambiar su SQL. SHA256: `3451A5ACD6C598DA7263E0972AE470A1CD3426B91F09214F2FD0607963314A8C`.

El historial anterior y los datos existentes se conservaron. Las 52 tareas empezaron sin funcionalidad. Se verificaron RLS, el snapshot autenticado del protocolo 1 y la ausencia de nuevas incidencias del asesor de seguridad. Véase `DEPLOYMENT.md` para el despliegue web.

## Conexiones entre funcionalidades — aplicada el 18 de noviembre de 2026

El conector registró `project_feature_links` como `20261118223658`. El archivo local original `20261118221651_project_feature_links.sql` se renombró conservando su SQL y SHA256 `B9906537ADC8196A013B5DF1554FC51C31C66683EAFA9191116D3F6DBE98BAA7`.

Se conservó el historial anterior y se verificaron los datos existentes, RLS, las restricciones de borrado y el snapshot autenticado. Véase `DEPLOYMENT.md` para la publicación web.
