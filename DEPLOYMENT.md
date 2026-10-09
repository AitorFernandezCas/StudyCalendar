# Publicación gratuita de StudyCalendar

## Servicios y coste

| Componente | Servicio | Configuración |
| --- | --- | --- |
| CI | GitHub Actions | Node 24, Python 3.12, build y pruebas |
| Frontend | Render Static Site | CDN, HTTPS y dominio `onrender.com` |
| Backend | Render Web Service Free | Frankfurt, Flask con Gunicorn |
| Datos y sesión | Supabase Free existente | Esquema `Task`, Auth y RLS |

No se contratan dominios, bases de datos de Render ni planes de pago. No añadir
un método de pago para cubrir excesos de uso. Si una cuenta ya tiene facturación,
comprobar sus límites de gasto antes de conectar este proyecto. Revisar también
la cuota gratuita y el presupuesto de GitHub Actions, especialmente en repositorios
privados. La configuración del repositorio no puede imponer límites de facturación
a las cuentas de los proveedores.

Render Free suspende el backend después de 15 minutos sin tráfico y el siguiente
acceso puede tardar aproximadamente un minuto. El frontend muestra un aviso a los
diez segundos de carga. Supabase Free puede pausar un proyecto tras una semana
sin actividad; su reactivación puede requerir entrar en el panel. Esto es una
publicación para aprendizaje, sin garantía de disponibilidad continua.

