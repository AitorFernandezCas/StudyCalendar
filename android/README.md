# Android de StudyCalendar

Implementación iniciada a partir de [las decisiones aprobadas](../docs/android-planning.md).

## Estado de esta entrega

La versión 0.4.0 incluye tres secciones: Agenda, Proyectos y Rutinas. Permite crear, editar, completar, reabrir y eliminar tareas personales con fecha, horario o todo el día; asociar tareas a proyectos, dejarlas sin fecha y seleccionar tareas previas. El formulario permite crear un proyecto conservando el borrador de la tarea.

Proyectos permite crear, editar nombre/color/estado y eliminar con confirmación de borrado de todas sus tareas y dependencias en una transacción. Agrupa activos, inactivos y completados, muestra progreso sobre todas sus tareas y mantiene las no asignadas bajo «Sin proyecto». Cambiar el estado conserva las tareas y su historial. Las nuevas asociaciones se limitan a proyectos activos; las existentes pueden conservar su proyecto inactivo/completado. La agenda mantiene las tareas fechadas de cualquier estado.

Rutinas permite crear hábitos con título, color, activación y fecha inicial; editar, pausar/reactivar y eliminar con confirmación. Se agrupan en pendientes de hoy, completadas hoy, próximas e inactivas. Solo se puede completar/desmarcar hoy si el hábito está activo y su período incluye esa fecha. Se recalculan las rachas actual y máxima, conservando el historial al pausar. Las rutinas no se incluyen en la agenda.

En el modo local, el día de hábitos usa Europe/Madrid y reinicio a las 00:00; en la consulta de cuenta se descarga su preferencia de reinicio. Se actualiza al llegar el límite y al volver a la app. No existe todavía un ajuste del reinicio desde Android. Una acción local con fecha obsoleta se rechaza; cada registro de completado conserva su fecha original.

Room conserva cada cambio y su registro local en la misma transacción. La migración explícita de la base v1 a v2 añade proyectos, rutinas y completados sin borrar las tareas ni el registro existente. No hay migración destructiva. El núcleo Kotlin independiente implementa las reglas de dependencias, la combinación de campos, el día de hábitos en Europe/Madrid y las rachas.

Esta entrega mantiene el modo local y añade el protocolo de escritura de cuenta, cola offline y resolución de conflictos (detalle al final). La base `local-preview.db` y su registro no se envían ni se asocian automáticamente a una cuenta. Mientras el servidor no anuncie protocolo 1, la cuenta continúa en consulta. El identificador debug termina en `.dev`.

Pendiente de activación compartida: migración y publicación coordinada del contrato de escritura ya implementado. Fuera de esta entrega: diagrama visual de proyectos, edición de preferencias desde Android y programación/acciones de recordatorios.

## Compilación

Requiere JDK 17, SDK Android 36, Build Tools 35.0.0 (versión utilizada por AGP 8.13.2). Abrir `android/` en Android Studio o definir `JAVA_HOME` y `ANDROID_HOME` para las herramientas instaladas. El wrapper fija Gradle 8.13 y verifica el SHA-256 oficial de la distribución; las versiones de plugins y dependencias están fijadas en los archivos Gradle.

Desde `android/` en PowerShell:

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

En este equipo se han preparado herramientas locales dentro de `.tools/android/`
(ignoradas por Git). Para usarlas desde `android/` sin cambiar la configuración
global de Windows:

