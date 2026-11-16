# Android de StudyCalendar

Implementación iniciada a partir de [las decisiones aprobadas](../docs/android-planning.md).

## Estado de esta entrega

La versión 0.4.0 incluye Agenda, Proyectos, Rutinas y Categorías. Permite crear, editar, completar, reabrir y eliminar tareas personales con fecha, horario o todo el día; asociar tareas a proyectos, dejarlas sin fecha y seleccionar tareas previas. El formulario permite crear un proyecto conservando el borrador de la tarea.

Proyectos permite crear, editar nombre/color/estado y eliminar con confirmación de borrado de todas sus tareas y dependencias en una transacción. Agrupa activos, inactivos y completados, muestra progreso sobre todas sus tareas y mantiene las no asignadas bajo «Sin proyecto». Cambiar el estado conserva las tareas y su historial. Las nuevas asociaciones se limitan a proyectos activos; las existentes pueden conservar su proyecto inactivo/completado. La agenda mantiene las tareas fechadas de cualquier estado.

Rutinas permite crear hábitos con título, color, activación y fecha inicial; editar, pausar/reactivar y eliminar con confirmación. Se agrupan en pendientes de hoy, completadas hoy, próximas e inactivas. Solo se puede completar/desmarcar hoy si el hábito está activo y su período incluye esa fecha. Se recalculan las rachas actual y máxima, conservando el historial al pausar. Las rutinas no se incluyen en la agenda.

En el modo local, el día de hábitos usa Europe/Madrid y reinicio a las 00:00; en la consulta de cuenta se descarga su preferencia de reinicio. Se actualiza al llegar el límite y al volver a la app. No existe todavía un ajuste del reinicio desde Android. Una acción local con fecha obsoleta se rechaza; cada registro de completado conserva su fecha original.

Room conserva cada cambio y su registro local en la misma transacción. La migración explícita de la base v1 a v2 añade proyectos, rutinas y completados sin borrar las tareas ni el registro existente. No hay migración destructiva. El núcleo Kotlin independiente implementa las reglas de dependencias, la combinación de campos, el día de hábitos en Europe/Madrid y las rachas.

Esta entrega mantiene el modo local y añade el protocolo de escritura de cuenta, cola offline y resolución de conflictos (detalle al final). La base `local-preview.db` y su registro no se envían ni se asocian automáticamente a una cuenta. Mientras el servidor no anuncie protocolo 1, la cuenta continúa en consulta. El identificador debug termina en `.dev`.

Pendiente de activación compartida: migración y publicación coordinada del contrato de escritura ya implementado. Fuera de esta entrega: diagrama visual de proyectos, edición de preferencias desde Android y programación/acciones de recordatorios.

## Categorías en Android

La sección «Categorías» permite crear, editar y eliminar categorías por nombre y color, tanto en modo local como con cuenta. Las tareas pueden asignarse a una categoría o quedar sin ella; al eliminar una categoría se conservan sus tareas. Las tarjetas usan el color del proyecto cuando existe y, en caso contrario, el de la categoría.

La base local incorpora una migración de Room de la versión 2 a la 3 que conserva tareas y registro. La cuenta usa el protocolo de sincronización existente y su cola offline; los datos locales permanecen separados de cada cuenta. No se requiere ninguna migración de Supabase.

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


## Sincronización 0.4.0 · activada el 14 de noviembre de 2026

La implementación permite guardar cambios de cuenta sin conexión, enviarlos con operaciones idempotentes y resolver conflictos por campo. Un borrado remoto no se recrea. La cola, los recibos y la base remota se guardan juntos en la copia privada de esa cuenta; una edición sigue disponible aunque la red esté esperando. WorkManager 2.11.2 solicita envío con red y conserva los pendientes cuando necesita reintentar o renovar acceso. Los conflictos detienen la cola y permanecen al cerrar/reabrir. Cerrar sesión conserva los pendientes para la misma cuenta.

Las marcas de hábitos guardan su día original y sus revisiones de disponibilidad/ajustes; una creación o activación pendiente puede preceder a la marca. No se reutiliza el registro de pruebas de Room como operaciones remotas. Véase [el contrato y sus decisiones de persistencia](../docs/sync-protocol.md).

