// Uses an in-memory PostgreSQL instance and never reads DATABASE_URL.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFileSync, readdirSync } from 'node:fs'

const { PGlite } = await import(process.env.PGLITE_MODULE || '@electric-sql/pglite')
const migrations = new URL('../../supabase/migrations/', import.meta.url)
const db = new PGlite()
const owner = randomUUID()
const other = randomUUID()
const project = randomUUID()
const secondProject = randomUUID()
const otherProject = randomUUID()
const a = randomUUID()
const b = randomUUID()
const first = randomUUID()
const second = randomUUID()
const foreign = randomUUID()
const migration = '20261118105622_project_features.sql'
const applyFile = name => db.exec(readFileSync(new URL(name, migrations), 'utf8'))

async function asUser(user, run) {
  await db.exec('set role authenticated')
  await db.query("select set_config('request.jwt.claim.sub', $1, false)", [user])
  try {
    return await run()
  } finally {
    await db.exec("reset role; reset request.jwt.claim.sub")
  }
}

async function snapshot(user = owner) {
  return asUser(user, async () => (await db.query('select "Task".sync_snapshot() as snapshot')).rows[0].snapshot)
}

async function apply(entity, action, id, changes = {}, base = null, user = owner) {
  return asUser(user, async () => {
    const operation = { protocol: 1, operation_id: randomUUID(), entity, action, id, changes, base, context: {} }
    return (await db.query('select "Task".sync_apply($1::jsonb) as result', [JSON.stringify(operation)])).rows[0].result
  })
}

function applied(result) {
  assert.equal(result.status, 'applied', JSON.stringify(result))
  return result.row
}

