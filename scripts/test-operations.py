"""Isolated release/restore/deployment contracts. No real gh/docker writes."""
import json
import gzip
import os
import pathlib
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
MOCK = r'''#!/usr/bin/env python3
import json,os,pathlib,sys,shutil
path=pathlib.Path(os.environ['MOCK_STATE']);s=json.loads(path.read_text());args=sys.argv[1:]
digest='sha256:'+'a'*64
def save(): path.write_text(json.dumps(s))
if pathlib.Path(sys.argv[0]).name=='jq':
 raw_args=sys.argv[1:];args=list(raw_args);named={};named_json={};flags=set();null_input=False
 while args and args[0].startswith('-'):
  flag=args.pop(0);flags.add(flag)
  if flag in ('-n','--null-input'):null_input=True
  elif flag in ('--arg','--argjson'):
   key=args.pop(0);value=args.pop(0)
   if flag=='--arg':named[key]=value
   else:named_json[key]=json.loads(value)
 expression=args.pop(0)
 text='{}' if null_input else (pathlib.Path(args[0]).read_text() if args else sys.stdin.read())
 data=json.loads(text)
 if expression=='.':result=data
 elif expression=='{sha:$sha,image:$image,version:$version,digest:$digest,assets:$assets}':
  result={key:named[key] for key in ('sha','image','version','digest')};result['assets']=named_json['assets']
 elif expression=='. + {($name): $hash}':data[named['name']]=named['hash'];result=data
 elif expression=='."containerimage.digest"':result=data['containerimage.digest']
 elif expression=='.digest':result=data['digest']
 elif expression=='.assets[$name]':result=data['assets'][named['name']]
 else:
  valid=True
  for key in ('sha','image','version'):
   if f'.{key}==${key}' in expression:valid=valid and data.get(key)==named[key]
  if 'test("^sha256:' in expression:valid=valid and isinstance(data.get('digest'),str) and len(data['digest'])==71 and data['digest'].startswith('sha256:')
  if '.assets|length==2' in expression:valid=valid and len(data.get('assets',{}))==2
  if '.assets[$apk]|type=="string"' in expression:valid=valid and isinstance(data['assets'].get(named['apk']),str)
  if '.assets[$deployment]|type=="string"' in expression:valid=valid and isinstance(data['assets'].get(named['deployment']),str)
  result=valid
 if isinstance(result,str) and flags.intersection({'-r','-er'}):print(result)
 else:print(json.dumps(result,separators=(',',':')))
 sys.exit(0 if result is not False else 1)
if pathlib.Path(sys.argv[0]).name=='docker':
 if args[:3]==['buildx','imagetools','inspect']:
  if s.get('outage'):print('connection refused',file=sys.stderr);sys.exit(1)
  ref=args[3];key=ref.split(':')[-1]
  value=s.get('digests',{}).get(ref.split('@',1)[1],digest) if '@sha256:' in ref else s['tags'].get(key)
  if not value:print('manifest unknown',file=sys.stderr);sys.exit(1)
  print(json.dumps({'digest':value}))
 elif args[:2]==['buildx','build']:
  s['builds']+=1
  s['platforms']=args[args.index('--platform')+1] if '--platform' in args else ''
  pathlib.Path(args[args.index('--metadata-file')+1]).write_text(json.dumps({'containerimage.digest':digest}));save()
 elif args[:3]==['buildx','imagetools','create']:
  tag=args[args.index('-t')+1].split(':')[-1]
  s['promotions'].append(tag)
  if s.get('fail_once') and tag.startswith('v'):
   s['fail_once']=False;save();sys.exit(1)
  s['tags'][tag]=digest;save()
 else:sys.exit(2)
else:
 if args[:1]==['api']:
  if s.get('outage'):print('connection refused',file=sys.stderr);sys.exit(1)
  query=args[args.index('--jq')+1]
  if '.name ==' in query:
   wanted=query.split('.name == "',1)[1].split('"',1)[0]
   values=[value for value in s['tags'].values() if value==wanted]
  else:
   wanted=query.split('index("',1)[1].split('"',1)[0]
   values=[s['tags'][wanted]] if wanted in s['tags'] else []
  for value in values: print(value)
 elif args[:2]==['release','view']:
  if len(args)==2 or args[2].startswith('--'):
   if not s.get('latest_tag'):print('no releases',file=sys.stderr);sys.exit(1)
   print(s['latest_tag']);sys.exit(0)
  if not s['release']:print('release not found',file=sys.stderr);sys.exit(1)
  if '--jq' in args and '.body' in args[args.index('--jq')+1]:print(s.get('body',''))
  else:print('true' if s['release']=='draft' else 'false')
 elif args[:2]==['release','create']:
  assets=pathlib.Path(os.environ['HONGSHU_BUILD_DIR'])/'release'
  journal=path.parent/'journal';journal.mkdir()
  for file in args[3:]:
   if file.startswith('-'):break
   shutil.copyfile(file,journal/pathlib.Path(file).name)
  notes_file=pathlib.Path(args[args.index('--notes-file')+1])
  s['body']=notes_file.read_text();s['release']='draft';save()
 elif args[:2]==['release','download']:
  target=pathlib.Path(args[args.index('--dir')+1])
  for file in (path.parent/'journal').iterdir():shutil.copyfile(file,target/file.name)
 elif args[:2]==['release','edit']:s['release']='published';save()
 else:sys.exit(2)
'''