La versión 0.4.0 está instalada como actualización en el Poco. Se publicaron web/backend compatibles mediante la PR #26 y se aplicó únicamente `20261114133419_offline_sync.sql` tras autorización y confirmación de copia privada. Las filas existentes y sus políticas se conservaron. Pulsa «Actualizar» para descargar el protocolo 1 y habilitar los cambios de cuenta. Recarga también las pestañas anteriores de la web. Véase la evidencia de activación en `../DEPLOYMENT.md`.

Verificación del 2026-11-14:

- Compilación de ambos APK y lint correctos, sin errores.
- `OK (17 tests)` en el POCO X7 Pro / Android 16. Se verifican pérdida de respuesta con recuperación del mismo UUID, reapertura con cola y conflictos, mezcla de campos independientes, edición durante una espera HTTP sin pérdida, resolución explícita con nueva operación, eliminación remota sin recreación, día original de un hábito creado offline y base de disponibilidad al pausarlo antes del primer envío. Se mantienen las pruebas anteriores de migración y persistencia. Informe local generado: `app/build/reports/device/poco-sync-tests.txt`.
- 16 pruebas JVM del núcleo previamente verificadas y sin cambios en este incremento.
- Backend: 185 pruebas correctas y smoke Gunicorn/health/401/CORS en Linux correcto.
- Web: build y pruebas de sincronización (3), calendario (35) y despliegue (4) correctos.
- PostgreSQL 17 aislado: conservación de filas, RLS/privilegios, recibos, combinaciones/conflictos, concurrencia de dos conexiones, eliminaciones, contexto histórico, creación offline, cambios de hora e historial de más de 1.000 registros correctos.
- PostgREST real con JWT de prueba: lectura/escritura autenticadas, recuperación de recibo HTTP, separación de usuarios, rechazo anónimo y bloqueo de CRUD antiguo correctos.

Las pruebas instrumentadas usan transportes simulados y bases privadas de pruebas; las de PostgreSQL/PostgREST usan contenedores desechables. Además, el contrato ya está activo y el usuario confirmó el 2026-11-14 que su tarea desechable creada desde el Poco aparece en la web. Queda verificado móvil→Supabase→web con sesión real. También confirmó que la edición del título en la web llega al Poco al actualizar: quedan verificadas ambas direcciones con sesión real. Los conflictos siguen verificados mediante las pruebas aisladas descritas arriba.


## Calendario · 0.5.0

Diseño aprobado en [la ampliación de calendario](../docs/android-calendar-planning.md), Q12 del 2026-11-16.

La sección Calendario incorpora Día, Semana y Mes, con alternativa Agenda para día/semana. Empieza en la semana horaria y recuerda la vista y el filtro por cuenta en el teléfono. Mantiene Anterior/Hoy/Siguiente y añade selector directo de fecha. La semana se desplaza horizontalmente; las tareas simultáneas tienen columnas propias y las de todo el día se apilan. El mes muestra títulos abreviados y abre la agenda completa de cada día.

Mantener pulsada una tarjeta permite moverla; la previsualización indica la fecha y horario que se guardarán al soltar. El arrastre ajusta a quince minutos, conserva duración y permite pasar entre horario y todo el día; en mes solo cambia la fecha. Volver cancela el movimiento. El guardado ofrece «Deshacer», sujeto a que la tarea no haya cambiado desde el movimiento. Las horas permanecen dentro de una fecha y los límites del día se ajustan conservando duración. Tocar una tarjeta abre el formulario con su título completo y todas las acciones disponibles.

Crear desde una celda conserva su fecha/hora y desde una banda de todo el día conserva su fecha. El botón general sigue proponiendo hoy. Las agendas incluyen también tareas fuera del horario visible y la cuadrícula ofrece un acceso explícito a ellas. Se mantienen completadas y tareas de proyectos inactivos/completados; hábitos y tareas sin fecha no aparecen.

Ajustes permite editar inicio/fin del horario y lunes/domingo. Con cuenta, se guardan offline en la cola existente y se comparten con la web; el modo local mantiene sus ajustes propios. El primer guardado sin fila remota usa los valores predeterminados del protocolo como base de comparación. Editar estos campos conserva el reinicio de hábitos. No requiere migración de Supabase, despliegue web/backend ni cambios en datos compartidos para su instalación.