try {
  await db.exec(`create role anon; create role authenticated;
    create schema auth; create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated,anon;
    grant execute on function auth.uid() to authenticated,anon;`)
  for (const name of readdirSync(migrations).sort()) {
    if (!name.startsWith('202610040002') && name < migration && name.endsWith('.sql')) await applyFile(name)
  }
  await db.query('insert into auth.users values ($1), ($2)', [owner, other])
  for (const [id, user, name] of [[project, owner, 'Principal'], [secondProject, owner, 'Otro'], [otherProject, other, 'Ajeno']]) {
    applied(await apply('projects', 'create', id, { name }, null, user))
  }
  const task = { title: 'Preparar', task_type: 'project', project_id: project, date: '2026-11-18', start_time: '09:00', end_time: '10:00' }
  const legacyA = applied(await apply('tasks', 'create', a, task))
  const legacyB = applied(await apply('tasks', 'create', b, { ...task, title: 'Entregar', prerequisite_ids: [a] }))
  const before = (await snapshot()).data.tasks

  await applyFile(migration)
  assert.deepEqual((await snapshot()).data.tasks, before)
  assert.deepEqual((await snapshot()).data.project_features, [])

  const feature = applied(await apply('project_features', 'create', first, { name: ' Preparación ', description: ' Borrador ', project_id: project }))
  assert.equal(feature.name, 'Preparación')
  assert.equal(feature.description, 'Borrador')
  assert.equal((await apply('project_features', 'create', randomUUID(), { name: 'preparación', project_id: project })).reason, 'duplicate_feature_name')
  applied(await apply('project_features', 'create', randomUUID(), { name: 'Preparación', project_id: secondProject }))
  const feature2 = applied(await apply('project_features', 'create', second, { name: 'Entrega', project_id: project }))
  applied(await apply('project_features', 'create', foreign, { name: 'Ajena', project_id: otherProject }, null, other))
  assert.equal((await apply('project_features', 'create', randomUUID(), { name: 'Ajena', project_id: otherProject })).status, 'rejected')
  assert.equal((await apply('project_features', 'update', first, { project_id: secondProject }, feature._sync)).status, 'rejected')
  assert.equal((await snapshot(other)).data.project_features.length, 1)
  await asUser(other, async () => {
    assert.deepEqual((await db.query('select id from "Task".project_features')).rows, [{ id: foreign }])
    await assert.rejects(db.query('delete from "Task".project_features where id = $1', [foreign]), { code: '42501' })
  })

  const assigned = applied(await apply('tasks', 'update', a, { feature_id: first }, legacyA._sync))
  const linked = applied(await apply('tasks', 'update', b, { feature_id: second }, legacyB._sync))
  assert.deepEqual(linked.prerequisite_ids, [a])
  assert.equal((await apply('tasks', 'update', b, { feature_id: foreign }, linked._sync)).status, 'rejected')
  assert.equal((await apply('tasks', 'update', b, { completed: true }, linked._sync)).status, 'rejected')
  assert.equal((await apply('tasks', 'update', a, { prerequisite_ids: [b] }, assigned._sync)).status, 'rejected')

  // Payload and base with the same shape as a pre-feature offline Android edit.
  const edited = applied(await apply('tasks', 'update', a, { title: 'Editada sin conexión' }, legacyA._sync))
  assert.equal(edited.feature_id, first)
  const completedA = applied(await apply('tasks', 'update', a, { completed: true }, edited._sync))
  const completedB = applied(await apply('tasks', 'update', b, { completed: true }, linked._sync))
  assert.equal((await apply('tasks', 'update', a, { completed: false }, completedA._sync)).status, 'rejected')
  const relocated = applied(await apply('tasks', 'update', b, { feature_id: first }, completedB._sync))
  assert.equal(relocated.completed, true)
  assert.deepEqual(relocated.prerequisite_ids, [a])
  applied(await apply('project_features', 'delete', first, {}, feature._sync))
  const after = (await snapshot()).data
  assert.ok(!after.project_features.some(row => row.id === first))
  const kept = after.tasks.find(row => row.id === b)
  assert.equal(kept.feature_id, null)
  assert.ok(kept._sync.revision > relocated._sync.revision)
  assert.deepEqual(kept.prerequisite_ids, [a])
  assert.equal(kept.date, task.date)
  assert.equal(kept.completed, true)
  const afterOffline = applied(await apply('tasks', 'update', b, { title: 'Sin conexión después del borrado' }, relocated._sync))
  assert.equal(afterOffline.feature_id, null)
  assert.equal((await apply('tasks', 'update', b, { feature_id: first }, afterOffline._sync)).status, 'rejected')

  const newId = randomUUID()
  const newLegacy = applied(await apply('tasks', 'create', newId, { ...task, title: 'Nueva desde Android' }))
  assert.equal(newLegacy.feature_id, null)
  const newFeatureTask = applied(await apply('tasks', 'create', randomUUID(), { ...task, title: 'Nueva con funcionalidad', feature_id: second }))
  assert.equal(newFeatureTask.feature_id, second)
  const legacyMove = applied(await apply('tasks', 'update', newFeatureTask.id, { project_id: secondProject }, newFeatureTask._sync))
  assert.equal(legacyMove.feature_id, null)
  assert.equal(legacyMove.project_id, secondProject)

  const projectState = (await snapshot()).data.projects.find(row => row.id === project)._sync
  applied(await apply('projects', 'delete', project, {}, projectState))
  const remaining = (await snapshot()).data
  assert.ok(!remaining.tasks.some(row => row.project_id === project))
  assert.ok(!remaining.project_features.some(row => row.id === feature2.id))
  assert.ok(remaining.tasks.some(row => row.id === legacyMove.id))
  assert.ok((await snapshot(other)).data.project_features.some(row => row.id === foreign))
  console.log('Feature migration, ownership, project dependencies, deletion and legacy sync passed.')

  const beforeLinks = await snapshot()
  await applyFile('20261118223658_project_feature_links.sql')
  const withLinks = await snapshot()
  assert.equal(withLinks.protocol, 1)
  assert.deepEqual(withLinks.data.project_feature_links, [])
  assert.deepEqual(withLinks.data.tasks, beforeLinks.data.tasks)
  const added = []
  for (const name of ['Planteamiento', 'Desarrollo', 'Entrega final']) {
    added.push(applied(await apply('project_features', 'create', randomUUID(), { project_id: secondProject, name })))
  }
  const [p, d, e] = added.map(row => row.id)
  const linkFields = (source_feature_id, target_feature_id) => ({ project_id: secondProject, source_feature_id, target_feature_id })
  const add = (source, target) => apply('project_feature_links', 'create', randomUUID(), linkFields(source, target))
  const pd = applied(await add(p, d))
  const de = applied(await add(d, e))
  assert.equal((await add(p, d)).status, 'rejected')
  assert.equal((await add(p, p)).status, 'rejected')
  assert.match((await add(e, p)).message, /ciclo/)
  const separateProject = randomUUID()
  applied(await apply('projects', 'create', separateProject, { name: 'Proyecto separado' }))
  const separate = applied(await apply('project_features', 'create', randomUUID(), { name: 'Separada', project_id: separateProject }))
  assert.equal((await add(p, separate.id)).status, 'rejected')
  const otherFeature = applied(await apply('project_features', 'create', randomUUID(), { name: 'Otra', project_id: otherProject }, null, other))
  assert.equal((await add(p, otherFeature.id)).status, 'rejected')
  assert.deepEqual((await snapshot(other)).data.project_feature_links, [])
  await asUser(owner, async () => {
    await assert.rejects(db.query('delete from "Task".project_feature_links where id = $1', [pd.id]), { code: '42501' })
  })
  const legacyUpdate = applied(await apply('tasks', 'update', legacyMove.id, { title: 'Editada desde una versión anterior' }, legacyMove._sync))
  assert.equal(legacyUpdate.project_id, secondProject)
  applied(await apply('tasks', 'create', randomUUID(), { ...task, project_id: secondProject, feature_id: p, title: 'Previa pendiente' }))
  const freeToComplete = applied(await apply('tasks', 'create', randomUUID(), { ...task, project_id: secondProject, feature_id: d, completed: true }))
  assert.equal(freeToComplete.completed, true)
  const unchangedTasks = (await snapshot()).data.tasks
  applied(await apply('project_feature_links', 'delete', pd.id, {}, pd._sync))
  assert.deepEqual((await snapshot()).data.tasks, unchangedTasks)
  assert.deepEqual((await snapshot()).data.project_feature_links.map(row => row.id), [de.id])
  applied(await apply('project_features', 'delete', e, {}, added[2]._sync))
  assert.equal((await snapshot()).data.project_feature_links.length, 0)
  applied(await add(p, d))
  const secondState = (await snapshot()).data.projects.find(row => row.id === secondProject)._sync
  applied(await apply('projects', 'delete', secondProject, {}, secondState))
  assert.equal((await snapshot()).data.project_feature_links.length, 0)
  assert.ok((await snapshot(other)).data.project_features.some(row => row.id === foreign))
  console.log('Feature links verified: separate planning, ownership, cycles, removal and cascades.')
} finally {
  await db.close()
}
