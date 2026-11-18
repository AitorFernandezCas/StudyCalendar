import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'

const apiSource = readFileSync(new URL('../src/lib/api.ts', import.meta.url), 'utf8')
const errorClass = apiSource.match(/export class ApiError[\s\S]*?\n}/)[0].replace('export ', '')
const source = readFileSync(new URL('../src/lib/syncApi.ts', import.meta.url), 'utf8')
  .replace("import { ApiError } from './api'", errorClass)
  .replace('import.meta.env', JSON.stringify({ VITE_API_URL: 'https://test.invalid' }))
const output = stripTypeScriptTypes(source, { mode: 'transform' })
const api = await import('data:text/javascript;base64,' + Buffer.from(output).toString('base64'))
const session = { user: { id: '11111111-1111-4111-8111-111111111111' }, access_token: 'test-token' }
const storage = new Map()
globalThis.sessionStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
globalThis.window = new EventTarget()

function snapshot(owner = session.user.id) {
  return { protocol: 1, owner, downloadedAt: '2026-11-14T00:00:00Z', data: { tasks: [], projects: [], category: [], routine: [], routine_period: [], routine_completion: [], user_settings: [] }, settings: { routine_reset_time: '00:00' }, day: { date: '2026-11-14' }, routines: [] }
}
function response(body, status = 200) { return { ok: status >= 200 && status < 300, status, json: async () => body } }

test('only intended fields are sent, retaining null/false and normalizing schedules', () => {
  const base = { revision: 1, state: { title: 'Antes', completed: false, project_id: 'p', start_time: '09:00:00', prerequisite_ids: ['b', 'a'] } }
  assert.deepEqual(api.intendedChanges(base, { title: 'Después', completed: false, project_id: null, start_time: '09:00', prerequisite_ids: ['a', 'b'] }), { title: 'Después', project_id: null })
  assert.deepEqual(api.intendedChanges({ revision: 1, state: { completed: true } }, { completed: false }), { completed: false })
})

test('lost write response recovers the original operation, entity ID and context', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/snapshot')) return response(snapshot())
    const op = JSON.parse(options.body)
    calls.push(op)
    if (calls.length === 1) throw new TypeError('simulated lost response')
    return response({ status: 'applied', row: { id: op.id, title: 'Prueba' } })
  }
  await api.readSynced('/api/projects', session)
  const options = { method: 'POST', body: JSON.stringify({ name: 'Prueba', color: '#16A085' }) }
  await assert.rejects(api.mutateSynced('/api/projects', session, options), /lost response/)
  const result = await api.mutateSynced('/api/projects', session, options)
  assert.equal(result.id, calls[0].id)
  assert.deepEqual(calls[0], calls[1])
  assert.equal(storage.size, 0)
})

test('account mismatch is rejected and conflicts retain the original edit base', async () => {
  globalThis.fetch = async () => response(snapshot('22222222-2222-4222-8222-222222222222'))
  await assert.rejects(api.readSynced('/api/tasks', session), /cuenta/)
  const base = { revision: 4, state: { title: 'Antes' } }
  let notice
  window.addEventListener('studycalendar-sync-conflict', event => { notice = event.detail }, { once: true })
  globalThis.fetch = async (_url, options) => {
    assert.deepEqual(JSON.parse(options.body).base, base)
    return response({ status: 'conflict', reason: 'fields', fields: ['title'], remote: { revision: 5, state: { title: 'Móvil' } } })
  }
  await assert.rejects(api.mutateSynced('/api/tasks/33333333-3333-4333-8333-333333333333', session, { method: 'PATCH', body: JSON.stringify({ title: 'Web' }) }, base), /conflicto/)
  assert.equal(notice.operation.changes.title, 'Web')
  assert.equal(notice.result.remote.state.title, 'Móvil')
})

test('feature mutations use sync receipts and task membership can be explicitly cleared', async () => {
  const data = snapshot()
  const feature = { id: 'feature-fixture', project_id: 'project-fixture', name: 'Entrega', description: null }
  const operations = []
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/snapshot')) return response(data)
    const operation = JSON.parse(options.body)
    operations.push(operation)
    return response({ status: 'applied', row: { id: operation.id, ...operation.changes } })
  }
  assert.deepEqual((await api.readSynced('/api/bootstrap', session)).features, [])
  data.data.project_features = [feature]
  assert.deepEqual((await api.readSynced('/api/bootstrap', session)).features, [feature])
  await api.mutateSynced('/api/project-features', session, {
    method: 'POST', body: JSON.stringify({ name: feature.name, project_id: feature.project_id, description: null }),
  })
  assert.equal(operations[0].entity, 'project_features')
  const base = { revision: 12, state: { title: 'Título', feature_id: feature.id } }
  await api.mutateSynced('/api/tasks/task-fixture', session, {
    method: 'PATCH', body: JSON.stringify({ title: 'Título', feature_id: null }),
  }, base)
  assert.deepEqual(operations[1].changes, { feature_id: null })
  assert.deepEqual(operations[1].base, base)
})

test('feature links use their own sync entity and retain the removal base', async () => {
  const data = snapshot()
  const base = { revision: 13, state: { project_id: 'project-fixture', source_feature_id: 'from', target_feature_id: 'to' } }
  const link = { id: 'link-fixture', ...base.state, _sync: base }
  const operations = []
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/snapshot')) return response(data)
    const op = JSON.parse(options.body)
    operations.push(op)
    return response({ status: 'applied', row: link, deleted: op.action === 'delete' ? op.id : null })
  }
  assert.equal((await api.readSynced('/api/bootstrap', session)).feature_links, null)
  data.data.project_feature_links = [link]
  assert.deepEqual((await api.readSynced('/api/bootstrap', session)).feature_links, [link])
  await api.mutateSynced('/api/project-feature-links', session, { method: 'POST', body: JSON.stringify(base.state) })
  assert.equal(operations[0].entity, 'project_feature_links')
  assert.deepEqual(operations[0].changes, base.state)
  assert.deepEqual(await api.mutateSynced('/api/project-feature-links/' + link.id, session, { method: 'DELETE' }, base), { deleted: link.id })
  assert.deepEqual(operations[1].base, base)
})
