# Gráfico de proyectos en Android

Diseño aprobado el 2026-11-16 e implementado en Android 0.7.0. Amplía el plan visual web de `project-planning.md` sin duplicar tareas ni cambiar el contrato remoto.

## Decisiones

- Gráfico completo con tareas, flechas de requisitos, edición de conexiones, movimiento de tarjetas, zoom/desplazamiento y enfoque de rama.
- Selector «Gráfico / Lista» dentro de cada proyecto. La primera vista es el gráfico; se recuerda la elección por cuenta y proyecto en el teléfono.
- Tocar abre una hoja de acciones; mantener pulsado mueve la tarjeta. «Conectar con…» selecciona una tarea previa y después su dependiente. Hay un selector alternativo de requisitos.
- Las posiciones manuales se guardan únicamente en este dispositivo. Los cambios de tareas y requisitos usan el almacenamiento y la sincronización offline existentes.

## Comportamiento

Se muestran todas las tareas del proyecto, incluidas las completadas y sin fecha. Se conservan progreso, estados y creación existente. «Sin proyecto» sigue como lista, ya que sus tareas no pueden tener requisitos.

El diseño inicial es determinista, de izquierda a derecha, con títulos completos y alturas naturales. Las flechas que atravesarían otra tarjeta se desvían por encima del gráfico para no aparentar enlaces inexistentes. La disposición y las rutas se reutilizan al desplazar/ampliar la cámara. El zoom admite 40–160 %, con botones y pellizco. Arrastrar el fondo desplaza la vista; los bordes desplazan la cámara durante el movimiento de una tarjeta. Hay controles de movimiento en la hoja y acciones accesibles/teclado. Mover tarjetas nunca modifica horarios ni requisitos. Una cancelación restaura su posición anterior.

«Restablecer vista» encuadra el gráfico visible. «Organizar automáticamente» elimina las posiciones manuales de la vista actual y vuelve a encuadrarla. Las preferencias son privadas y están separadas por usuario/modo local y proyecto. Coordenadas inválidas se ignoran y las posiciones de tareas eliminadas se descartan.

«Ver rama» recorre por separado los antecesores y los descendientes de la raíz, excluyendo ramas laterales y otros requisitos de tareas posteriores. Los requisitos ocultos siguen visibles en la hoja, marcados como fuera de rama, y mantienen las restricciones de finalización. La rama utiliza posiciones temporales; volver al proyecto completo restaura su disposición y cámara. Cambiar los enlaces recalcula la pertenencia; eliminar la raíz devuelve al gráfico completo.

La cámara, selección y rama sobreviven a la rotación y al cambio Lista/Gráfico mediante estado retenido, sin restaurarse tras morir el proceso. Cambiar de proyecto o cuenta cancela el contexto transitorio. Volver cierra primero la hoja, cancela el movimiento/conexión, sale de la rama y finalmente abandona el proyecto.

La hoja permite consultar horarios, completar/reabrir con las reglas vigentes, editar, elegir/quitar requisitos y eliminar tareas sin enlaces con confirmación. La finalización conserva el aviso existente con Deshacer. Las mutaciones se deshabilitan al guardar o en modo de consulta; la inspección, navegación y disposición local siguen disponibles.

## Integridad

`CalendarCommands.setPrerequisites` lee la tarea actual dentro de la transacción Room o del bloqueo de edición de la réplica. Comprueba proyecto y conjunto previo esperado antes de cambiar únicamente requisitos. Conserva títulos, horarios y otros campos recientes; valida ciclos, propiedad del proyecto, finalización, reapertura y eliminación mediante las reglas existentes. La réplica conserva su cola y resolución explícita de conflictos. No se añade migración, endpoint ni campo sincronizado para la disposición.

## Verificación

Las pruebas JVM cubren disposición, alturas, orden, ramas, referencias ausentes, ciclos defectuosos, cadenas largas, coordenadas y modificaciones protegidas. Las instrumentadas cubren persistencia, cola offline/reapertura/conflictos y controles/gestos Compose con datos desechables.

Para ejecutar las pruebas en una instalación aislada de la aplicación habitual:

```powershell
# Con JAVA_HOME, ANDROID_HOME y GRADLE_USER_HOME configurados según android/README.md:
.\gradlew.bat -PisolatedUiTests=true :app:connectedDebugAndroidTest
```

Esta variante debug usa `com.studycalendar.android.graphcheck`, con su propia base y preferencias. La compilación normal mantiene `.dev`. Recompilar sin esa propiedad para entregar el APK normal. La instrumentación y las cuentas simuladas no verifican escrituras autenticadas nuevas en producción.
