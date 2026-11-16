# Versión Android de StudyCalendar

Estado: diseño aprobado; implementación iniciada. Fecha: 2026-11-13.
El usuario confirmó Q27 y pidió comenzar la implementación. Las decisiones de la entrevista y la propuesta técnica quedan aprobadas. El avance concreto se registra en `android/README.md`; esta aprobación no equivale a afirmar que todas las funcionalidades estén terminadas.

Ampliación solicitada el 2026-11-13: incorporar rutinas y proyectos a la vista previa local instalada en el Poco. Incluye gestión de definiciones, estados de proyectos, tareas asociadas con o sin fecha, completados diarios y rachas. Esta solicitud adelanta la gestión local básica; el diagrama avanzado y la sincronización siguen pendientes. La actualización debe conservar la base local existente.

## Decisiones confirmadas

Ampliación de calendario del 2026-11-16: el usuario ha confirmado vistas día/semana/mes, agenda alternativa, creación contextual, arrastre y ajustes de calendario compartidos. [El diseño de calendario](android-calendar-planning.md) recoge Q1–Q11 y la propuesta consolidada aprobada en Q12. Esta ampliación retoma el trabajo aplazado en el alcance inicial descrito a continuación; Q12 autoriza comenzar la implementación y el avance se registra en `android/README.md`.