Verificación del 2026-11-16:

- 23 pruebas JVM correctas, incluidas siete nuevas de calendario: límites de semana/mes/año, creación al final del día, duración y conversiones, rango visible, solapamientos y validación de ajustes.
- 23 pruebas instrumentadas correctas en el POCO X7 Pro (Android 16). Las tres nuevas verifican preferencias sin fila previa y reapertura de la cola, conservación del reinicio de hábitos y resolución de conflictos, y rechazo de deshacer sobre tareas modificadas o eliminadas. Usan bases desechables y transporte simulado.
- Compilación final de la aplicación y del APK instrumentado correcta; lint final sin errores. El APK final se instaló como actualización y se repitieron las 23 pruebas instrumentadas correctamente.
- Actualización instalada conservando la firma debug anterior y los datos. La app arrancó con la cuenta existente; la jerarquía de accesibilidad confirmó Calendario, Día, Semana, Mes, Ajustes y Mostrar agenda. Se revisó la captura inicial y se redujo la banda vacía de todo el día.
- Queda pendiente la comprobación interactiva completa de gestos, desplazamiento, agendas anidadas, TalkBack y sincronización de ajustes entre web y móvil con cuenta real. No se han modificado datos de cuenta para simular estas comprobaciones. La presencia de controles y las pruebas de persistencia no sustituyen esa revisión.

Para repetir los checks en entornos donde el daemon Kotlin no puede escribir en su caché de usuario, pasar `'-Pkotlin.compiler.execution.strategy=in-process'` a Gradle. Conservar la clave debug existente al instalar actualizaciones; cambiar `ANDROID_USER_HOME` puede generar otra clave e impedir instalar con `-r`. El APK firmado con la clave previa de este equipo se entrega como `app/build/outputs/apk/debug/studycalendar-0.5.0-debug.apk`.


## Arrastre continuo · 0.5.1

La tarjeta se eleva al mantener pulsado y sigue el dedo conservando el punto de agarre. Una capa por encima del calendario permite cruzar columnas sin recortar la tarjeta; su posición se actualiza mediante transformaciones gráficas. El origen queda atenuado y el destino se resalta, con el horario propuesto en una etiqueta flotante que no desplaza el calendario.

Al soltar, la tarjeta espera el resultado del guardado local y aterriza con una transición de 150 ms, respetando los ajustes de animación del sistema. Un destino inválido o una cancelación no escribe datos. Los errores de guardado devuelven la tarjeta al origen disponible, o la desvanecen si ese estado ya no existe. La operación y «Deshacer» conservan la validación y sincronización existentes. El desplazamiento en bordes usa el reloj de fotogramas y una velocidad proporcional a la proximidad del dedo.

Verificación del 2026-11-16:

- Compilación final de la aplicación y APK instrumentado correcta; lint sin errores.
- 23 pruebas JVM correctas (sin cambios de dominio en esta versión).
- `OK (31 tests)` en el POCO X7 Pro con Android 16: incluye ocho pruebas Compose nuevas de seguimiento del dedo, desplazamiento de los límites renderizados de la tarjeta, cancelación sin escritura, cambio de día con una sola escritura, error de guardado, destino inválido, conservación del horario y duración, conversiones y desplazamiento en bordes. Las pruebas usan tareas y preferencias desechables, separadas de la cuenta y de `local-preview.db`.
- APK final 0.5.1 instalado como actualización con la firma debug existente; conserva los datos. Archivo local: `app/build/outputs/apk/debug/studycalendar-0.5.1-debug.apk`.

Las pruebas de gestos se ejecutan con `:app:connectedDebugAndroidTest` o con el runner de instrumentación tras instalar los APK de aplicación y pruebas con la misma firma. Las nuevas dependencias de pruebas Compose usan el BOM ya fijado; la actividad de pruebas se incorpora únicamente a la variante debug.


## Interfaz Android · 0.6.0

