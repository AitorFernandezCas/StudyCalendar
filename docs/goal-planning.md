# Objetivos en Android

Objetivos permite crear metas semanales (lunes a domingo), mensuales y anuales en períodos de calendario de Europe/Madrid. Cada objetivo tiene título, período y, opcionalmente, una meta numérica positiva y una unidad. Una revisión guarda la fecha, un total absoluto opcional y notas. Guardar una revisión vacía registra que se ha revisado el objetivo. El último total registrado determina el progreso. Alcanzar la cifra permite seguir registrando avances. El usuario marca el objetivo conseguido y puede reabrirlo.

Los períodos vencidos pasan a Historial y aceptan revisiones finales. Los objetivos conseguidos o vencidos dejan de recibir avisos. Cada nuevo período empieza vacío. Editar o eliminar un objetivo comprueba que conserva la versión mostrada. Eliminarlo requiere confirmación y elimina sus revisiones.

## Recordatorios

La configuración se guarda por tipo de objetivo. Empieza desactivada y sugiere revisiones diarias a las 20:00 para objetivos semanales, los domingos a las 20:00 para mensuales y el último día del mes a las 20:00 para anuales. La frecuencia, hora y día se pueden modificar. Un día de mes superior a su longitud se ajusta al último día.

Las revisiones satisfacen la ventana diaria, semanal o mensual correspondiente al aviso. Se agrupan los objetivos pendientes en una notificación que abre la vista Por revisar. Cerrar la notificación conserva la revisión pendiente. Los avisos se calculan con la copia disponible en cada teléfono y se recuperan tras reinicio o actualización. AlarmManager utiliza entrega aproximada compatible con las restricciones de batería. Android requiere permiso de notificaciones en cada instalación. Los teléfonos habilitados reciben sus propios avisos y conocen los avances de otros dispositivos cuando sincronizan.

## Persistencia y sincronización

Room 5 añade goals, goal_reviews y goal_schedules a la base local con la migración 4→5. La réplica de cuenta conserva estas colecciones y las operaciones pendientes en su documento atómico existente. El modo local conserva sus propios datos.

El protocolo 1 amplía sync_snapshot y sync_apply con las tres colecciones. La presencia de goals anuncia la capacidad. Cada revisión tiene identificador estable y constituye una operación de creación independiente. Los reintentos usan los recibos existentes. Los cambios concurrentes en objetivos y horarios usan la resolución de conflictos existente. Las revisiones son permanentes hasta eliminar su objetivo. RLS y claves compuestas impiden asociar revisiones a objetivos de otras cuentas.

## Verificación

Ejecutar el núcleo Kotlin, assembleDebug, lintDebug y las pruebas instrumentadas en la instalación aislada. GoalsTest comprueba períodos, ventanas, horarios Madrid, migración 4→5, persistencia, cola, recibos y formulario. El script frontend/scripts/test-goals-db.mjs ejecuta las migraciones y contratos con PGlite en memoria y admite PGLITE_MODULE para localizar una instalación existente. Nunca lee DATABASE_URL.

La migración compartida requiere una copia privada confirmada y su aplicación antes de publicar el APK. Consultar DEPLOYMENT.md.