- Aplicación nativa Android en Kotlin, inicialmente para uso personal.
- Convivirá con la web y utilizará la misma cuenta, tareas, proyectos y hábitos.
- La primera versión se centra en consultar la agenda, crear y completar tareas y marcar hábitos.
- La gestión avanzada de proyectos y su diagrama quedan para una fase posterior.
- Los recordatorios mediante notificaciones son la primera capacidad nativa prioritaria.
- Se podrán crear tareas y marcar hábitos sin conexión, sincronizando los cambios cuando vuelva internet.
- Una marca de hábito pendiente de sincronización conservará el día al que correspondía aunque llegue al servidor otro día, recalculando las rachas. No se incorpora por defecto la edición libre del historial.
- Los cambios contradictorios entre web y móvil requerirán elegir qué conservar, manteniendo el cambio pendiente. Se combinarán automáticamente las ediciones de campos distintos; las modificaciones contradictorias del mismo campo mostrarán ambas versiones. La combinación y la elección manual deberán respetar las reglas de negocio.
- Los recordatorios iniciales serán un aviso opcional por tarea y un aviso diario configurable por hábito, omitido cuando el elemento ya esté completado. El aviso del hábito es independiente de su definición y no lo incorpora al calendario.
- La agenda inicial será una lista por días con selector de fecha, tareas con hora y de todo el día, y una sección propia para hábitos. Las cuadrículas de semana/mes y el arrastre quedan para después.
- Se podrán editar título, fecha y horario de tareas, y deshacer su finalización, también sin conexión y respetando dependencias.
- La creación y administración de proyectos, categorías y definiciones de hábitos seguirán inicialmente en la web.
- El dispositivo objetivo inicial es el Poco X7 Pro con Android 16, según indica el usuario. Tabletas y otros formatos quedan para después.
- La prioridad actual es la funcionalidad móvil. Android ejecutará sus operaciones locales en Kotlin, persistirá los datos en el teléfono y sincronizará directamente con Supabase, sin pasar por Flask ni depender de Render para ese intercambio.
- Flask se mantiene inicialmente para la web. Se adaptarán las validaciones compartidas y las escrituras web donde sea necesario para preservar consistencia y detectar conflictos. No se ha acordado retirar el servicio Render de la web.
- Tras la carga inicial con conexión, se conservará una copia local de las tareas, los proyectos y el historial de hábitos para trabajar varios días sin internet. Los cambios pendientes se conservarán hasta poder enviarlos.
- Una marca offline de un hábito seguirá siendo válida aunque el hábito se pause posteriormente, siempre que el día marcado perteneciera a un período válido. Si se eliminó el hábito o la tarea, el cambio pendiente se mostrará como conflicto sin recrear automáticamente el elemento.
- Android mantendrá `Europe/Madrid` y la hora de reinicio de hábitos de la cuenta aunque cambie la zona horaria del teléfono. Cambiar la zona del dispositivo no debe reasignar las marcas ni alterar por sí solo las rachas.
- La primera versión permite crear tareas personales y tareas asociadas a proyectos activos existentes, y consultar/completar las tareas con fecha. Las tareas bloqueadas indicarán sus requisitos pendientes. El trabajo sin fecha y la gestión avanzada del proyecto permanecerán inicialmente en la web.
- Se prioriza programar los recordatorios para la hora elegida, solicitando acceso para alarmas precisas cuando sea necesario. Se reconocen las restricciones de Android y no se garantiza puntualidad absoluta.
- Las notificaciones permitirán «Completar» y «Posponer 10 minutos», también sin conexión. Completar respetará las reglas de la tarea y persistirá la operación pendiente; posponer solo afecta al aviso, sin mover la tarea ni cambiar el día del hábito.
- Los recordatorios se configurarán desde Android y se guardarán asociados a la cuenta para recuperarlos al reinstalar o cambiar de teléfono. Seguirán separados de los horarios de las tareas y de la definición de hábitos.
- Las acciones registradas offline no tendrán una caducidad arbitraria. Conservarán el día calculado al registrarse, aunque después cambie la hora de reinicio; deberán corresponder a períodos válidos y superar las comprobaciones de sincronización. No se reasignarán retroactivamente.
- Si se permiten notificaciones pero no alarmas precisas, se usarán avisos aproximados y se indicará. Si se deniegan las notificaciones, se mostrará que los recordatorios están desactivados. Tras un apagado, los avisos vencidos recuperables se agruparán en un resumen de pendientes, excluyendo los elementos completados o eliminados conocidos localmente.
- Pulsar «Completar» en un aviso de hábito cuyo día ya terminó abrirá la aplicación para mostrar el estado actual sin marcar automáticamente el día anterior ni el actual. Esto es distinto de enviar tarde una acción que sí se registró durante el día correspondiente.
- Los recordatorios estarán desactivados hasta que se configuren. En tareas con hora, el aviso será relativo al inicio y sugerirá diez minutos antes; acompañará los cambios de horario. Para tareas de todo el día se elegirá una hora de esa fecha; para hábitos, una hora diaria. Los avisos usarán Europe/Madrid y posponer uno no alterará la configuración de los días siguientes.
- Android permitirá eliminar tareas también offline, con confirmación y prohibiendo el borrado de tareas con enlaces de dependencia. Un cambio remoto de la tarea antes de sincronizar el borrado producirá un conflicto.
- La entrega inicial será un APK privado firmado, con instalación y actualizaciones manuales desde el ordenador, sin publicación inicial en Google Play. Las actualizaciones conservarán la identidad y firma de la aplicación.
- Se utilizará la cuenta actual: primer acceso y descarga con internet, seguido de uso local aunque la sesión necesite renovarse antes de sincronizar. La caducidad de la sesión no borrará cambios pendientes. Reinstalar recuperará lo sincronizado; los cambios exclusivamente locales podrían perderse al desinstalar.

## Arquitectura acordada

El usuario acepta Kotlin con persistencia local y sincronización directa con Supabase para priorizar la autonomía del móvil. Véase [ADR 0004](adr/0004-android-local-operations-direct-supabase-sync.md).

La solución separa las operaciones locales de la sincronización. El contrato directo con Supabase debe conservar y completar las validaciones centrales de negocio; no equivale a sustituir sin más las llamadas HTTP por escrituras en tablas. Room es la propuesta de persistencia local explicada durante la entrevista; los detalles de implementación se concretarán al cerrar el diseño.

Mover el proceso Flask al teléfono sin cambiar sus repositorios mantendría las llamadas a Supabase y no resolvería el funcionamiento sin conexión. La propuesta local requiere portar las reglas necesarias a Kotlin, persistir datos y cambios pendientes, y reconciliarlos después con el estado compartido.

