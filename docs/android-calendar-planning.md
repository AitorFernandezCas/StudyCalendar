# Calendario de Android

Estado: diseño aprobado e implementado; revisión interactiva completa pendiente. Inicio: 2026-11-15. Actualización: 2026-11-16.

El usuario solicita incorporar a Android la funcionalidad del calendario web. Esta petición retoma las cuadrículas y el arrastre aplazados en [el diseño Android](android-planning.md). Ha confirmado Q1 a Q12. El 2026-11-16 respondió «Yes, begin implementation», aprobando el diseño consolidado y el comienzo de implementación.

## Base comprobada

- Android muestra una agenda de una fecha con navegación anterior/hoy/siguiente. Ya permite crear, editar, completar/reabrir y eliminar tareas mediante las operaciones locales y la sincronización existentes.
- La web ofrece día, semana y mes, agenda y cuadrícula horaria, filtro por categoría, configuración de horario y movimiento de tareas. La adaptación de estas interacciones al teléfono requiere decisiones propias.
- El calendario conserva tareas completadas y tareas de proyectos inactivos/completados. Los hábitos y las tareas sin fecha quedan fuera. La nueva interfaz debe conservar estas reglas.
- Se mantienen las decisiones Android ya aprobadas sobre funcionamiento offline, sincronización, conflictos, aislamiento de cuentas y Europe/Madrid.
- El gráfico anual de actividad pertenece a «Todas las tareas» en la web; no forma parte de la pantalla del calendario.
- El protocolo compartido ya admite cambios de `calendar_start_time`, `calendar_end_time` y `week_start`, con validación y conflictos por campos. Android conserva el JSON descargado, pero necesita exponer estos campos y conectar su edición. Debe cubrirse el primer guardado cuando todavía no existe una fila de ajustes; no se requiere por estos campos una migración de Supabase.
- El horario visible puede terminar a las 24:00. Las tareas actuales deben empezar y terminar dentro de una misma fecha, con fin posterior al inicio; 24:00 no es una hora válida de tarea. Esta ampliación no introduce tareas que atraviesen medianoche.

Fuentes: `android/app/src/main/java/com/studycalendar/android/MainActivity.kt`, `android/app/src/main/java/com/studycalendar/android/CollectionForms.kt`, `frontend/src/App.tsx`, `frontend/src/components/CalendarTimeGrid.tsx` y [estado Android](../android/README.md).

## Decisiones confirmadas

Primera ronda: Q1 y Q2 aceptadas el 2026-11-16 mediante «Yes to both».

1. Vistas: incorporar día, semana y mes, conservando la agenda como alternativa.
2. Interacción: incluir en esta entrega creación desde una fecha/hora y arrastre para reprogramar tareas, incluidos cambios entre tareas con hora y de todo el día, además de los formularios existentes.

Segunda ronda: Q3 a Q7 aceptadas el 2026-11-16 mediante «Yes to all».

- Q3: abrir inicialmente la semana en modo horario y recordar en el teléfono la última vista y el modo agenda/horario; al abrir de nuevo, situarse en la fecha actual.
- Q4: mostrar la semana con columnas de anchura legible y desplazamiento horizontal, manteniendo visibles las fechas y las referencias horarias.
- Q5: mostrar el mes completo en siete columnas, con títulos abreviados y contador de tareas adicionales; pulsar un día abre su agenda completa con acciones de edición y creación.
- Q6: iniciar el arrastre mediante pulsación prolongada, mostrar una previsualización y ajustar a intervalos de quince minutos; conservar la duración de tareas con hora y ofrecer el formulario como alternativa al gesto.
- Q7: compartir con la web el rango horario y el primer día de semana mediante los ajustes de cuenta; mantener locales la vista elegida y el filtro de categoría.

Tercera ronda: Q8 a Q11 aceptadas el 2026-11-16 mediante «Yes to all».

