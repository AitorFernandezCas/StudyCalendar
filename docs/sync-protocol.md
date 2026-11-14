# Sincronización de StudyCalendar · protocolo 1

Estado: implementación preparada; la migración compartida y la publicación coordinada todavía no se han ejecutado. El APK 0.4.0 conserva la consulta de cuenta cuando el servidor no anuncia protocolo 1. No habilita escrituras por sustituir llamadas CRUD en tablas.

## Operaciones y conflictos

`Task.sync_apply(p_operation)` recibe `protocol`, `operation_id`, `entity`, `id`, `action`, `base`, `changes` y `context`. La sesión JWT determina el usuario; el cliente no elige `user_id`. Las entidades admitidas son tareas, proyectos, categorías, rutinas, completados y ajustes. Los campos y acciones admitidos están enumerados en SQL.

Cada operación conserva su UUID y contenido después del primer envío. El servidor serializa operaciones del mismo usuario con un bloqueo transaccional, recupera recibos existentes y rechaza reutilizar un UUID con otro contenido. Una respuesta perdida se recupera reenviando exactamente esa operación. No se deduce una creación de la ausencia de una fila.

Las ediciones guardan el valor base de los campos realmente modificados. Dentro de la transacción se compara base/remoto/local: campos independientes se combinan; un mismo campo cambiado de forma incompatible devuelve `conflict`. La combinación vuelve a pasar las restricciones y reglas de dominio. Una combinación inválida devuelve `rejected`, conserva la operación en el teléfono y detiene el envío. Un borrado exige la revisión exacta conocida y presenta el estado actual si cambió. Las modificaciones de tareas también invalidan la revisión de su proyecto/categoría para revisar sus efectos antes de borrar.

Los recibos y el historial no caducan automáticamente. `sync_entities` conserva revisiones y marcas de borrado; `sync_history` conserva los estados anteriores y la disponibilidad de hábitos. Un identificador eliminado no puede reutilizarse. Las resoluciones crean otra operación: el recibo original permanece inmutable. La pantalla permite elegir por campo o descartar explícitamente el cambio; no ofrece recrear automáticamente un elemento eliminado ni eludir validaciones.

## Fechas de hábitos

Las marcas llevan día original, hora de reinicio, instante de registro y revisiones de rutina/ajustes. El servidor valida Europe/Madrid, el día calculado, el período original y su historia de disponibilidad. Se conserva una marca registrada antes de una pausa posterior, incluida una pausa del mismo día que eliminó el período abierto. Crear/activar una rutina offline puede servir como dependencia explícita de una marca posterior: el servidor comprueba el recibo de esa creación/activación. Las activaciones offline conservan su día, con comprobación de solapamientos.

Las fechas futuras y contextos incompatibles se rechazan; no se trasladan al día de sincronización. La hora del dispositivo no constituye una prueba inviolable de cuándo ocurrió la acción. El cálculo de reinicio utiliza el primer instante repetido y avanza una hora inexistente hasta el primer minuto válido. Se prueba frente a los cambios estacionales de Madrid.

## Persistencia y envío en Android

Room conserva la vista previa local anterior. Los datos de cuenta de 0.3.0 ya utilizaban una copia JSON privada por UUID. La versión 0.4.0 amplía ese documento con base remota, cola y recibos: se valida, se escribe con `AtomicFile` y solo después se publica en pantalla. De este modo, el cambio visible y la operación pendiente se confirman juntos; no se envía el antiguo registro de Room como CRUD remoto ni se asocia automáticamente la vista previa a una cuenta.

El agregado por cuenta mantiene la compatibilidad con las copias de 0.3.0. Es una decisión de esta implementación personal; escribir/leer el documento completo tiene un coste creciente. No equivale a haber migrado la cuenta a un conjunto de tablas Room. Una futura sustitución deberá migrar cola y recibos de forma atómica, conservar la identidad de operaciones y superar las mismas pruebas.

La espera HTTP utiliza un bloqueo distinto del almacenamiento local. Las ediciones pueden guardarse mientras la red está en espera; la respuesta se aplica sobre la copia más reciente sin borrar operaciones recién añadidas. El indicador distingue pendientes, conflicto y error de conexión. Un conflicto detiene la cola y conserva lo que viene detrás. Cerrar sesión conserva los cambios por UUID y otra cuenta no puede enviarlos.

