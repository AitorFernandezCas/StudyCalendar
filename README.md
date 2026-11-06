# StudyCalendar

Calendario de estudio con un frontend en React, TypeScript y Vite, una API Flask
y persistencia y autenticación en Supabase.

## Estructura

```text
frontend/       aplicación React, recursos públicos, scripts y pruebas de Node
backend/        API Flask y pruebas de Python
supabase/       migraciones SQL e historial de la base de datos
docs/           capturas y documentación gráfica
.github/        integración continua
render.yaml     configuración de despliegue
DEPLOYMENT.md   guía de publicación y mantenimiento
```

## Desarrollo

Usar las versiones indicadas en `.node-version` y `.python-version`.
Los siguientes comandos se ejecutan desde la raíz del repositorio.

Preparar el frontend:

```powershell
Copy-Item frontend/.env.example frontend/.env
npm --prefix frontend ci
npm --prefix frontend run dev
```

Copiar el archivo de ejemplo solo si todavía no existe `frontend/.env` y completar
sus variables con la configuración local. Vite se inicia en
`http://localhost:5173`.

Preparar el backend en otra terminal:

```powershell
python -m venv backend/.venv
backend/.venv/Scripts/python.exe -m pip install -r backend/requirements.txt
Copy-Item backend/.env.example backend/.env
backend/.venv/Scripts/python.exe backend/app.py
```

Copiar también el ejemplo del backend solo si no existe `backend/.env` y configurar
sus variables. En Linux/macOS, usar `backend/.venv/bin/python`.
La API se inicia en `http://localhost:5000`.

## Verificación

```powershell
npm --prefix frontend run test:deployment
npm --prefix frontend run test:calendar
npm --prefix frontend run build
backend/.venv/Scripts/python.exe -m pytest backend -q
```

También se puede entrar en `frontend` y ejecutar directamente `npm ci`,
`npm run dev` y los demás scripts de su `package.json`.

Consultar [backend/README.md](backend/README.md) para la arquitectura y las pruebas
con datos desechables, y [DEPLOYMENT.md](DEPLOYMENT.md) para producción.