| Alternativa considerada | Operaciones diarias en el teléfono | Sincronización | Consecuencia |
| --- | --- | --- | --- |
| Kotlin y base local, conservando Flask | Locales | API Flask en Render hacia Supabase | Reutiliza más backend; las operaciones locales pueden continuar mientras Render no está disponible, pero la sincronización espera. |
| Kotlin y base local, con contrato directo en Supabase (elegida) | Locales | API/funciones de Supabase | Android deja de necesitar Render; exige implementar las validaciones compartidas y el protocolo de sincronización en Supabase. La web puede mantener Flask durante la transición, adaptándolo donde sea necesario para respetar el nuevo contrato. |

La solución conserva la necesidad de conexión para intercambiar cambios con la web. Retirar también el backend Render de la web es una decisión adicional. Las escrituras web deberán participar en la detección de conflictos. Los cambios locales pueden requerir reconciliación cuando el estado compartido haya cambiado; las restricciones de negocio siguen siendo obligatorias.

Referencias oficiales consultadas para evaluar estas alternativas:

- [Arquitectura Android offline-first](https://developer.android.com/topic/architecture/data-layer/offline-first).
- [Persistencia local con Room](https://developer.android.com/training/data-storage/room).
- [Ciclo de vida de procesos Android](https://developer.android.com/guide/components/activities/process-lifecycle).
- [Programación de alarmas y permisos](https://developer.android.com/develop/background-work/services/alarms).
- [Data API de Supabase](https://supabase.com/docs/guides/api).
- [Funciones de base de datos](https://supabase.com/docs/guides/database/functions) y [controles de acceso](https://supabase.com/docs/guides/api/securing-your-api).

## Contexto comprobado en el repositorio

- La web usa Supabase Auth y envía el token a la API Flask. Las reglas de negocio existentes se ejecutan en el backend: `frontend/src/lib/api.ts`, `frontend/src/lib/supabase.ts` y `backend/studycalendar/`.
- No se ha encontrado una implementación Android, una cola persistente de sincronización offline ni un sistema de notificaciones en las partes investigadas.
- `RoutineService.complete` rechaza con conflicto las operaciones cuya fecha no coincide con el día vigente. Aceptar marcas offline después del cambio de día necesita una decisión de producto y cambios de contrato; no basta con reenviar la petición actual.
- El código actual calcula el día de hábitos con `routine_reset_time`, una preferencia por usuario. No debe asumirse que el cambio ocurre siempre a medianoche: `backend/studycalendar/routines/application.py` y `backend/studycalendar/preferences/domain.py`.
- Las tareas de proyecto tienen restricciones de dependencias que también deben respetarse al sincronizar: `backend/studycalendar/tasks/planning.py`.
- Los identificadores de tareas, categorías, proyectos, hábitos y sus registros históricos son UUID. No se han encontrado revisiones de concurrencia, un registro de operaciones idempotentes ni marcas persistentes de borrado. Los `updated_at` existentes no cubren todas las entidades ni constituyen el contrato de sincronización propuesto.
- El formulario web de tareas envía varios campos, incluidos estado de finalización y dependencias, aunque no todos hayan sido editados. Una pestaña antigua puede sobrescribir trabajo móvil si no conserva su versión base y envía únicamente los cambios intencionados: `frontend/src/App.tsx` y `frontend/src/lib/api.ts`.
- La creación/activación/pausa de hábitos, la propagación de color de categorías y algunas operaciones de preferencias usan varias escrituras separadas. El contrato nuevo debe coordinar sus efectos, versiones y resultados de sincronización de forma atómica; preservar solo las rutas HTTP no resuelve la concurrencia.
- Las comprobaciones SQL de planes de proyecto protegen varias reglas de dependencias y propiedad. Las relaciones de categoría y los registros hijos de hábitos necesitan revisar que se conserva la propiedad entre entidades al permitir escrituras desde Android; la propiedad de cada fila por separado no sustituye esa validación.
- Una pausa actual puede cerrar el período en ayer o eliminar uno iniciado hoy. Para evaluar una marca offline frente a su contexto original se necesita historia de disponibilidad; comparar solo con los períodos actuales puede perder esa información. Las pausas del mismo día son un caso explícito de validación del contrato.
- La infraestructura debe conservar los planes gratuitos y la separación de usuarios. Supabase es compartido; siguen vigentes las restricciones de migraciones, autenticación y despliegue de `AGENTS.md` y `DEPLOYMENT.md`.
- Estas observaciones describen el código local; no verifican qué versión está publicada.

## Árbol de decisiones y cierre

1. Datos compartidos: Kotlin local y Supabase directo confirmados; Flask sigue para la web. Combinación por campos y resolución explícita de conflictos confirmadas.
2. Uso diario: agenda por días, CRUD de tareas con fecha, proyectos existentes, requisitos visibles y hábitos confirmados. Gestión avanzada y trabajo sin fecha quedan en la web.
3. Recordatorios: configuración recuperable, horarios, precisión, permisos, acciones y tratamiento de avisos antiguos confirmados.
4. Trabajo offline: copia local, fechas de hábitos, pausas, eliminaciones, acciones antiguas y cambios de ajustes confirmados.
5. Entrega personal: Poco X7 Pro con Android 16, APK privado, actualizaciones manuales y sesión offline confirmados.
6. Cierre confirmado: propuesta técnica, etapas y criterios de aceptación aprobados en Q27. Los detalles de bibliotecas, contratos SQL y versiones se verificarán al implementar; las decisiones de producto anteriores no se reabren por defecto.

## Diseño técnico aprobado

### Aplicación Android

- Añadir `android/` al repositorio actual, separado de `frontend/` y `backend/`. Mantener la identidad visual y los textos en español, adaptando navegación y controles al teléfono.
- Kotlin y Jetpack Compose para la interfaz; Room para datos y operaciones locales persistentes. Capas de presentación, casos de uso de dominio, repositorios y adaptadores de almacenamiento/sincronización. Las reglas de dominio se probarán con relojes y repositorios sustituibles.
- La interfaz lee el estado local. Una edición y su operación pendiente se guardan juntas en una transacción local; un cierre de proceso no debe perder una acción que la aplicación ya dio por guardada.
- La copia local incluye categorías, proyectos, dependencias, períodos y completados necesarios para mostrar la agenda, validar tareas y calcular rachas. El trabajo sin fecha puede almacenarse para validar dependencias, aunque no tenga una pantalla propia en la primera versión. Las lecturas completas se paginan y no truncan historiales.
- Estado visible de cambios pendientes, conflictos y errores; sincronización al abrir o recuperar uso de la aplicación, acción manual y trabajo diferido con WorkManager cuando exista conexión. El sistema puede retrasar el trabajo en segundo plano: no se promete sincronización instantánea con la web.
- AlarmManager y notificaciones para recordatorios; WorkManager se reserva para trabajo diferido, no para fijar la hora de un aviso. Las programaciones se reconstruyen desde datos persistidos tras reinicios o cambios de permisos, y se recalculan cuando cambia una tarea o se completa un elemento.
- Sesión y datos locales separados por usuario. Renovar una sesión no cambia el dueño de las operaciones pendientes. Un cierre explícito de sesión debe proteger frente a la pérdida involuntaria de cambios; otra cuenta no debe verlos ni enviarlos.

### Contrato de sincronización compartido

- Diseñar operaciones autenticadas en Supabase que validen pertenencia, categorías/proyectos, horarios, dependencias y hábitos. Aplicar RLS, permisos mínimos y funciones con derechos del llamante siempre que sea posible. La APK solo contiene configuración pública; ninguna clave privilegiada.
- Cada operación tendrá un identificador estable y un resultado recuperable. Reenviar una operación tras perder su respuesta no puede crear dos tareas ni aplicar dos veces una acción. Esto requiere un contrato idempotente; no se repetirán a ciegas las peticiones de escritura actuales.
- Conservar una versión base y los campos modificados. Comparar base, estado remoto y cambio local dentro de una operación transaccional para combinar cambios compatibles o devolver un conflicto. Volver a validar el resultado combinado: campos distintos también pueden producir una combinación inválida.
- Diferenciar conflictos de datos y errores temporales de conexión. Un proyecto o categoría eliminado o que dejó de admitir una asociación requiere corregir la operación pendiente; no se recrea ni se reasigna silenciosamente. Una tarea existente puede preservar su proyecto inactivo/completado conforme a las reglas actuales.
- Registrar cambios de versión y eliminaciones de todas las rutas de escritura, incluida la web. Una ausencia de fila remota no debe confundirse con una creación nueva, y los borrados no deben revertirse durante una sincronización tardía.
- Las modificaciones indirectas (categoría eliminada o recoloreada, borrado de proyecto, cambios de períodos y completados) publican sus revisiones y efectos junto con la operación original. Las escrituras directas permitidas deberán cumplir las mismas invariantes mediante restricciones/triggers o quedar fuera del acceso de los clientes; añadir RPC y dejar una ruta que eluda sus garantías no es suficiente.
- Las operaciones offline no caducan por una limpieza automática del historial de sincronización. El diseño debe conservar la información necesaria o realizar una reconciliación completa que preserve los cambios pendientes; cualquier política de retención debe respetar este requisito.
- Una marca de hábito lleva su día original y el contexto temporal con que se registró. Validar usuario, existencia y período aplicable y rechazar fechas futuras o combinaciones inválidas. La fecha indicada por el teléfono no autentica al usuario ni demuestra por sí sola cuándo ocurrió la acción; no se promete una prueba inviolable de registro offline.
- Mantener historia/versiones de la disponibilidad y de los ajustes temporales suficientes para comprobar el contexto original, incluso ante una pausa del mismo día o un cambio de reinicio. Cuando exista una contradicción de disponibilidad no resuelta, conservar la operación y mostrar el conflicto; no descartar la marca por comparar exclusivamente con la disponibilidad actual.
- Mantener las reglas de rachas coherentes entre el cálculo local y el cálculo central; las marcas pendientes pueden producir una vista local provisional que se reconcilia al sincronizar. Los recordatorios también se basan en lo conocido localmente y pueden no conocer aún una finalización hecha en la web.
- Guardar la configuración de recordatorios separada de las definiciones de tareas y hábitos. La restauración desde la cuenta no concede permisos Android automáticamente: comprobarlos en cada instalación.
- Los clientes web y Flask deberán usar el contrato compatible de versiones y validaciones. Mantener Flask para la web no implica dejar intactas sus escrituras: de lo contrario podrían sobrescribir silenciosamente cambios del móvil o eludir reglas compartidas.
- Durante el despliegue coordinado, las pestañas web antiguas que no aporten la versión/contexto requerido deberán recibir un error que les pida actualizar, conservando el borrador cuando sea posible. No se mantendrá una vía de escritura incondicional que anule la detección de conflictos; las rutas y los comportamientos de negocio se preservan con el nuevo contrato.

### Entrega y validación

1. **Contratos y pruebas aisladas:** inventariar reglas existentes; definir revisiones, operaciones idempotentes, eliminaciones y funciones de sincronización; probar RLS y concurrencia en una base aislada. Preparar los ajustes web/Flask compatibles.
2. **Aplicación local:** crear la base Android y las pantallas de agenda, formularios y hábitos con persistencia y pruebas locales, utilizando datos desechables.
3. **Sincronización:** conectar autenticación, descarga paginada, envío de operaciones, combinación/conflictos y recuperación de sesión contra el entorno de pruebas; comprobar interoperabilidad con la web.
4. **Recordatorios:** configuración, programación, permisos, acciones offline, recuperación de reinicios y avisos antiguos; verificar en el Poco X7 Pro con Android 16.
5. **Entrega personal:** generar un APK de release firmado, documentar instalación y actualización manual, y custodiar la clave de firma fuera de Git con copia segura. Preparar copia de seguridad y despliegue coordinado de cambios compartidos según `DEPLOYMENT.md`; no aplicar migraciones automáticamente en CI o Render.

La secuencia prepara artefactos revisables antes de tocar producción. No se considera completa una etapa con solo pruebas de memoria cuando su contrato depende de PostgreSQL, del SDK o del comportamiento real de Android. Se mantienen los planes gratuitos y no se retira el backend web de Render.

## Criterios de aceptación

| Escenario | Resultado esperado |
| --- | --- |
| Primer inicio con la cuenta existente | Descarga paginada completa de datos necesarios, sin mezclar usuarios ni crear cuentas nuevas. |
| Crear/editar/completar/borrar una tarea en modo avión, cerrar y abrir la app | Estado y operaciones pendientes conservados; borrar exige confirmación y no permite enlaces de dependencia. |
| Restablecer conexión o perder la respuesta de una escritura | Sincronización recuperable sin duplicados ni pérdida de operaciones. |
| Editar título en móvil y hora en web desde una misma base | Combinación automática si el resultado cumple las reglas. |
| Editar el mismo campo o borrar una tarea modificada en la web | Conflicto visible con ambas versiones y sin sobrescritura silenciosa. |
| Modificar dependencias o borrar el elemento remotamente | La operación local no elude restricciones ni recrea automáticamente lo eliminado. |
| Marcar un hábito offline y sincronizar días después de una pausa | Conserva el día original si pertenecía a un período válido y recalcula las rachas. |
| Cambiar zona del teléfono, hora de reinicio o atravesar un cambio de horario estacional | Marcas pendientes estables; días, próximos avisos y rachas coherentes con la configuración acordada. |
| Denegar permisos, reiniciar el teléfono o completar un elemento | Estado de avisos comprensible, recuperación agrupada de vencidos y cancelación/reprogramación según datos locales. |
| Completar o posponer desde un aviso en modo avión | Acción persistente; posponer no mueve la tarea ni cambia la configuración recurrente. Un aviso de hábito de un día anterior abre la app sin completar. |
| Caducar la sesión durante trabajo offline | Datos intactos; el envío espera a una sesión válida de la misma cuenta. |
| Actualizar APK firmado o reinstalar | Actualizar conserva datos; reinstalar recupera lo sincronizado al autenticarse y no promete recuperar lo exclusivamente local. |
| Usar la web después de incorporar el contrato compartido | Se conservan reglas, propiedad de datos y funcionalidades; sus escrituras participan en conflictos y validaciones. |

Pruebas previstas: dominio Kotlin y fechas con reloj fijo; transacciones y migraciones Room; integración SQL de sincronización/RLS/concurrencia; regresión Flask y frontend; y pruebas de interfaz/avisos en Android 16. Las versiones concretas de herramientas y dependencias se fijarán y documentarán al crear el proyecto.

Referencias de implementación verificadas: [Jetpack Compose](https://developer.android.com/compose), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent), [cliente Kotlin de Supabase](https://supabase.com/docs/reference/kotlin/introduction), [ADB](https://developer.android.com/tools/adb) y [firma de aplicaciones](https://developer.android.com/studio/publish/app-signing).

## Registro de la entrevista

- Ronda 1: el usuario acepta las cuatro recomendaciones: misma información que la web, alcance diario inicial, recordatorios prioritarios y operaciones diarias sin conexión.
- Ronda 2: el usuario acepta Q6–Q11. Q5 sigue abierta: solicita estudiar la ejecución de la lógica en el teléfono en lugar de depender del backend Render.
- Aclaración de Q5: tras comparar alternativas y comprobar que la arquitectura permite alcanzar la funcionalidad web a largo plazo, el usuario acepta eliminar Flask del recorrido móvil. La prioridad es Android; la web conserva inicialmente su backend.
- Ronda 3: el usuario acepta Q12–Q18: copia local, conservación de marcas de períodos válidos tras pausas, conflictos ante eliminaciones, zona y reinicio compartidos, tareas con fecha de proyectos existentes, alarmas precisas, acciones desde avisos y recuperación de configuración de recordatorios mediante la cuenta.
- Ronda 4: el usuario acepta Q19–Q26: combinación por campos, conservación de fechas sin caducidad arbitraria, degradación visible de avisos, tratamiento de notificaciones de días anteriores, configuración inicial de avisos, borrado offline, APK privado y sesión/recuperación.
- Cierre conjunto: el usuario responde afirmativamente a Q27 y solicita comenzar la implementación. Los cambios compartidos se prepararán y verificarán antes de su aplicación o publicación, siguiendo las salvaguardas del repositorio.

Los términos de negocio nuevos se incorporarán a `GLOSSARY.md` cuando se acuerde su significado. Las decisiones arquitectónicas con alternativas y consecuencias relevantes se registrarán en `docs/adr/` al resolverlas.