```powershell
$env:JAVA_HOME = (Get-ChildItem '../.tools/android/jdk' -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = (Resolve-Path '../.tools/android/sdk').Path
$env:GRADLE_USER_HOME = Join-Path (Resolve-Path '../.tools/android').Path 'gradle-cache'
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

En Linux/macOS:

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

El APK de pruebas se genera en `app/build/outputs/apk/debug/app-debug.apk`. Usa firma debug; todavía no es el APK privado de release acordado.

Con un emulador o teléfono conectado mediante ADB, ejecutar `:app:connectedDebugAndroidTest` para verificar persistencia, migración v1→v2, estados de proyectos, eliminación atómica, hábitos y reversión cuando falla el registro. Las pruebas usan bases exclusivas de pruebas o en memoria, nunca `local-preview.db`. Requieren un dispositivo y no se consideran ejecutadas por compilar el APK.

## Comprobación manual

1. Crear una tarea de todo el día desde una fecha de agenda distinta: el botón general propone hoy en Madrid.
2. Crear una tarea con hora y verificar validación de fin posterior al inicio.
3. Editar, completar/reabrir y eliminar con confirmación; reiniciar la app y comprobar persistencia.
4. Repetir en modo avión. La versión local no presenta sus datos como sincronizados.
5. Crear un proyecto; añadir tareas con fecha y sin fecha, crear otro desde el formulario y comprobar que se conserva el borrador. Verificar dependencias y bloqueo al completar antes de los requisitos.
6. Cambiar un proyecto a inactivo/completado y de vuelta a activo: comprobar progreso e historial, conservación en agenda y que no recibe nuevas tareas mientras está inactivo/completado. Cancelar una eliminación; eliminar otro proyecto desechable y verificar que desaparecen sus tareas.
7. Crear una rutina para hoy y otra futura. Completar/desmarcar hoy y comprobar rachas; pausar/reactivar y editar conservando completados. Las futuras no permiten completar antes del inicio.
8. Volver a abrir la app después de medianoche de Madrid y comprobar el nuevo día de hábitos. Verificar que una rutina no aparece en la agenda y que todo persiste tras cerrar/reabrir sin conexión.

No se incluyen credenciales, claves de firma ni SDK en Git. Al añadir sincronización se respetarán las restricciones de migración y despliegue del repositorio; el registro local no debe reproducirse como CRUD remoto sin control de concurrencia.

## Verificación de esta entrega

- 16 pruebas del núcleo Kotlin ejecutadas correctamente (dependencias, proyectos, pausas/reactivación, conflictos, rachas, cambios de hora y reglas de avisos).
- En la entrega inicial se verificaron 183 pruebas backend y la compilación frontend. La ampliación 0.2.0 solo modifica la aplicación Android y su documentación.
- APK debug y APK de pruebas instrumentadas compilados correctamente; lint completado sin errores.
- Las ocho pruebas instrumentadas de `LocalCalendarTest` y `CollectionsStoreTest` se ejecutaron correctamente en el POCO X7 Pro con Android 16 (API 36), el 2026-11-13. Cubren migración v1→v2 conservando tareas y registro; persistencia al reabrir; estados y nombres de proyectos; eliminación atómica con dependencias; completados únicos, pausas, edición y cambio de día; y reversión cuando falla el registro. Resultado: `OK (8 tests)`. Se utilizaron bases exclusivas de pruebas y en memoria, nunca la agenda del usuario. Salida local en `app/build/reports/device/poco-instrumentation.txt` (generada, fuera de Git).
- La prueba de migración reconstruye v1 desde su esquema exportado y deja que Room ejecute la migración de producción y valide todas las tablas de v2. Se evitó `MigrationTestHelper` porque su deserializador lanzó `AbstractMethodError` en el dispositivo antes de llegar a ejecutar la migración.
- APK 0.2.0 instalado como actualización con `adb install -r`; arranque correcto y navegación Agenda/Proyectos/Rutinas presente en la jerarquía de accesibilidad. La revisión interactiva del checklist queda pendiente: HyperOS bloquea la inyección de toques remotos con los permisos actuales. No se han solicitado permisos adicionales para sortear esta restricción.

Las comprobaciones de 0.2.0 no verificaban autenticación Android. La verificación de la conexión 0.3.0 se registra al final; las escrituras sincronizadas y las notificaciones del sistema siguen pendientes.

## Conexión a Supabase · 0.3.0

La app incorpora acceso por correo/contraseña a la cuenta existente, descarga directa por HTTPS desde el esquema `Task` y actualización manual. Se usa la clave pública publishable del proyecto existente; nunca claves privilegiadas. La contraseña no se persiste ni se registra. Los tokens se cifran con AES-GCM y una clave Android Keystore no exportable; la renovación conserva la identidad de la cuenta.

Esta conexión permite **consulta de la cuenta y copia sin conexión**, todavía no escritura sincronizada. Los controles de modificación se deshabilitan al consultar la cuenta. El modo local anterior sigue disponible al cerrar sesión y conserva sus tareas y registro: no se importan automáticamente en la cuenta. Esto no completa la etapa de sincronización bidireccional de `docs/android-planning.md`.

La descarga pagina tareas, proyectos, rutinas, períodos y completados por identificador hasta una página vacía, además de leer la preferencia de reinicio. Cada respuesta se comprueba contra el usuario autenticado, además de RLS. La copia se guarda por UUID en el almacenamiento privado de la app con reemplazo atómico, separada de `local-preview.db`. Un fallo conserva la última copia completa; cerrar sesión oculta la copia de cuenta. No hay una instantánea transaccional entre las lecturas remotas: una actualización concurrente puede requerir volver a descargar.

La app respeta `routine_reset_time` de la copia de la cuenta y Europe/Madrid. La descarga se solicita al restaurar la sesión, al iniciar sesión y con «Actualizar». Los errores requieren reintento explícito; una sesión caducada conserva la copia y permite renovar el acceso. No se ofrecen aún registro, recuperación de contraseña, MFA ni proveedores OAuth en Android; se conserva la cuenta y configuración Auth existentes.

La conexión no aplica migraciones remotas ni modifica RLS, Auth global o datos de producción. El contrato de revisiones, operaciones idempotentes, conflictos y escrituras compatibles web/Flask sigue pendiente; habilitar envío del registro local sin ese contrato incumpliría el diseño aprobado.

Fuentes de implementación: [Auth REST oficial](https://github.com/supabase/auth/blob/master/openapi.yaml), [sesiones de Supabase](https://supabase.com/docs/guides/auth/sessions) y [selección de esquema PostgREST](https://docs.postgrest.org/en/stable/references/api/schemas.html).

### Verificación de conexión 0.3.0

- Pruebas JVM del núcleo: 16, sin cambios en esta entrega (Gradle confirma el resultado previo como actualizado).
- Poco X7 Pro / Android 16: `OK (11 tests)`; incluye 3 pruebas nuevas de conexión con transporte simulado y 8 de persistencia/migración/proyectos/hábitos. Las nuevas comprueban paginación con páginas cortas, reinicio por cuenta, rechazo de filas de otro propietario, cifrado/restauración de sesión, conservación de la copia al fallar la descarga y aislamiento después de cerrar sesión. No utilizan credenciales ni datos reales de la cuenta. Informe generado: `app/build/reports/device/poco-cloud-tests.txt`.
- Comprobación remota de solo lectura: esquema y columnas existentes, permisos SELECT de authenticated, RLS activo en las siete tablas y políticas SELECT por `auth.uid() = user_id`. La llamada REST sin sesión fue rechazada (401/403); no se expusieron filas. No se ejecutó DDL ni escrituras de datos.
- APK 0.3.0 instalado con actualización conservando datos y arranque correcto. La descarga con credenciales reales requiere que el usuario inicie sesión desde el teléfono; no se considera probada por las pruebas de transporte simulado.

El usuario confirmó el 2026-11-13 que inició sesión en el Poco y ve sus tareas, proyectos y rutinas reales. Queda verificada la consulta autenticada de extremo a extremo por confirmación del usuario. La compilación final y lint terminaron correctamente, sin errores (13 avisos). Las escrituras sincronizadas continúan pendientes.


## Sincronización 0.4.0 · preparada, pendiente de activación compartida

La implementación permite guardar cambios de cuenta sin conexión, enviarlos con operaciones idempotentes y resolver conflictos por campo. Un borrado remoto no se recrea. La cola, los recibos y la base remota se guardan juntos en la copia privada de esa cuenta; una edición sigue disponible aunque la red esté esperando. WorkManager 2.11.2 solicita envío con red y conserva los pendientes cuando necesita reintentar o renovar acceso. Los conflictos detienen la cola y permanecen al cerrar/reabrir. Cerrar sesión conserva los pendientes para la misma cuenta.

Las marcas de hábitos guardan su día original y sus revisiones de disponibilidad/ajustes; una creación o activación pendiente puede preceder a la marca. No se reutiliza el registro de pruebas de Room como operaciones remotas. Véase [el contrato y sus decisiones de persistencia](../docs/sync-protocol.md).

La versión 0.4.0 está instalada como actualización en el Poco. Hasta aplicar la migración y publicar web/backend compatibles, mantiene la cuenta en consulta. Esta entrega **no ha modificado el esquema ni los datos del Supabase compartido y no ha publicado Render**.

Verificación del 2026-11-14:

- Compilación de ambos APK y lint correctos, sin errores.
- `OK (17 tests)` en el POCO X7 Pro / Android 16. Se verifican pérdida de respuesta con recuperación del mismo UUID, reapertura con cola y conflictos, mezcla de campos independientes, edición durante una espera HTTP sin pérdida, resolución explícita con nueva operación, eliminación remota sin recreación, día original de un hábito creado offline y base de disponibilidad al pausarlo antes del primer envío. Se mantienen las pruebas anteriores de migración y persistencia. Informe local generado: `app/build/reports/device/poco-sync-tests.txt`.
- 16 pruebas JVM del núcleo previamente verificadas y sin cambios en este incremento.
- Backend: 185 pruebas correctas y smoke Gunicorn/health/401/CORS en Linux correcto.
- Web: build y pruebas de sincronización (3), calendario (35) y despliegue (4) correctos.
- PostgreSQL 17 aislado: conservación de filas, RLS/privilegios, recibos, combinaciones/conflictos, concurrencia de dos conexiones, eliminaciones, contexto histórico, creación offline, cambios de hora e historial de más de 1.000 registros correctos.
- PostgREST real con JWT de prueba: lectura/escritura autenticadas, recuperación de recibo HTTP, separación de usuarios, rechazo anónimo y bloqueo de CRUD antiguo correctos.

Las pruebas de teléfono usan transportes simulados y bases privadas de pruebas. Las de PostgreSQL/PostgREST usan contenedores desechables; no se presentan como escrituras verificadas en producción. La comprobación final móvil↔web requiere activar el contrato según `DEPLOYMENT.md`.
