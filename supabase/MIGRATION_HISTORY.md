# Correspondencia del historial compartido

Comprobado el 9 de octubre de 2026 en el proyecto `nppktgavaulgidxqegse`
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
| `20261007144641_task_all_day.sql` | Pendiente; `tasks.all_day` no existe |
| `20261009141547_user_settings.sql` | `20261009141547` user_settings, aplicada el 9 de octubre |

La migración de tareas inicial remota creó `public.tasks`; la siguiente endureció
su función y la posterior trasladó ambos objetos a `Task`. El archivo inicial
local ya incorpora el esquema `Task` y `search_path = pg_catalog` en la función.
No son versiones que deban volver a aplicarse al proyecto existente.

Para preferencias se aplicó únicamente el SQL `user_settings`, tras confirmar
el usuario que ya disponía de una copia privada reciente. El conector asignó la
versión `20261009141547`; el archivo generado inicialmente como
`20261009134142_user_settings.sql` se renombró para coincidir con el historial
remoto. La migración `task_all_day` es independiente y sigue pendiente.

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
