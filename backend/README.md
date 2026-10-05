# Backend hexagonal de StudyCalendar

Python 3.11 o posterior. Flask y Supabase permanecen como adaptadores de entrada
y salida; los casos de uso no dependen de ellos.

## Estructura y flujo

```text
studycalendar/
  tasks/ categories/ projects/ routines/
    domain.py              modelos y validación de comandos
    ports.py               contratos de repositorios (typing.Protocol)
    application.py         casos de uso
    adapters/http.py       Blueprint y serialización HTTP
    adapters/supabase.py   implementación de persistencia
  queries/                 composición de calendario y bootstrap
  shared/                  valores comunes, reloj, HTTP y runtime Supabase
  dependencies.py          contratos para inyectar autenticación, repositorios y reloj
  config.py                configuración de entorno
  factory.py               composición de la aplicación
```

Una petición pasa por autenticación, un ámbito de repositorios y un servicio de
aplicación. Los repositorios devuelven dataclasses del dominio. Solo los
adaptadores HTTP conocen request, g y jsonify. La factoría crea servicios nuevos
por petición con un User explícito.

Los puertos se definen en el núcleo que los consume. Las implementaciones dependen
de esos contratos; el núcleo no importa adaptadores. La prueba de límites de
dependencias impide introducir Flask, Supabase o HTTP en dominio/aplicación/puertos.

El módulo shared contiene tanto valores del núcleo como adaptadores comunes.
El núcleo solo puede importar shared.domain y shared.ports. El reloj real se
inyecta desde la factoría.

## Arranque e inyección

Desde la raíz:

```powershell
python backend/app.py
python -m pytest backend -q
npm run build
```

Se conservan app y create_app en app.py y app en wsgi.py. No se crea ninguna
conexión externa al importar o construir la aplicación. /api/health funciona
incluso sin credenciales de Supabase.

create_app(settings=None, dependencies=None) admite Settings y Dependencies.
Dependencies contiene Authenticator, RepositoryProvider y Clock. El proveedor
expone scope(token, user) como gestor de contexto que devuelve Repositories:
tareas, categorías, proyectos, rutinas y consulta de resúmenes. El ámbito se
libera también cuando el caso de uso falla.

Para sustituir Supabase, implementar los puertos de cada módulo y un
RepositoryProvider, e inyectarlo en Dependencies. No modificar casos de uso ni
controladores. Las pruebas incluyen repositorios en memoria y reloj fijo como
ejemplos, además del SDK real con httpx.MockTransport.

Para añadir una funcionalidad: definir sus modelos/reglas, sus puertos, el servicio
que los consume y ambos adaptadores; registrar el Blueprint y las dependencias en
la composición. Probar las reglas sin Flask y verificar el contrato HTTP por
separado. No añadir repositorios genéricos ni dependencias inversas.

## Compatibilidad y seguridad

Se conservan rutas, estados HTTP, mensajes, formatos JSON, ordenaciones y
validaciones históricas. Command conserva la presencia de los campos: omitir
project_id no equivale a enviar null. Los modelos conservan columnas adicionales
devueltas por la base de datos para no alterar respuestas existentes.

Los clientes usan la clave publicable y el bearer token validado por Auth.
Los repositorios operan en el esquema Task y conservan las comprobaciones de
propiedad y RLS. No se emplean claves de servicio. La decodificación de exp del JWT
solo limita la vida de la caché; no valida identidad.

Cada aplicación/proceso mantiene dos LRU de 128 entradas: autenticación y clientes
de datos. AUTH_CACHE_TTL_SECONDS mantiene el valor por defecto de 30 segundos y
nunca extiende la caché más allá de exp. Los clientes no cambian sesión ni
credenciales. Las conexiones se reutilizan por token; las entradas expulsadas se
cierran al terminar su última petición activa. runtime.close() libera recursos al
apagar el proceso; no se llama en teardown de cada petición.

El esquema Task se fija al construir el cliente PostgREST. En la versión instalada,
schema() crea otro cliente HTTP; los repositorios utilizan directamente el cliente
configurado para evitar conexiones fuera del ciclo de vida administrado.