- Q8: aplicar el rango horario únicamente a la cuadrícula; las agendas conservan todas las tareas de las fechas y categoría seleccionadas. La cuadrícula indica cuántas tareas quedan fuera del rango y permite abrirlas en la agenda.
- Q9: guardar al soltar una tarea en un destino válido y ofrecer «Deshacer», sin diálogo de confirmación por cada movimiento. Un gesto cancelado o un destino inválido no modifica la tarea; se conservan las reglas de sincronización y conflictos.
- Q10: convertir todo el día a una hora de duración al entrar en la cuadrícula; quitar el horario visible al pasar a todo el día; mover solo la fecha al arrastrar entre días del mes. Mantener los intervalos dentro de una fecha: cerca del final del día, ajustar el inicio para conservar la duración y mostrar el resultado en la previsualización, o rechazar el destino si no cabe.
- Q11: permitir editar desde Android el horario visible y el primer día de semana, también offline; sincronizar estos ajustes compartidos con las mismas reglas de conservación de pendientes y resolución de conflictos. El modo local conserva sus ajustes separados de las cuentas.

## Propuesta consolidada para Q12

### Navegación y presentación

La sección actual de calendario incorporará los selectores Día/Semana/Mes y Agenda/Horario para día y semana. Mantendrá Anterior/Hoy/Siguiente, con salto directo de fecha mediante selector. La primera apertura mostrará la semana en modo horario; las posteriores conservarán la elección local y partirán de hoy. Durante la navegación y la edición se conservará la fecha seleccionada. Se mantienen Europe/Madrid y la separación del día de hábitos.

La semana tendrá desplazamiento horizontal y el horario desplazamiento vertical. Las tareas simultáneas tendrán columnas propias y las tareas de todo el día se apilarán a lo ancho de su día. El mes mostrará siete columnas, títulos abreviados y acceso a la agenda completa desde el día o su contador. El filtro ofrecerá todas las categorías, una categoría y sin categoría. Las agendas incluirán también las tareas fuera del rango horario; la cuadrícula proporcionará acceso explícito a ellas. Se mostrarán la fecha actual y el indicador de hora actual cuando corresponda.

### Creación, edición y movimiento

El botón general de creación seguirá proponiendo hoy y todo el día. Crear desde una fecha o su banda de todo el día conservará esa fecha; crear desde una celda horaria propondrá su fecha y hora. Las tareas conservarán edición, completar/reabrir y eliminación con sus reglas actuales de dependencias y confirmación.

La pulsación prolongada iniciará el arrastre, con destino y horario propuestos visibles, desplazamiento al acercarse al borde y cancelación sin escritura. Soltar en un destino válido guardará una única reprogramación mediante los comandos existentes y ofrecerá «Deshacer». Deshacer será otra edición validada del horario anterior: no sobrescribirá silenciosamente cambios posteriores ni restaurará una tarea eliminada. El formulario seguirá siendo la alternativa accesible para cualquier movimiento, incluidas fechas fuera de la vista actual. Los controles tendrán etiquetas accesibles y la agenda permitirá consultar títulos completos y acciones sin depender del arrastre.

Se aplicarán los pasos de quince minutos, las conversiones y los límites aceptados en Q6 y Q10. La previsualización coincidirá con el horario persistido. Un movimiento conservará categoría, proyecto, dependencias y estado de finalización. Cambiar a todo el día conservará horas internas válidas para el contrato existente, sin mostrarlas como horario de la tarea.

### Preferencias y persistencia

Android ofrecerá edición de inicio/fin del horario y comienzo de semana. Los valores predeterminados existentes son 00:00–24:00 y lunes. Los ajustes de cuenta se leerán de la copia local y se guardarán junto con su operación pendiente; se compartirán mediante el protocolo existente, con mezcla de campos y resolución explícita de conflictos. El primer guardado sin fila remota previa tendrá cobertura específica. La edición de calendario conservará los ajustes de hábitos que no se estén modificando.