class Operations(unittest.TestCase):
    def setUp(self):
        # Caller selects an external build/cache location; never create repo artifacts.
        self.temp = tempfile.TemporaryDirectory(prefix='hongshu-ops-', dir=os.environ.get('HONGSHU_TEST_TMP'))
        self.root = pathlib.Path(self.temp.name)
        tools = self.root / 'bin'
        tools.mkdir()
        for tool in ('gh', 'docker', 'jq'):
            file = tools / tool
            file.write_text(MOCK)
            file.chmod(0o700)
        self.state = self.root / 'state.json'
        self.state.write_text(json.dumps(dict(tags={}, release=None, builds=0, promotions=[])))
        release = self.root / 'build/release'
        release.mkdir(parents=True)
        for name in ('Hongshu-Android-0.1.0-alpha.99.apk',
                     'hongshu-v0.1.0-alpha.99-deployment.tar.gz'):
            (release / name).write_text('synthetic fixture')
        self.notes = self.root / 'release-notes.md'
        self.notes.write_text('''# Synthetic release\n\n## 本次更新\n\n- Synthetic fixture\n\n## 快捷下载\n\n- [APK](https://github.com/synthetic/hongshu/releases/download/v0.1.0-alpha.99/Hongshu-Android-0.1.0-alpha.99.apk)\n''')
        self.env = dict(os.environ, PATH=str(tools)+':'+os.environ['PATH'], MOCK_STATE=str(self.state),
                        HONGSHU_BUILD_DIR=str(self.root/'build'), RUNNER_TEMP=str(self.root/'runner'),
                        RELEASE_NOTES_FILE=str(self.notes),
                        TAG='v0.1.0-alpha.99', VERSION='0.1.0-alpha.99', IS_PRERELEASE='true',
                        RELEASE_IMAGE='ghcr.io/synthetic/hongshu', RELEASE_OWNER='synthetic',
                        RELEASE_SHA='b'*40, GH_TOKEN='synthetic-token', GITHUB_SHA='b'*40,
                        GITHUB_RUN_ID='1', GITHUB_RUN_ATTEMPT='1', GITHUB_EVENT_NAME='workflow_dispatch')

    def tearDown(self):
        self.temp.cleanup()

    def run_publish(self, env=None):
        return subprocess.run(['bash', str(ROOT/'scripts/publish-release.sh')], env=env or self.env, capture_output=True).returncode

    def update(self, **values):
        state=json.loads(self.state.read_text());state.update(values);self.state.write_text(json.dumps(state))

    def test_preflight_missing_asset_prevents_registry_build(self):
        (self.root/'build/release/hongshu-v0.1.0-alpha.99-deployment.tar.gz').unlink()
        self.assertNotEqual(0,self.run_publish())
        self.assertEqual(0,json.loads(self.state.read_text())['builds'])

    def test_release_notes_contract_prevents_registry_build(self):
        self.notes.write_text('# Synthetic release\n\nNo download section.\n')
        self.assertNotEqual(0,self.run_publish())
        self.assertEqual(0,json.loads(self.state.read_text())['builds'])

    def test_auxiliary_asset_prevents_registry_build(self):
        (self.root/'build/release/IMAGE.json').write_text('must not be uploaded')
        self.assertNotEqual(0,self.run_publish())
        self.assertEqual(0,json.loads(self.state.read_text())['builds'])

    def test_retired_server_tools_asset_prevents_registry_build(self):
        (self.root/'build/release/hongshu-v0.1.0-alpha.99-server-tools-linux-amd64.tar.gz').write_text('retired asset')
        self.assertNotEqual(0,self.run_publish())
        self.assertEqual(0,json.loads(self.state.read_text())['builds'])

    def test_partial_promotion_resumes_same_digest_without_rebuild(self):
        self.update(fail_once=True)
        self.assertNotEqual(0,self.run_publish())
        state=json.loads(self.state.read_text());self.assertEqual('draft',state['release'])
        self.assertEqual(
            sorted(file.name for file in (self.root/'journal').iterdir()),
            sorted(file.name for file in (self.root/'build/release').iterdir()),
        )
        self.env['RUNNER_TEMP']=str(self.root/'retry')
        self.assertEqual(0,self.run_publish())
        state=json.loads(self.state.read_text())
        self.assertEqual(1,state['builds']);self.assertEqual('published',state['release'])
        self.assertEqual('linux/amd64,linux/arm64',state['platforms'])
        self.assertNotIn('latest',state['tags'])
        self.assertNotEqual(0,self.run_publish()) # public release cannot be overwritten

    def test_unjournaled_tag_and_registry_failure_are_fail_closed(self):
        self.update(tags={'0.1.0-alpha.99':'sha256:'+'c'*64})
        self.assertNotEqual(0,self.run_publish())
        self.update(tags={},outage=True)
        self.assertNotEqual(0,self.run_publish())
        self.assertEqual(1,json.loads(self.state.read_text())['builds'])

    def test_existing_matching_tags_can_publish_without_overwrite(self):
        self.update(tags={'0.1.0-alpha.99':'sha256:'+'a'*64,
                          'v0.1.0-alpha.99':'sha256:'+'a'*64})
        self.assertEqual(0,self.run_publish())
        state=json.loads(self.state.read_text())
        self.assertEqual(1,state['builds'])
        self.assertEqual('published',state['release'])
        self.assertEqual([],state['promotions'])

    def test_manual_republish_reuses_existing_digest(self):
        old='sha256:'+'c'*64
        self.update(tags={'0.1.0-alpha.99':old,'v0.1.0-alpha.99':old}, digests={old:old})
        self.assertEqual(0,self.run_publish())
        state=json.loads(self.state.read_text())
        self.assertEqual(1,state['builds'])
        self.assertEqual('published',state['release'])
        self.assertEqual([],state['promotions'])

    def test_corrupted_partial_draft_is_not_overwritten(self):
        self.update(fail_once=True);self.assertNotEqual(0,self.run_publish())
        (self.root/'journal/hongshu-v0.1.0-alpha.99-deployment.tar.gz').write_text('tampered')
        self.env['RUNNER_TEMP']=str(self.root/'retry')
        self.assertNotEqual(0,self.run_publish())
        self.assertEqual(1,json.loads(self.state.read_text())['builds'])

    def test_conflicting_digest_during_recovery_is_not_overwritten(self):
        self.update(fail_once=True);self.assertNotEqual(0,self.run_publish())
        self.update(tags={'0.1.0-alpha.99':'sha256:'+'c'*64})
        self.env['RUNNER_TEMP']=str(self.root/'retry')
        self.assertNotEqual(0,self.run_publish())
        state=json.loads(self.state.read_text())
        self.assertEqual('sha256:'+'c'*64,state['tags']['0.1.0-alpha.99'])
        self.assertEqual('draft',state['release'])
        self.assertEqual(1,state['builds'])

    def test_restore_generation_cannot_repeat_for_the_same_backup(self):
        def next_epoch(live,backup):
            return subprocess.check_output(['bash',str(ROOT/'scripts/next-restore-epoch.sh'),str(live),str(backup)],text=True).strip()
        first=next_epoch(1,1);second=next_epoch(first,1)
        self.assertEqual('2',first);self.assertEqual('3',second)
        self.assertEqual('11',next_epoch(3,10))
        for bad in ('-1','garbage','9007199254740991'):
            result=subprocess.run(['bash',str(ROOT/'scripts/next-restore-epoch.sh'),bad,'1'],capture_output=True)
            self.assertNotEqual(0,result.returncode)

    def test_deployment_requires_an_explicit_version_before_credentials(self):
        env=dict(os.environ);env.pop('HONGSHU_VERSION',None)
        script=self.root/'isolated/scripts/init-deployment.sh'
        script.parent.mkdir(parents=True)
        shutil.copyfile(ROOT/'scripts/init-deployment.sh',script)
        result=subprocess.run(['bash',str(script)],env=env,capture_output=True)
        self.assertNotEqual(0,result.returncode)
        self.assertIn(b'HONGSHU_VERSION',result.stderr)

    def test_backup_success_failure_timeout_retention_and_health(self):
        dump = self.root / 'bin/mysqldump'
        dump.write_text("#!/usr/bin/env bash\ncase ${MOCK_DUMP_MODE:-ok} in\n  fail) echo partial; exit 1 ;;\n  hang) sleep 10 ;;\n  *) printf 'CREATE TABLE synthetic (id INT);\\n' ;;\nesac\n")
        dump.chmod(0o700)
        backups = self.root / 'backups'
        env = dict(self.env)
        env.update(PATH=str(self.root/'bin')+':'+env['PATH'], BACKUP_DIR=str(backups),
                   BACKUP_ONCE='1', BACKUP_INTERVAL='1', BACKUP_TIMEOUT='2',
                   BACKUP_RETENTION_DAYS='30', BACKUP_MAX_FILES='2',
                   MYSQL_PWD='synthetic-not-a-credential')
        for _ in range(3):
            result = subprocess.run(['bash', str(ROOT/'scripts/backup.sh')], env=env,
                                    capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
        archives = list(backups.glob('hongshu-*.sql.gz'))
        self.assertEqual(2, len(archives))
        self.assertTrue((backups/'last-success').is_file())
        self.assertFalse((backups/'last-failure').exists())
        health = subprocess.run(['bash', str(ROOT/'scripts/backup-healthcheck.sh')], env=env,
                                capture_output=True)
        self.assertEqual(0, health.returncode, health.stderr)
        for archive in archives:
            with gzip.open(archive, 'rt') as stream:
                self.assertIn('CREATE TABLE synthetic', stream.read())

        failed_dir = self.root/'failed-backups'
        env.update(BACKUP_DIR=str(failed_dir), MOCK_DUMP_MODE='fail')
        result = subprocess.run(['bash', str(ROOT/'scripts/backup.sh')], env=env,
                                capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((failed_dir/'last-failure').is_file())
        self.assertFalse((failed_dir/'last-success').exists())
        self.assertTrue(list(failed_dir.glob('*.partial')))
        health = subprocess.run(['bash', str(ROOT/'scripts/backup-healthcheck.sh')], env=env,
                                capture_output=True)
        self.assertNotEqual(0, health.returncode)

        timeout_dir = self.root/'timeout-backups'
        env.update(BACKUP_DIR=str(timeout_dir), MOCK_DUMP_MODE='hang', BACKUP_TIMEOUT='1')
        subprocess.run(['bash', str(ROOT/'scripts/backup.sh')], env=env,
                       capture_output=True, timeout=10, check=True)
        self.assertTrue((timeout_dir/'last-failure').is_file())
        self.assertFalse((timeout_dir/'last-success').exists())

        stale = backups/'last-success'
        old = time.time()-10
        os.utime(stale, (old, old))
        env.update(BACKUP_DIR=str(backups), BACKUP_TIMEOUT='1', BACKUP_HEALTH_GRACE='0',
                   MOCK_DUMP_MODE='ok')
        health = subprocess.run(['bash', str(ROOT/'scripts/backup-healthcheck.sh')], env=env,
                                capture_output=True)
        self.assertNotEqual(0, health.returncode)

    def isolated_initializer(self):
        isolated = self.root/'initializer'
        (isolated/'scripts').mkdir(parents=True)
        shutil.copyfile(ROOT/'scripts/init-deployment.sh', isolated/'scripts/init-deployment.sh')
        for name in ('compose.yml', 'compose.external.yml'):
            shutil.copyfile(ROOT/name, isolated/name)
        docker = self.root/'bin/docker'
        docker.write_text("""#!/usr/bin/env bash
set -eu
[[ ${1:-} == compose ]] || exit 2
shift
[[ " $* " == *' version '* ]] && exit 0
[[ " $* " == *' pull '* ]] && [[ ${MOCK_PULL_FAIL:-0} != 1 ]] && exit 0
[[ " $* " == *' pull '* ]] && exit 1
[[ " $* " == *' config '* ]] && exit 0
if [[ " $* " == *' run '* ]]; then
  printf 'VAPID_PUBLIC_KEY=synthetic-public\\nVAPID_PRIVATE_KEY=synthetic-private\\n'
  exit 0
fi
exit 2
""")
        docker.chmod(0o700)
        return isolated

    def test_initializer_publishes_env_atomically_only_after_success(self):
        isolated = self.isolated_initializer()
        env = dict(self.env)
        env.update(PATH=str(self.root/'bin')+':'+env['PATH'], HONGSHU_VERSION='0.2.0-alpha.23',
                   HONGSHU_PASSWORD='synthetic-test-password', MOCK_PULL_FAIL='1')
        result = subprocess.run(['bash', str(isolated/'scripts/init-deployment.sh')], env=env,
                                capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((isolated/'.env').exists())
        self.assertFalse(list(isolated.glob('.env.pending.*')))
        env['MOCK_PULL_FAIL'] = '0'
        result = subprocess.run(['bash', str(isolated/'scripts/init-deployment.sh')], env=env,
                                capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        final = isolated/'.env'
        self.assertTrue(final.is_file())
        self.assertEqual(0o600, final.stat().st_mode & 0o777)
        body = final.read_text()
        self.assertIn('VAPID_PUBLIC_KEY=synthetic-public', body)
        self.assertIn('VAPID_PRIVATE_KEY=synthetic-private', body)
        self.assertFalse(list(isolated.glob('.env.pending.*')))

    def prepare_stable_publish(self, version, latest):
        tag = 'v'+version
        release = self.root/'build/release'
        for file in release.iterdir():
            file.unlink()
        for name in (f'Hongshu-Android-{version}.apk', f'hongshu-{tag}-deployment.tar.gz'):
            (release/name).write_text('synthetic stable fixture')
        self.notes.write_text(self.notes.read_text().replace('0.1.0-alpha.99', version))
        self.update(latest_tag=latest)
        env = dict(self.env)
        env.update(TAG=tag, VERSION=version, IS_PRERELEASE='false')
        return env

    def test_stable_latest_moves_only_forward(self):
        env = self.prepare_stable_publish('1.0.0', '2.0.0')
        self.assertEqual(0, self.run_publish(env))
        state = json.loads(self.state.read_text())
        self.assertNotIn('latest', state['promotions'])
        self.assertIn('v1.0.0', state['promotions'])

        other = Operations(methodName='test_stable_latest_moves_only_forward')
        other.setUp()
        try:
            env = other.prepare_stable_publish('2.0.0', '1.0.0')
            self.assertEqual(0, other.run_publish(env))
            self.assertIn('latest', json.loads(other.state.read_text())['promotions'])
        finally:
            other.tearDown()

    def test_semver_comparison_rejects_prerelease_and_regressions(self):
        script = ROOT/'scripts/compare-semver.sh'
        self.assertEqual(0, subprocess.run(['bash', str(script), 'v2.0.0', '1.9.9']).returncode)
        self.assertNotEqual(0, subprocess.run(['bash', str(script), '1.0.0', '1.0.0']).returncode)
        self.assertNotEqual(0, subprocess.run(['bash', str(script), '1.0.0', '2.0.0']).returncode)
        self.assertEqual(2, subprocess.run(['bash', str(script), '1.0.0-rc.1', '1.0.0'],
                                           capture_output=True).returncode)

    def test_external_backup_is_independent_and_migration_uses_image_sql(self):
        external = (ROOT/'compose.external-backup.yml').read_text()
        self.assertNotIn('depends_on:', external)
        self.assertIn('BACKUP_MYSQL_HOST', external)
        self.assertIn('/backup-healthcheck.sh', external)
        bundled = (ROOT/'compose.yml').read_text().split('  backup:', 1)[1]
        self.assertNotIn('hub: {condition: service_healthy}', bundled)
        database = (ROOT/'docs/DATABASE.md').read_text()
        self.assertNotIn('-v "$PWD/db/migrations:/app/migrations:ro"', database)
        dockerfile = (ROOT/'Dockerfile').read_text()
        self.assertIn('/app/migrations', dockerfile)

    def test_docker_context_contains_web_manifests_used_by_the_image(self):
        dockerfile=(ROOT/'Dockerfile').read_text()
        ignored=(ROOT/'.dockerignore').read_text().splitlines()
        self.assertIn('COPY web/package.json web/package-lock.json ./',dockerfile)
        for path in ('web/package.json','web/package-lock.json'):
            with self.subTest(path=path):
                self.assertTrue((ROOT/path).is_file())
                self.assertNotIn(path,ignored)


    def test_deployment_archive_sources_exist_in_the_repository(self):
        """A renamed script must fail here, not during a release."""
        workflow = (ROOT/'.github/workflows/release.yml').read_text()
        stage = workflow.split('deployment_stage', 1)[1]
        copied = set()
        for line in stage.splitlines():
            line = line.strip()
            if not line.startswith('cp '):
                continue
            for token in line.split()[1:]:
                token = token.strip('"\'')
                if not token or token.startswith('-') or '$' in token:
                    continue
                if '/' not in token and '.' not in token:
                    continue
                copied.add(token)
        self.assertGreaterEqual(len(copied), 10, copied)
        for path in sorted(copied):
            with self.subTest(path=path):
                self.assertTrue((ROOT/path).is_file(), f'release archive copies missing {path}')

    def test_current_version_has_release_notes_ready(self):
        """release.yml runs `test -s docs/releases/${TAG}.md` before publishing."""
        version = (ROOT/'VERSION').read_text().strip()
        notes = ROOT/'docs/releases'/f'v{version}.md'
        self.assertTrue(notes.is_file(), f'missing release notes for {version}')
        self.assertGreater(len(notes.read_text().strip()), 0)

    def test_archive_manifest_matches_documented_deployment_files(self):
        deployment = (ROOT/'docs/DEPLOYMENT.md').read_text()
        for name in ('compose.yml', 'compose.external.yml', 'compose.external-backup.yml',
                     '.env.example', 'VERSION'):
            with self.subTest(name=name):
                self.assertTrue((ROOT/name).is_file())
        self.assertIn('compose.external-backup.yml', deployment)
        self.assertIn('scripts/backup-healthcheck.sh', deployment)


if __name__ == '__main__':
    unittest.main()
