// Optional isolated PostgreSQL runner. Set PGLITE_MODULE to an installed PGlite
// module URL, or install @electric-sql/pglite separately. Never uses DATABASE_URL.
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
const { PGlite } = await import(process.env.PGLITE_MODULE || '@electric-sql/pglite')
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const db = new PGlite()
try {
  await db.exec(`create role anon; create role authenticated;
    create schema auth; create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated,anon;
    grant execute on function auth.uid() to authenticated,anon;`)
  for (const migration of ['202610040001_create_tasks', '202610040003_create_categories', '202610040004_add_task_type', '202610040009_create_projects', '20261009220556_task_all_day', '20261010164339_project_status']) {
    await db.exec(readFileSync(resolve(root, 'supabase/migrations', migration + '.sql'), 'utf8'))
  }
  const sql = readFileSync(resolve(root, 'backend/test_project_plans.sql'), 'utf8')
    .replace(/^\\ir (.+)$/gm, (_, path) => readFileSync(resolve(root, 'backend', path.trim()), 'utf8'))
  await db.exec(sql)
  const { rows } = await db.query(`select count(*)::int count from "Task".tasks`)
  if (rows[0].count !== 0) throw new Error('Fixtures did not roll back')
  console.log('Project plan migration, invariants, RLS, deletion and rollback passed in isolated PostgreSQL.')
} finally { await db.close() }