El modo local conservará sus propios ajustes. Las preferencias locales de vista y categoría permanecerán separadas entre modo local y cuentas; una categoría que deje de existir no mantendrá el calendario vacío por un filtro inválido. Se preservarán los datos y las operaciones pendientes al actualizar la aplicación.

### Implementación y alcance

- Extraer el cálculo de fechas, intervalos visibles, disposición de solapamientos y propuestas de movimiento a funciones Kotlin comprobables sin interfaz.
- Componer las vistas Android sobre las tareas y comandos existentes; mantener la fuente local de datos durante uso offline, refrescos y sincronización.
- Conectar el modelo y la edición de preferencias al protocolo existente; comprobar la representación local de ajustes predeterminados y cambios pendientes.
- Generar el APK de pruebas y documentar la verificación realizada y cualquier comprobación de dispositivo pendiente.

Esta entrega desarrolla el calendario en la app nativa. El gráfico anual de actividad, los recordatorios y el diagrama de proyectos pertenecen a otras funcionalidades. Las decisiones actuales no requieren modificar el esquema remoto ni desplegar web/backend. No se cambia la arquitectura acordada; no procede un ADR nuevo.

## Criterios de aceptación propuestos

1. Día, semana, mes y agenda muestran las mismas tareas fechadas para la categoría elegida, con la excepción explícita del rango visible de la cuadrícula y su acceso a tareas exteriores. Incluyen completadas y proyectos de todos los estados; excluyen hábitos y trabajo sin fecha.
2. Anterior/Hoy/Siguiente, selector de fecha, cambios de mes/año y lunes/domingo sitúan correctamente los días. Abrir una agenda, editar y volver conserva el contexto. La app mantiene su zona horaria aunque cambie la del teléfono.
3. Se verifican creación general frente a creación contextual, todo el día frente a horario, solapamientos, varias tarjetas de todo el día, títulos largos y mes con más tareas de las que caben en una celda.
4. Arrastrar respeta quince minutos y duración; convierte entre todo el día y horario; el mes cambia solo fecha. Se comprueban límites del día, cancelación, destino inválido, desplazamiento en bordes y ausencia de escrituras duplicadas. Deshacer preserva las reglas de negocio y no revierte cambios ajenos.
5. Mover y editar ajustes sin conexión persiste tras cerrar/reabrir. La sincronización conserva cambios compatibles y presenta conflictos reales. Se prueban preferencias sin fila previa, cuentas separadas, categoría eliminada y conservación del reinicio de hábitos.
6. La interfaz admite desplazamiento normal sin iniciar movimientos, controles accesibles y operación mediante formularios sin arrastre. Las agendas permiten acceder a tareas de fuera del horario visible.
7. Ejecutar pruebas JVM de calendario, pruebas instrumentadas pertinentes de persistencia/sincronización e interfaz, compilación y lint Android. Usar datos desechables para operaciones de prueba; distinguir pruebas ejecutadas en dispositivo de mera compilación de sus APK. Verificar actualización sin pérdida de datos y registrar cualquier limitación de comprobación manual.

## Cierre confirmado

Q12 aceptada el 2026-11-16: diseño y comienzo de implementación confirmados.

Se mantiene la arquitectura aprobada. El estado de implementación y su verificación se registran en `android/README.md`.


## Mejora de arrastre del 2026-11-16

El usuario aprobó implementar seguimiento continuo del dedo. La versión Android 0.5.1 añade una tarjeta flotante por encima de los contenedores desplazables, conserva el punto de agarre, resalta el destino y usa desplazamiento por fotogramas. La etiqueta de horario es flotante y no altera el espacio del calendario. Al soltar, espera el resultado del guardado local y anima la llegada durante 150 ms; las cancelaciones y fallos vuelven al origen disponible o desvanecen la tarjeta si ese estado desapareció. No cambia las reglas de sincronización, las conversiones ni «Deshacer». Véase `android/README.md` para la evidencia de compilación y las ocho pruebas Compose de gestos ejecutadas en el Poco.