Las rachas siguen en routine_summaries con SECURITY INVOKER. Los casos de uso
capturan un único Day por operación. APP_TIMEZONE conserva Europe/Madrid y
next_day_at respeta el horario de verano. Los rangos del calendario mantienen
su fecha local histórica como valor predeterminado.

Este cambio no requiere migraciones y no cambia las garantías transaccionales
de las operaciones con varias escrituras. Tampoco añade paginación a la API:
los límites del Data API siguen siendo una consideración para volúmenes grandes.
La prueba SQL sigue disponible; debe ejecutarse si se cambia el cálculo de
rachas, sus migraciones o políticas.

## Producción y varios procesos

En Linux o WSL, desde backend:

```sh
gunicorn --workers 4 --bind 127.0.0.1:5000 wsgi:app
```

Cuatro procesos son un punto inicial para medir, no una capacidad garantizada.
Las cachés son locales a cada proceso y no almacenan estado de negocio.
Mantener un proxy apropiado delante del servidor y configurar el origen permitido.
Gunicorn no se ejecuta en Windows nativo; el servidor de Flask sigue siendo
la entrada de desarrollo. El proveedor de hosting queda pendiente de elección.

## Carga de solo lectura

Usar un entorno de pruebas con datos y tokens de usuarios de prueba. El script
solo envía GET a tareas, calendario, bootstrap y rutinas. Nunca imprime tokens,
URL base ni mensajes de excepciones. Devuelve código 1 si hay errores.

```powershell
$env:LOAD_TEST_BASE_URL='https://backend-de-pruebas.example'
# Archivo externo sin seguimiento: array JSON de access tokens.
python backend/load_test.py --tokens-file C:/ruta-privada/tokens.json --from-date 2026-10-05 --to-date 2026-10-11 --output backend/baseline.local
python backend/load_test.py --tokens-file C:/ruta-privada/tokens.json --from-date 2026-10-05 --to-date 2026-10-11 --baseline backend/baseline.local --output backend/after.local
```

Defaults: concurrencias 1, 10 y 25; 60 segundos por nivel; timeout 10 segundos.
También se admite LOAD_TEST_TOKENS como array JSON en una variable de entorno.
Los archivos *.local están ignorados por Git. No colocar tokens en un archivo
seguido por el repositorio.

Cada nivel informa peticiones/segundo, p50/p95, códigos HTTP, errores y métricas
por endpoint, además de throughput exitoso y tipos/códigos de errores de conexión.
La duración incluye las peticiones en curso al acabar el intervalo.
El modo predeterminado no introduce pausas y estresa el servidor. Para simular
usuarios con una pausa entre lecturas, usar --interval 0.2; la latencia excluye
esa pausa y la comparación requiere el mismo intervalo.
Comparar antes/después con iguales datos, rango, duración, hardware, workers y
usuarios. Una medición con repositorios en memoria no representa la capacidad
de Supabase ni demuestra una mejora en producción.

## Verificación manual con datos desechables

La fixture ui_smoke.py se ejecuta exclusivamente en localhost, con datos en memoria
y dependencias de prueba. No es un punto de entrada de producción. Su cálculo
simple de rachas sirve para verificar la UI; la implementación de producción
sigue usando el puerto SQL y se verifica por separado.

Terminal 1:

```powershell
python backend/ui_smoke.py
```

Terminal 2, desde la raíz:

```powershell
$env:VITE_API_URL='http://127.0.0.1:5001'
$env:VITE_SUPABASE_URL='http://127.0.0.1:5001'
$env:VITE_SUPABASE_PUBLISHABLE_KEY='demo-key'
npm run dev -- --host 127.0.0.1 --port 5180 --strictPort
```

Abrir http://localhost:5180 e iniciar sesión con demo@example.invalid y cualquier
contraseña de seis caracteres. Los datos sobreviven a recargas del navegador y
desaparecen al detener la fixture. La autenticación ficticia solo existe en este
script, nunca en app.py ni en la factoría de producción.

Verificar CRUD de tareas, categorías y proyectos; vistas semana/mes; arrastre y
persistencia tras recarga; separación de completadas; completar/desmarcar rutinas,
contadores y rachas; pausa/reactivación, fecha inicial futura, y ausencia de horas
y rutinas en el calendario. Los escenarios de medianoche, fecha obsoleta y fallo
del proveedor se cubren también con reloj fijo y transporte simulado.
