"""Isolated PostgreSQL 17 sync checks. Never reads DATABASE_URL or Supabase keys.
Requires a Docker container labelled studycalendar.test=offline-sync.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parents[1]
DB = 'studycalendar_sync_test'
USER = '11111111-1111-4111-8111-111111111111'
OTHER = '22222222-2222-4222-8222-222222222222'
TASK = '33333333-3333-4333-8333-333333333333'
PROJECT = '44444444-4444-4444-8444-444444444444'
ROUTINE = '55555555-5555-4555-8555-555555555555'


def run(container, sql, database=DB, check=True):
    result = subprocess.run(['docker', 'exec', '-i', container, 'psql', '-X', '-U', 'postgres', '-d', database, '-v', 'ON_ERROR_STOP=1', '-At'], input=sql, text=True, encoding='utf-8', capture_output=True)
    if check and result.returncode:
        raise RuntimeError(result.stderr + '\n' + result.stdout)
    return result.stdout.strip()


def operation(entity='tasks', action='update', identifier=TASK, base=None, changes=None, context=None):
    import uuid
    return dict(protocol=1, operation_id=str(uuid.uuid4()), entity=entity, action=action, id=identifier, base=base, changes=changes or {}, context=context or {})


def call(container, op, user=USER):
    sql = f"set role authenticated; select set_config('request.jwt.claim.sub','{user}',false); select \"Task\".sync_apply($op${json.dumps(op)}$op$::jsonb);"
    return json.loads(run(container, sql).splitlines()[-1])


def base(container, entity='tasks', identifier=TASK):
    return json.loads(run(container, f"select jsonb_build_object('revision',revision,'state',state) from \"Task\".sync_entities where user_id='{USER}' and entity='{entity}' and id='{identifier}'"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', default='studycalendar-sync-test')
    parser.add_argument('--debug-sql', action='store_true')
    args = parser.parse_args()
    container = args.container
    label = subprocess.run(['docker', 'inspect', '--format', '{{index .Config.Labels "studycalendar.test"}}', container], capture_output=True, text=True, check=True).stdout.strip()
    if label != 'offline-sync':
        raise SystemExit('Refusing a container without the isolated-test label')
    # This named disposable database belongs exclusively to the labelled container.
    run(container, f'DROP DATABASE IF EXISTS {DB} WITH (FORCE); CREATE DATABASE {DB};', 'postgres')
    run(container, '''do $$ begin if not exists(select 1 from pg_roles where rolname='anon') then create role anon; end if; if not exists(select 1 from pg_roles where rolname='authenticated') then create role authenticated; end if; end $$;
      create schema auth; create table auth.users(id uuid primary key);
      create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
      grant usage on schema auth to anon,authenticated; grant execute on function auth.uid() to anon,authenticated;''')
    migration = next((ROOT / 'supabase/migrations').glob('*_offline_sync.sql'))
    for path in sorted((ROOT / 'supabase/migrations').glob('*.sql')):
        if path.name.startswith('202610040002') or path.name >= migration.name:
            continue
        run(container, path.read_text(encoding='utf-8-sig'))
    # Existing data and its exact values must survive the new migration.
    run(container, f"insert into auth.users values('{USER}'),('{OTHER}'); insert into \"Task\".tasks(id,user_id,title,date,start_time,end_time,color,task_type,all_day) values('{TASK}','{USER}','Antes','2026-11-13','09:00','10:00','#4F46E5','daily',true);")
    before = run(container, 'select to_jsonb(t) from "Task".tasks t')
    migration_sql=migration.read_text(encoding='utf-8-sig')
    if args.debug_sql:
        migration_sql=migration_sql.replace("diagnostic:='La combinación no cumple las reglas. Revisa títulos, nombres, fechas, horarios y asociaciones.'", "diagnostic:=SQLSTATE||':'||SQLERRM")
    run(container, 'begin;\n' + migration_sql + '\ncommit;')
    assert before == run(container, 'select to_jsonb(t) from "Task".tasks t')
    original = base(container)
    first = operation(base=original, changes={'title': 'Móvil'})
    result = call(container, first)
    assert result['status'] == 'applied', result
    assert call(container, first) == result  # lost response, stable receipt
    assert call(container, operation(base=original, changes={'completed': True}))['status'] == 'applied'
    conflict = call(container, operation(base=original, changes={'title': 'Web'}))
    assert conflict['status'] == 'conflict' and conflict['fields'] == ['title'], conflict
    assert call(container, operation(base=conflict['remote'], changes={'title': 'Resuelto'}))['status'] == 'applied'
    rejected = call(container, operation(base=base(container), changes={'end_time': '08:00'}))
    assert rejected['status'] == 'rejected', rejected
    assert base(container)['state']['end_time'] == '10:00:00'
    assert call(container, operation(base=original, changes={'title': 'Ajeno'}), OTHER)['status'] == 'conflict'
    run(container, f'''set role authenticated; select set_config('request.jwt.claim.sub','{OTHER}',false);
      do $$ begin if exists(select 1 from "Task".sync_entities) then raise exception 'RLS leak'; end if; end $$;''')
    run(container, f'''set role authenticated; select set_config('request.jwt.claim.sub','{USER}',false);
      do $$ begin
        begin update "Task".tasks set title='Legacy' where id='{TASK}'; raise exception 'legacy write accepted' using errcode='XX000'; exception when raise_exception then null; end;
        begin insert into "Task".sync_operations values('{USER}',gen_random_uuid(),'{{}}','{{}}',now()); raise exception 'forged receipt accepted' using errcode='XX000'; exception when insufficient_privilege then null; end;
      end $$;''')
    # Both connections start from one base. The second waits, then merges fields.
    shared = base(container)
    a = operation(base=shared, changes={'title': 'Concurrente'})
    b = operation(base=shared, changes={'completed': False})
    def hold():
        return run(container, f"begin; set role authenticated; select set_config('request.jwt.claim.sub','{USER}',true); select \"Task\".sync_apply($op${json.dumps(a)}$op$); select pg_sleep(1); commit;")
    with ThreadPoolExecutor(max_workers=2) as pool:
        future = pool.submit(hold)
        time.sleep(.2)
        started = time.monotonic()
        second = call(container, b)
        waited = time.monotonic() - started
        assert second['status'] == 'applied', second
        future.result()
        assert waited > .5, 'RPC did not serialize same-user writes'
    assert base(container)['state']['title'] == 'Concurrente'
    assert base(container)['state']['completed'] is False
    # Projects, undated tasks, inactive assignments, children and tombstones.
    assert call(container, operation('projects','create',PROJECT,changes={'name':'Proyecto','color':'#16A085'}))['status']=='applied'
    import uuid
    child=str(uuid.uuid4())
    assert call(container, operation('tasks','create',child,changes={'title':'Sin fecha','task_type':'project','project_id':PROJECT,'date':None,'start_time':None,'end_time':None,'all_day':False}))['status']=='applied'
    project_base=base(container,'projects',PROJECT)
    assert call(container, operation('tasks','update',child,base(container,'tasks',child),{'title':'Cambio hijo'}))['status']=='applied'
    assert call(container, operation('projects','delete',PROJECT,project_base))['status']=='conflict'
    assert call(container, operation('projects','delete',PROJECT,base(container,'projects',PROJECT)))['status']=='applied'
    assert base(container,'tasks',child)['state']['title']=='Cambio hijo'
    assert run(container,f"select deleted from \"Task\".sync_entities where id='{child}'")=='t'
    assert call(container, operation('projects','create',PROJECT,changes={'name':'Recreado'}))['status']=='conflict'
    # Habit actions retain original day/context across a later pause.
    assert call(container, operation('routine','create',ROUTINE,changes={'title':'Leer','color':'#16A085','active':True}))['status']=='applied'
    habit_base=base(container,'routine',ROUTINE)
    recorded=run(container,'select clock_timestamp()')
    today=run(container,"select (\"Task\".sync_day(clock_timestamp(),'00:00')->>'date')")
    ctx=dict(day=today,recorded_at=recorded,reset_time='00:00',routine_revision=habit_base['revision'],settings_revision=0)
    assert call(container, operation('routine','update',ROUTINE,habit_base,{'active':False}))['status']=='applied'
    completion=call(container, operation('routine_completion','complete',ROUTINE,{'state':{'completed':False},'revision':0},{'completed':True},ctx))
    assert completion['status']=='applied', completion
    assert run(container,f"select occurrence_date from \"Task\".routine_completion where routine_id='{ROUTINE}'")==today
    # Offline creation can be acknowledged after the original completion day.
    offline_id=str(uuid.uuid4())
    old_recorded=run(container, "select clock_timestamp()-interval '1 day'")
    old_day=run(container, "select (\"Task\".sync_day(clock_timestamp()-interval '1 day','00:00')->>'date')")
    create_offline=operation('routine','create',offline_id,changes={'title':'Creada offline','color':'#16A085','active':True},context={'recorded_at':old_recorded,'day':old_day,'reset_time':'00:00','settings_revision':0,'starts_on':old_day})
    created=call(container,create_offline)
    assert created['status']=='applied',created
    assert call(container,create_offline)==created
    queued_context=dict(day=old_day,recorded_at=old_recorded,reset_time='00:00',settings_revision=0,routine_revision=created['row']['_sync']['revision'],routine_operation_id=create_offline['operation_id'])
    old_mark=call(container,operation('routine_completion','complete',offline_id,{'revision':0,'state':{'completed':False}},{'completed':True},queued_context))
    assert old_mark['status']=='applied',old_mark
    assert run(container,f"select occurrence_date from \"Task\".routine_completion where routine_id='{offline_id}'")==old_day
    # A merged schedule can be invalid even when each edit was valid in isolation.
    schedule_base=base(container)
    assert call(container,operation(base=schedule_base,changes={'start_time':'09:30'}))['status']=='applied'
    before_reject=base(container)
    invalid_merge=call(container,operation(base=schedule_base,changes={'end_time':'09:15'}))
    assert invalid_merge['status']=='rejected',invalid_merge
    assert base(container)==before_reject
    # DST boundaries match Kotlin (first repeated reset, first valid missing minute).
    assert run(container,"select \"Task\".sync_day('2026-10-25T00:29:59Z','02:30')->>'date'")=='2026-10-24'
    assert run(container,"select \"Task\".sync_day('2026-10-25T00:30:00Z','02:30')->>'date'")=='2026-10-25'
    assert run(container,"select (\"Task\".sync_day('2026-03-29T00:59:59Z','02:30')->>'next_day_at')::timestamptz='2026-03-29T01:00:00Z'::timestamptz")=='t'
    # The RPC snapshot uses SQL aggregation, never a REST page truncated at 1000.
    historic_id=str(uuid.uuid4())
    historic_start=run(container,"select current_date-1500")
    assert call(container,operation('routine','create',historic_id,changes={'title':'Historial completo','color':'#16A085'},context={'starts_on':historic_start}))['status']=='applied'
    run(container,f"insert into \"Task\".routine_completion(routine_id,user_id,occurrence_date,completed) select '{historic_id}','{USER}',current_date-i,true from generate_series(0,1500) i")
    count=run(container,f"set role authenticated; select set_config('request.jwt.claim.sub','{USER}',false); select jsonb_array_length(\"Task\".sync_snapshot()->'data'->'routine_completion')")
    assert int(count.splitlines()[-1])>=1501
    for pattern in ('*_project_features.sql', '*_project_feature_links.sql'):
        path = next((ROOT / 'supabase/migrations').glob(pattern))
        run(container, path.read_text(encoding='utf-8-sig'))
    linked_project, a, b = (str(uuid.uuid4()) for _ in range(3))
    assert call(container, operation('projects', 'create', linked_project, changes={'name': 'Plan de prueba'}))['status'] == 'applied'
    for feature_id, name in ((a, 'A'), (b, 'B')):
        assert call(container, operation('project_features', 'create', feature_id,
                                         changes={'project_id': linked_project, 'name': name}))['status'] == 'applied'

    def feature_edge(source, target):
        return operation('project_feature_links', 'create', str(uuid.uuid4()), changes={
            'project_id': linked_project, 'source_feature_id': source, 'target_feature_id': target,
        })

    first, second = feature_edge(a, b), feature_edge(b, a)
    def hold_feature_edge():
        sql = (f"begin; set role authenticated; select set_config('request.jwt.claim.sub','{USER}',true); "
               f"select \"Task\".sync_apply($op${json.dumps(first)}$op$); select pg_sleep(1); commit;")
        return next(json.loads(line) for line in run(container, sql).splitlines() if line.startswith('{'))

    # Each edge would succeed on its own. The two-session race may commit only one.
    with ThreadPoolExecutor(max_workers=2) as pool:
        held = pool.submit(hold_feature_edge)
        time.sleep(.2)
        started = time.monotonic()
        result = call(container, second)
        assert time.monotonic() - started > .5, 'Feature writes did not overlap'
        assert held.result()['status'] == 'applied'
    assert result['status'] == 'rejected' and 'ciclo' in result['message'], result
    assert run(container, 'select count(*) from "Task".project_feature_links') == '1'
    print('Passed: migration preservation, receipts, field merge/conflicts/resolution, validation rollback, RLS, guarded legacy writes, protected ledger, real two-connection serialization, atomic project deletion, tombstones, historical habit context and concurrent feature-cycle prevention.')


if __name__ == '__main__':
    main()