WorkManager 2.11.2 solicita red disponible y usa espera exponencial. Los reintentos automáticos se limitan; ese límite no elimina operaciones. Volver a entrar, abrir la aplicación o sincronizar manualmente permite recuperarlas. No se promete envío inmediato en segundo plano. La sesión se renueva antes de enviar; si no puede renovarse, la copia y la cola siguen disponibles.

`sync_snapshot` devuelve una instantánea SQL completa de los datos del usuario, incluidos todos los completados; su agregación no está truncada por la paginación de tablas REST. Las escrituras y lecturas del mismo usuario participan en el bloqueo. Para la escala personal actual se descarga la instantánea completa. La consulta anterior paginada sigue disponible como compatibilidad mientras no exista el nuevo contrato.

## Web y Flask

Flask añade `/api/sync/snapshot` y `/api/sync/apply` con autenticación y repositorio ligado al token de cada petición. Dominio y aplicación no importan Flask ni SDKs. La web mantiene sus rutas y lectores públicos; cuando existe protocolo 1, sus lecturas obtienen la instantánea autenticada y sus escrituras pasan por el contrato.

Los formularios conservan la base original, aunque haya un refresco mientras se editan. Se envían solo los campos cambiados, manteniendo `null` explícito. Los intentos web conservan identidad y contexto en `sessionStorage` hasta una respuesta definitiva. La UI de conflictos conserva borradores ajenos al elemento resuelto y utiliza el diálogo compartido con control de foco. Las pestañas antiguas reciben un error que pide actualizar; no disponen de una escritura incondicional que anule el contrato.

## Privilegios

Las tres tablas de sincronización tienen RLS y SELECT del propio usuario. Los clientes no pueden escribir revisiones, historial o recibos. La función de entrada y los auxiliares internos de captura necesitan privilegios del propietario para mantener ese registro protegido de forma transaccional. Son `SECURITY DEFINER` con `search_path=pg_catalog`; todos los accesos se filtran por el usuario autenticado y todos los identificadores dinámicos proceden de listas cerradas. No se utiliza el definidor para sortear un error de RLS. Los auxiliares internos no tienen EXECUTE para PUBLIC/anon/authenticated; solo la entrada autenticada recibe ese permiso. Las escrituras de negocio pasan además por validadores de pertenencia, horarios, tipos, proyectos, períodos y dependencias.

La migración comprueba relaciones históricas entre propietarios antes de cambiar nada. Bloquea las tablas `Task` durante la preparación de su estado inicial, preserva sus filas y activa guardas en todas las rutas de escritura. No modifica Auth global, FinanceTracker, la función existente de rachas, planes o servicios de pago.

## Verificación reproducible

- `python backend/check_sync_db.py`: PostgreSQL 17 desechable, con etiqueta obligatoria `studycalendar.test=offline-sync`. Verifica preservación de datos, operaciones idempotentes, mezcla/conflicto/resolución, RLS, guardas de clientes antiguos, historial protegido, concurrencia con dos conexiones reales, eliminación atómica, borrados, marcas históricas, creación offline, DST e historial superior a 1.000 filas. Solo reinicializa `studycalendar_sync_test` en ese contenedor; no lee `DATABASE_URL`.
- `python backend/check_sync_http.py`: requiere la fixture anterior y verifica JWT, perfiles de esquema y RPC con PostgREST real sobre una red Docker de pruebas. No usa tokens reales ni Supabase remoto.
- `python -m pytest backend -q`: regresión y contrato HTTP con el SDK instalado.
- `npm --prefix frontend run test:sync`, `test:calendar`, `test:deployment` y `build`.
- Gradle `:core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`; ejecutar los APK de pruebas en dispositivo. El informe generado de la cola está en `android/app/build/reports/device/poco-sync-tests.txt`.

Las pruebas aisladas y los transportes simulados no se presentan como escrituras autenticadas verificadas en producción. La activación y su prueba compartida requieren el procedimiento de `DEPLOYMENT.md`.

Antes de publicar se actualizó Vite de 8.3.2 a 8.3.8 (parche de la misma línea) por el aviso real de seguridad detectado al instalar el lockfile. La versión queda fijada y se verificaron build y pruebas de sincronización con las dependencias de CI.