La app abre «Todas las tareas» y ofrece cuatro destinos principales: Tareas, Calendario, Proyectos y Rutinas. Configuración reúne cuenta, sincronización, preferencias de calendario y categorías. Un estado compacto conserva visibles los cambios pendientes, errores y conflictos. Cada pantalla conserva su desplazamiento; volver de configuración o de un proyecto restaura el contexto. Cambiar de cuenta descarta borradores y acciones transitorias de la cuenta anterior.

«Todas las tareas» usa la colección completa y muestra tareas personales pendientes en Atrasadas/Hoy/Próximas, tareas fechadas de proyectos activos y rutinas de hoy. Trabajo sin fecha y tareas de proyectos inactivos/completados quedan fuera de esta vista y del contador. El contador incluye los hábitos pendientes y muestra un marcador durante la carga. Las secciones recuerdan su apertura por cuenta y en modo local. La agenda y el historial de proyectos conservan sus criterios anteriores.

Las tarjetas muestran fechas relativas, acciones secundarias en un menú y los títulos de los requisitos pendientes. Completar o desmarcar ofrece «Deshacer». Los comandos de finalización comprueban el estado esperado dentro de la transacción local o de la edición protegida de la réplica: conservan otras modificaciones y rechazan estados obsoletos, restricciones de dependencias y fechas de hábitos de días anteriores. La cola remota y su contrato no cambian.

Los formularios usan selectores nativos de fecha y hora de 24 horas; al cambiar el inicio se conserva la duración hasta el límite del día. El estado del proyecto es una selección única. El formulario de tareas sigue conservando el borrador al crear un proyecto anidado y permite consultar detalles en modo de solo lectura. El calendario compacta su barra, adapta la anchura de día y la altura disponible, y conserva el arrastre y las agendas accesibles. Se centralizan colores y tipografía en el tema Android.

No requiere migración remota ni despliegue de web/backend. La versión debug conserva el identificador `.dev` y usa la actualización habitual, sin borrar los datos de la app.


### Verificación de interfaz 0.6.0

- Núcleo Kotlin: 27 pruebas correctas, incluidas cuatro nuevas de visibilidad/contador, orden, cambio de día y duración.
- Compilación web correcta y 185 pruebas backend correctas. El aviso de tamaño de bundle web y el aviso de caché pytest no impidieron las comprobaciones.
- Primera compilación Android: APK debug y APK instrumentado correctos; lint con 0 errores, 26 avisos y una sugerencia. Se usó compilación Kotlin en proceso por restricciones del daemon local.
- La primera ejecución instrumentada incluyó seis fallos de calendario mientras el teléfono estaba bloqueado y un fallo del selector de la nueva prueba del badge. Tras desbloquear, las ocho pruebas de arrastre pasaron. Se añadió una descripción accesible del estado de carga/contador y se corrigió el selector de prueba.
- La instalación de actualización requiere la misma firma debug anterior. Se comprobó el certificado del APK instalado y se firmó con la clave debug existente del perfil del usuario, conservando datos; no se desinstaló la aplicación.

- Verificación final en POCO X7 Pro / Android 16: **OK (44 tests)**, incluidos nueve casos Compose de interfaz y ocho de arrastre, más persistencia, migraciones, sincronización, conflictos y ajustes. Se probaron el contador accesible, filtros, completar/deshacer, requisitos, selección exclusiva de estado, borrador con proyecto anidado, consulta sin escritura, texto al 200 % y calendario en un espacio de 320 × 360 dp. El primer fallo del badge quedó corregido y las seis pruebas afectadas por el bloqueo pasaron en la ejecución final completa.
- Compilación y lint finales correctos: **0 errores, 26 avisos y una sugerencia**. APK actualizado instalado con `adb install -r --no-incremental` y arranque verificado. Artefacto con la firma compatible: `app/build/outputs/apk/debug/studycalendar-0.6.0-debug.apk`.
- Captura revisada con datos desechables: `app/build/frontend-overview.png`. Informes locales ignorados por Git: `build-check.log.local`, `rebuild.log.local` y `final-device-tests.log.local`. Las pruebas usan cuentas simuladas y bases exclusivas/en memoria; no verifican escrituras nuevas contra datos reales de producción. La revisión de TalkBack en uso real y rotación física queda como comprobación manual adicional; las pruebas de tamaño y texto no la sustituyen.
