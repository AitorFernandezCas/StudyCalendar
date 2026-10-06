import assert from 'node:assert/strict'
import test from 'node:test'
import { validateProductionEnv } from './production-env.mjs'

const valid = {
  VITE_API_URL: 'https://studycalendar-api.example.com',
  VITE_SUPABASE_URL: 'https://studycalendar-ci.supabase.co',
  VITE_SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_ci_placeholder',
}
const jwt = role => ['header', Buffer.from(JSON.stringify({ role })).toString('base64url'), 'signature'].join('.')

test('permite HTTPS y claves publicables y anon', () => {
  assert.deepEqual(validateProductionEnv(valid), [])
  assert.deepEqual(validateProductionEnv({ ...valid, VITE_SUPABASE_PUBLISHABLE_KEY: jwt('anon') }), [])
})
test('exige las tres variables', () => {
  assert.equal(validateProductionEnv({}).length, 3)
})
test('rechaza HTTP, direcciones locales, credenciales y rutas', () => {
  for (const url of ['http://api.example.com', 'https://localhost', 'https://app.localhost', 'https://app.local', 'https://127.0.0.1', 'https://127.5.2.1', 'https://10.1.2.3', 'https://172.16.1.2', 'https://192.168.1.2', 'https://169.254.1.2', 'https://[::1]', 'https://[fd00::1]', 'https://[::ffff:127.0.0.1]', 'https://user:password@api.example.com', 'https://api.example.com/api', 'https://api.example.com/?key=value', 'https://api.example.com/#token', 'invalid']) {
    assert.ok(validateProductionEnv({ ...valid, VITE_API_URL: url }).length, url)
    assert.ok(validateProductionEnv({ ...valid, VITE_SUPABASE_URL: url }).length, url)
  }
})
test('rechaza claves privilegiadas y no las muestra en los errores', () => {
  for (const key of ['sb_secret_private', jwt('service_role'), 'invalid']) {
    const errors = validateProductionEnv({ ...valid, VITE_SUPABASE_PUBLISHABLE_KEY: key })
    assert.ok(errors.length)
    assert.ok(!errors.join('\n').includes(key))
  }
  assert.ok(validateProductionEnv({ ...valid, VITE_OTHER_KEY: jwt('service_role') }).length)
})
