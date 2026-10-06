"""Linux CI: exercise the real WSGI entrypoint without Supabase credentials."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def main():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    origin = 'https://studycalendar.example.com'
    env = {**os.environ, 'PORT': str(port), 'SUPABASE_URL': '',
           'SUPABASE_PUBLISHABLE_KEY': '', 'FRONTEND_ORIGIN': origin,
           'APP_TIMEZONE': 'Europe/Madrid'}
    process = subprocess.Popen([sys.executable, '-m', 'gunicorn', 'wsgi:app'],
                               cwd=Path(__file__).resolve().parent, env=env)
    base = f'http://127.0.0.1:{port}'
    try:
        deadline = time.monotonic() + 30
        while True:
            if process.poll() is not None:
                raise RuntimeError('Gunicorn terminó antes de responder')
            try:
                with urlopen(base + '/api/health', timeout=2) as response:
                    assert response.status == 200
                    assert json.load(response) == {'status': 'ok'}
                break
            except (URLError, TimeoutError):
                if time.monotonic() >= deadline:
                    raise RuntimeError('Gunicorn no respondió en 30 segundos') from None
                time.sleep(0.2)
        try:
            urlopen(base + '/api/tasks', timeout=5)
        except HTTPError as error:
            assert error.code == 401
            assert json.load(error) == {'error': 'Authentication required'}
        else:
            raise AssertionError('Las tareas sin autenticación deben devolver 401')
        for candidate in (origin, 'https://untrusted.example.com', 'http://localhost:5173'):
            request = Request(base + '/api/tasks', method='OPTIONS', headers={
                'Origin': candidate, 'Access-Control-Request-Method': 'GET'})
            with urlopen(request, timeout=5) as response:
                assert response.headers.get('Access-Control-Allow-Origin') == (origin if candidate == origin else None)
        print('WSGI, health, autenticación y CORS verificados.')
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


if __name__ == '__main__':
    main()
