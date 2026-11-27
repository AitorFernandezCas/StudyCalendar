// In-memory PostgreSQL only. Never reads credentials or DATABASE_URL.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFileSync, readdirSync } from 'node:fs'
const { PGlite } = await import(process.env.PGLITE_MODULE || '@electric-sql/pglite')
const db = new PGlite()
const directory = new URL('../../supabase/migrations/', import.meta.url)
const owner = randomUUID()
const other = randomUUID()
async function asUser(user, run) {
  await db.exec('set role authenticated')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  try { return await run() } finally { await db.exec('reset role; reset request.jwt.claim.sub') }
}
async function apply(entity, action, id, changes = {}, base = null, user = owner, operationId = randomUUID()) {
  const operation = { protocol: 1, operation_id: operationId, entity, action, id, changes, base, context: {} }
  return asUser(user, async () => (await db.query('select "Task".sync_apply($1::jsonb) result', [JSON.stringify(operation)])).rows[0].result)
}
function applied(result) { assert.equal(result.status, 'applied', JSON.stringify(result)); return result.row }
try {
  await db.exec(`create role anon; create role authenticated; create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated,anon;
    grant execute on function auth.uid() to authenticated,anon;`)
  for (const name of readdirSync(directory).sort()) {
    if (name.endsWith('.sql') && !name.startsWith('202610040002')) await db.exec(readFileSync(new URL(name, directory), 'utf8'))
  }
  await db.query('insert into auth.users values ($1),($2)', [owner, other])
  const id = randomUUID()
  const draft = { title: 'Leer', period: 'MONTH', starts_on: '2026-11-01', target: 12, unit: 'libros', achieved: false }
  const row = applied(await apply('goals', 'create', id, draft))
  const a = randomUUID(), b = randomUUID(), receipt = randomUUID()
  const review = { goal_id: id, recorded_at: '2026-11-23T12:00:00Z', amount: 3, note: 'Avance' }
  applied(await apply('goal_reviews', 'create', a, review, null, owner, receipt))
  applied(await apply('goal_reviews', 'create', a, review, null, owner, receipt))
  applied(await apply('goal_reviews', 'create', b, { ...review, amount: 4 }))
  assert.equal((await db.query('select count(*)::int n from "Task".goal_reviews')).rows[0].n, 2)
  assert.equal((await apply('goal_reviews', 'create', randomUUID(), review, null, other)).status, 'rejected')
  assert.equal((await asUser(other, async () => (await db.query('select * from "Task".goals')).rows)).length, 0)
  const updated = applied(await apply('goals', 'update', id, { title: 'Más lectura' }, row._sync))
  assert.equal((await apply('goals', 'update', id, { title: 'Conflicto' }, row._sync)).status, 'conflict')
  applied(await apply('goals', 'update', id, { achieved: true }, updated._sync))
  assert.equal((await apply('goals', 'create', randomUUID(), { ...draft, starts_on: '2026-11-02' })).status, 'rejected')
  const scheduleId = randomUUID()
  applied(await apply('goal_schedules', 'create', scheduleId, { period: 'YEAR', enabled: true, frequency: 'MONTHLY', time: '20:00', weekday: 7, month_day: 31 }))
  const snapshot = await asUser(owner, async () => (await db.query('select "Task".sync_snapshot() snapshot')).rows[0].snapshot)
  assert.equal(snapshot.data.goal_reviews.length, 2)
  applied(await apply('goals', 'delete', id, {}, snapshot.data.goals[0]._sync))
  assert.equal((await db.query('select count(*)::int n from "Task".goal_reviews')).rows[0].n, 0)
  console.log('Goals migration, reviews, idempotency, conflicts, schedules and ownership passed.')
} finally { await db.close() }