Fuentes: [Render Free](https://render.com/docs/free),
[Supabase](https://supabase.com/pricing),
[GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions).

## Variables

Introducirlas en los servicios de Render, nunca en Git ni en un comentario de PR.

| Servicio | Variable | Valor |
| --- | --- | --- |
| Backend | `SUPABASE_URL` | URL del proyecto existente |
| Backend | `SUPABASE_PUBLISHABLE_KEY` | Clave publicable del mismo proyecto |
| Backend | `FRONTEND_ORIGIN` | URL HTTPS exacta del frontend, sin barra final |
| Backend | `APP_TIMEZONE` | `Europe/Madrid` |
| Backend | `AUTH_CACHE_TTL_SECONDS` | `30` |
| Frontend | `VITE_API_URL` | URL HTTPS del backend, sin `/api` |
| Frontend | `VITE_SUPABASE_URL` | La misma URL de Supabase |
| Frontend | `VITE_SUPABASE_PUBLISHABLE_KEY` | La misma clave publicable |

`PORT` lo proporciona Render. Las versiones se definen en `.node-version` y
`.python-version`; evitar variables de Render que las sobrescriban accidentalmente.
`FLASK_ENV=development` no se configura en hosting.

Las variables `VITE_` se incorporan al JavaScript público durante el build. La
clave publicable (o la antigua clave `anon`) es apta para este uso con RLS y los
tokens de sesión. Nunca usar claves `sb_secret_` o `service_role`. El build de
producción valida las variables sin imprimir sus valores. Después de cambiar una
variable del frontend hay que volver a compilar y desplegar; cambiarla en el panel
sin reconstruir no actualiza el JavaScript ya publicado.

## CI y despliegue continuo

El workflow `CI` se ejecuta en pull requests hacia `main`, pushes a `main` y
ejecuciones manuales. Los trabajos Frontend y Backend son independientes; `CI
completa` exige que ambos terminen correctamente, incluso si alguno se cancela o
se omite. CI usa URLs y claves ficticias, no credenciales de Supabase.

El frontend instala el lockfile con `npm ci`, verifica la validación de entorno y
compila TypeScript/Vite. El backend ejecuta pytest y arranca la entrada real
`wsgi:app` mediante Gunicorn en Linux: comprueba salud, `401` sin sesión y CORS.
El check de salud verifica el proceso HTTP, no la disponibilidad de Supabase.

Los servicios de `render.yaml` usan `main` y `autoDeployTrigger: checksPass`.
Render debe estar conectado a GitHub mediante su integración; conectar únicamente
la URL pública del repositorio no habilita este despliegue automático. No se
necesitan deploy hooks ni secretos de Render en GitHub Actions. No se crean
entornos de preview que consuman otra instancia del backend.

[Integración con CI](https://render.com/docs/deploys).

## Primera publicación

1. Incorporar los cambios actuales del frontend y la configuración de publicación
   mediante un PR a `main`. Exigir los tres checks verdes antes de fusionarlo.
2. Iniciar sesión en Render y conectar su integración GitHub, concediendo acceso
   solo al repositorio `AitorFernandezCas/StudyCalendar` cuando sea posible.
3. En **New > Web Service**, seleccionar ese repositorio y `main`:
   Python, Frankfurt, **Free**, build `python -m pip install -r backend/requirements.txt`,
   inicio `cd backend && gunicorn wsgi:app`, health check `/api/health`.
   Mantener la raíz del servicio en la raíz del repositorio.
4. Configurar las variables del backend. Mientras no exista el frontend, usar
   `https://frontend-pendiente.invalid` como `FRONTEND_ORIGIN`; no autorizar `*`.
   Guardar la URL asignada realmente por Render; no deducirla del nombre del servicio.
5. Crear **New > Static Site**, mismo repositorio y `main`, raíz del repositorio,
   build `npm ci && npm run build:production`, salida `dist`. Introducir las tres
   variables `VITE_`, incluyendo la URL real del backend.
6. Añadir la reescritura `/*` hacia `/index.html` y los headers de `render.yaml`.
   Registrar la URL real del frontend en `FRONTEND_ORIGIN` y redesplegar el backend.
7. En ambos servicios, seleccionar **After CI Checks Pass** y confirmar que se
   conserva el plan Free. Verificar los servicios y las URLs finales.

`render.yaml` es la definición reproducible equivalente. También se puede importar
con **New > Blueprint**, completando los campos `sync: false` con las URLs reales
asignadas. Si el asistente no permite conocer ambas URLs antes del primer build,
crear los servicios en el orden anterior. Para incorporar servicios existentes a
un Blueprint, usar sus nombres exactos y revisar el resumen antes de sincronizar
para no crear servicios duplicados. El plan del backend es explícitamente `free`;
los sitios estáticos no tienen campo `plan`.

No cambiar el Site URL, SMTP, registro global ni otras opciones de Auth del proyecto
compartido de Supabase. El acceso inicial usa la cuenta existente y no necesita
confirmaciones nuevas por correo. Si posteriormente se añaden usuarios, tratar
el registro, los redirects y el correo en un cambio separado.

## Migraciones y copias

Las tareas de «Todo el día» requieren la nueva migración
`20261007144641_task_all_day.sql`. No ejecutar `db push`, resets ni migraciones
desde CI/Render. La base existente ya tiene aplicada
`20261005133423_routine_habits_and_streaks`. Varios identificadores locales antiguos
no coinciden con el historial remoto; no ejecutar todos los SQL otra vez.

Mientras `all_day` no exista, el adaptador mantiene disponibles las lecturas de
tareas, calendario y bootstrap y devuelve `all_day=false`. Crear o editar tareas
con horario sigue funcionando; comprueba la columna mediante una lectura antes
de escribir y omite el valor falso en el esquema antiguo. Solicitar `all_day=true`
devuelve `409` con un mensaje de migración pendiente, sin enviar una escritura.
Solo se recupera el error específico de columna ausente; los demás errores se
conservan. No se reintentan operaciones de escritura ni se aplica SQL automáticamente.

Antes de automatizar migraciones en otro cambio, comparar el historial remoto y
los SQL locales, reconciliar las versiones sin volver a ejecutar DDL aplicado y
probar sobre una base de prueba. Los cambios incompatibles requieren coordinar
base de datos y backend; un rollback de código no revierte una migración.

Para publicar «Todo el día»:

1. Comparar el historial remoto con los SQL locales y reconciliar las versiones
   antiguas sin volver a ejecutar DDL aplicado. Crear una copia privada siguiendo
   las instrucciones siguientes y preparar una base de prueba aislada con el
   esquema actual, anterior a esta nueva migración.
2. En esa base de prueba ejecutar `psql "$env:DATABASE_URL" -v ON_ERROR_STOP=1 -f backend/test_task_all_day.sql`.
   El script aplica el nuevo SQL dentro de una transacción, comprueba valores
   existentes, restricciones y RLS, y revierte todos sus cambios. Nunca ejecutarlo
   contra producción ni contra una base que ya tenga la columna `all_day`.
3. Aplicar manualmente solo `20261007144641_task_all_day.sql` al proyecto existente
   y registrar su versión conforme al historial reconciliado. No modifica las
   políticas RLS ni las horas; las tareas existentes reciben `all_day=false`.
4. Desplegar primero el backend y después el frontend tras pasar los checks de CI.
   Verificar crear, editar, completar, arrastrar y recargar tareas de todo el día
   en semana, mes, todas las tareas y proyectos, con una cuenta de prueba propia.

La API añade `all_day` a tareas, calendario y bootstrap; los PATCH que omiten el
campo conservan su valor. `start_time` y `end_time` siguen siendo obligatorios y
válidos, aunque no se muestran cuando `all_day=true`. Al convertir en el formulario
se conserva el horario; al arrastrar a horas se asigna la hora de destino. Antes de
un rollback, valorar que las versiones antiguas mostrarán estas tareas con sus
horas internas: revertir código no borra la columna ni restaura la vista anterior.

Para una copia manual, usar la conexión PostgreSQL indicada en el panel Supabase
y `pg_dump` instalado desde PostgreSQL. En PowerShell, guardar la conexión en la
variable de entorno `DATABASE_URL` mediante un mecanismo privado, fuera de Git:

```powershell
pg_dump --dbname="$env:DATABASE_URL" --format=custom --schema='"Task"' --file='C:/copias-privadas/studycalendar.dump'
```

Comprobar el archivo con `pg_restore --list`. Mantenerlo en una carpeta privada
fuera del repositorio; contiene datos personales. Hacer una copia antes de cambios
de esquema y periódicamente cuando haya datos que interese conservar. El dump
del esquema `Task` depende de usuarios y funciones de `auth`: por sí solo no es una
copia completa de Auth ni del otro proyecto. Una prueba de restauración requiere
un entorno aislado y los mismos usuarios/UUID o una copia coherente del proyecto;
no restaurar sobre la base compartida de producción. No asumir que el plan gratuito
incluye copias automáticas descargables.

## Comprobaciones y recuperación

- Abrir y recargar `/`, `/tareas`, `/proyectos` y `/rutinas`; ninguna debe dar 404.
- Iniciar sesión con la cuenta existente. Crear datos de prueba identificables y
  comprobar tareas, arrastre, proyectos, rutinas, rachas y persistencia al recargar.
- `/api/health` debe devolver `200` y `{"status":"ok"}`. `/api/tasks` sin token
  debe devolver `401`. CORS debe permitir solo el origen final del frontend.
- Tras un periodo sin tráfico, comprobar el aviso de conexión lenta y que el acceso
  se recupera. Si una lectura falla, **Reintentar carga** solo ejecuta GET; nunca
  reenvía automáticamente una creación, edición o eliminación.
- Verificar CI en un PR con fallo intencionado y corregirlo. Para comprobar el
  bloqueo real de CD durante la puesta en marcha, apuntar los servicios temporalmente
  a la rama del PR de prueba y lanzar manualmente el workflow sobre esa rama: un
  commit rojo no debe generar despliegue, y el corregido verde sí. Volver a `main`
  al terminar. No romper `main` para esta prueba ni desactivar checks en producción.
- Revisar logs de Render y resultados de GitHub Actions al fallar. No guardar ni
  compartir bearer tokens, contraseñas o claves privilegiadas en logs.
- Render Free permite volver a las dos versiones anteriores. Seleccionar la última
  válida en **Deploys** y revisar la configuración de auto-deploy después del rollback.
  Para una recuperación duradera, revertir el cambio defectuoso en Git mediante un
  nuevo PR con CI verde; mantener compatibles las versiones de frontend/backend.
- Si Supabase está pausado, abrir su proyecto en el dashboard, reactivarlo y esperar
  a que vuelva a estar saludable. No modificar datos ni migraciones para reactivarlo.
- Revisar cuotas de Render, Supabase y GitHub desde sus paneles. No se programan
  pings, cron jobs ni monitores que impidan artificialmente la suspensión.

## Estado de entrega

Publicado el 7 de octubre de 2026:

- Aplicación: https://studycalendar.onrender.com
- API: https://studycalendar-api.onrender.com
- Salud: https://studycalendar-api.onrender.com/api/health
- Backend Render: `srv-db2nj4btqb8s73e5hnn0`, Python, Frankfurt, Free.
- Frontend Render: `srv-db2nmie0tbcc73ev793g`, sitio estático gratuito.
- Configuración incorporada mediante [PR #9](https://github.com/AitorFernandezCas/StudyCalendar/pull/9).
- [CI de la implementación](https://github.com/AitorFernandezCas/StudyCalendar/actions/runs/37541941344):
  build, cuatro comprobaciones de entorno, 96 pruebas backend y smoke WSGI satisfactorios.
- Comprobación pública: salud `200`, tareas sin token `401`, CORS permitido solo
  para `https://studycalendar.onrender.com`, las cuatro rutas SPA `200` y header `nosniff`.
- Aviso a los diez segundos y recuperación tras un error comprobados con la fixture
  local desechable; no se reenvían escrituras.
- [Prueba de CI negativa](https://github.com/AitorFernandezCas/StudyCalendar/actions/runs/37544019087):
  una URL local en la configuración ficticia produjo fallo del build y del check
  agregado. Render conservó el commit anterior mientras estaba enlazado a la rama
  temporal de prueba. [PR de verificación #10](https://github.com/AitorFernandezCas/StudyCalendar/pull/10).
- [Prueba corregida](https://github.com/AitorFernandezCas/StudyCalendar/actions/runs/37544283432):
  CI verde y publicación automática de `01229fb`, con motivo `Auto-Deploy` en Render.
  La rama del frontend se restauró a `main` y el PR temporal se cerró sin fusionar.
- [Captura del frontend publicado](docs/deployment-published.jpg).

El historial de pruebas no implica un SLA. La suspensión real por inactividad,
el acceso con la cuenta del usuario y las operaciones autenticadas se verifican
por separado durante la puesta en marcha; los checks públicos no usan una sesión.
## Despliegue de preferencias personales

La funcionalidad necesita `20261009141547_user_settings.sql`, una migración
aditiva del esquema `Task`. El código anterior puede seguir funcionando con la
tabla añadida; el nuevo backend requiere esa tabla. No cambia Auth global ni la
función de rachas y no mueve completaciones o períodos existentes.

1. Seguir el procedimiento existente de copia privada y reconciliar el historial
   local/remoto antes de modificar la base compartida. No ejecutar `db push`
   indiscriminadamente ni aplicar migraciones desde CI o Render.
2. Aplicar la nueva migración en una base de pruebas y ejecutar
   `backend/test_user_settings.sql` con `ON_ERROR_STOP=1`. Revisar RLS, grants y
   los asesores de Supabase. El script revierte sus fixtures.
3. Tras esa verificación y la copia, aplicar únicamente esta migración al proyecto
   existente. Publicar backend y después frontend, con CI satisfactoria. El nuevo
   frontend necesita los metadatos `day_started_at` del nuevo backend.
4. Verificar con una cuenta de prueba guardado/recarga, otro dispositivo, rutinas
   antes y después del reinicio, semana desde domingo/lunes y horarios con minutos.
   Confirmar carga/error/reintento y las rutas directas existentes.

No se necesitan nuevas variables ni servicios. La zona sigue siendo
`APP_TIMEZONE=Europe/Madrid`. Cambiar la hora de reinicio aplica inmediatamente el
día efectivo y conserva la fecha de todas las completaciones anteriores. Un
rollback de código no requiere borrar la tabla; no revierte la migración.

Verificación local del 9 de octubre de 2026: build correcto, 165 pruebas backend
y 12 pruebas de calendario correctas. La fixture desechable confirmó guardado y
recarga, domingo en semana/mes, minutos, recorte/ocultación, agenda móvil, arrastre
con ratón/teclado y completado/desmarcado en el día efectivo anterior. La
verificación posterior de base de datos se registra a continuación;
el despliegue del nuevo código sigue pendiente.

El 9 de octubre se verificó además `user_settings` con el script SQL de permisos
en PostgreSQL aislado (PGlite), usando fixtures de Auth locales y revirtiendo sus
datos. Se inspeccionó el esquema remoto y su historial. La correspondencia
histórica se documenta en
`supabase/MIGRATION_HISTORY.md`; no se reejecutaron ni repararon versiones antiguas.
Antes de aplicar, el usuario confirmó que ya disponía de una copia privada
reciente y autorizó la aplicación.

Se aplicó únicamente `user_settings`, registrada remotamente como
`20261009141547`; se alineó el nombre del archivo local con esa versión. La nueva
tabla tenía cero filas, RLS activo, defaults correctos y políticas de propiedad
para SELECT/INSERT/UPDATE. Se verificó la denegación de acceso anónimo, borrado y
reasignación del propietario. Los recuentos de las seis tablas anteriores se
conservaron, `routine_summaries` no cambió y el asesor de seguridad no introdujo
avisos nuevos. La API REST reconoce la tabla y devuelve `401`/`42501` al intentar
leerla con la clave pública y sin sesión. `task_all_day` sigue sin aplicarse.
El código nuevo aún requiere
publicación mediante CI y verificación autenticada en producción.
