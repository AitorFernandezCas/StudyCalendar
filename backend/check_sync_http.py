"""Exercise the sync RPC over real PostgREST/JWT in disposable Docker services.
Never reads cloud credentials; requires check_sync_db.py to have succeeded first.
"""
import base64
import hashlib
import hmac
import json
import subprocess
import socket
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from uuid import uuid4
from check_sync_db import run, USER, OTHER, TASK

PG='studycalendar-sync-test'
REST='studycalendar-sync-rest-test'
NETWORK='studycalendar-sync-test-net'
SECRET='local-sync-test-jwt-secret-not-for-any-real-project-000001'


def docker(*args):
    return subprocess.run(['docker',*args],text=True,encoding='utf-8',capture_output=True,check=True).stdout.strip()


def token(user):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).decode().rstrip('=')
    body=encode({'alg':'HS256','typ':'JWT'})+'.'+encode({'role':'authenticated','sub':user,'exp':int(time.time())+3600})
    return body+'.'+base64.urlsafe_b64encode(hmac.new(SECRET.encode(),body.encode(),hashlib.sha256).digest()).decode().rstrip('=')


def main():
    assert docker('inspect','--format','{{index .Config.Labels "studycalendar.test"}}',PG)=='offline-sync'
    run(PG,"""do $$ begin if not exists(select 1 from pg_roles where rolname='authenticator') then create role authenticator login noinherit password 'local-sync-http-only'; end if; end $$;
      grant authenticated,anon to authenticator;
      create or replace function auth.uid() returns uuid language sql stable as $$ select coalesce(nullif(current_setting('request.jwt.claim.sub',true),''),nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'sub')::uuid $$;""")
    existing=subprocess.run(['docker','network','inspect',NETWORK],capture_output=True,text=True)
    if existing.returncode:
        docker('network','create','--label','studycalendar.test=offline-sync',NETWORK)
    else:
        assert json.loads(existing.stdout)[0]['Labels']['studycalendar.test']=='offline-sync'
    networks=json.loads(docker('inspect',PG))[0]['NetworkSettings']['Networks']
    if NETWORK not in networks: docker('network','connect',NETWORK,PG)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1',0))
        port=listener.getsockname()[1]
    docker('run','--name',REST,'--label','studycalendar.test=offline-sync','--rm','-d','--network',NETWORK,'-p',f'127.0.0.1:{port}:3000',
      '-e','PGRST_DB_URI=postgres://authenticator:local-sync-http-only@'+PG+':5432/studycalendar_sync_test',
      '-e','PGRST_DB_SCHEMAS=Task','-e','PGRST_DB_ANON_ROLE=anon','-e','PGRST_JWT_SECRET='+SECRET,'public.ecr.aws/supabase/postgrest:v16.4')
    try:
        base='http://127.0.0.1:'+str(port)
        def request(path,body=None,user=USER,method='POST'):
            headers={'Content-Type':'application/json','Content-Profile':'Task','Accept-Profile':'Task'}
            if user: headers['Authorization']='Bearer '+token(user)
            req=Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
            try:
                with urlopen(req,timeout=30) as response: return response.status,json.load(response)
            except HTTPError as error: return error.code,json.load(error)
        deadline=time.monotonic()+30
        while True:
            try:
                status,snapshot=request('/rpc/sync_snapshot',{})
                if status==200: break
            except (URLError,ConnectionError): pass
            if time.monotonic()>deadline: raise RuntimeError('PostgREST did not become ready')
            time.sleep(.2)
        assert snapshot['owner']==USER and snapshot['protocol']==1
        task=next(t for t in snapshot['data']['tasks'] if t['id']==TASK)
        op=dict(protocol=1,operation_id=str(uuid4()),entity='tasks',id=TASK,action='update',base=task['_sync'],changes={'title':'HTTP verificado'},context={})
        status,result=request('/rpc/sync_apply',{'p_operation':op})
        assert status==200 and result['status']=='applied',(status,result)
        assert request('/rpc/sync_apply',{'p_operation':op})==(status,result)
        _,other=request('/rpc/sync_snapshot',{},OTHER)
        assert other['data']['tasks']==[]
        denied,_=request('/tasks?select=id',None,None,'GET')
        assert denied in (401,403)
        legacy,body=request('/tasks?id=eq.'+TASK,{'title':'Legacy'},USER,'PATCH')
        assert legacy==400 and body['code']=='P0001',(legacy,body)
        print('Passed: real PostgREST profile, authenticated JWT snapshot/write, idempotent HTTP replay, cross-user isolation, anonymous denial and legacy-write guard.')
    finally:
        assert docker('inspect','--format','{{index .Config.Labels "studycalendar.test"}}',REST)=='offline-sync'
        docker('stop',REST)


if __name__=='__main__': main()
