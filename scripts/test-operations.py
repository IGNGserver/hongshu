"""Isolated release/restore/deployment contracts. No real gh/docker writes."""
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
MOCK = r'''#!/usr/bin/env python3
import json,os,pathlib,sys,shutil
path=pathlib.Path(os.environ['MOCK_STATE']);s=json.loads(path.read_text());args=sys.argv[1:]
digest='sha256:'+'a'*64
def save(): path.write_text(json.dumps(s))
if pathlib.Path(sys.argv[0]).name=='docker':
 if args[:3]==['buildx','imagetools','inspect']:
  if s.get('outage'):print('connection refused',file=sys.stderr);sys.exit(1)
  ref=args[3];key=ref.split(':')[-1]
  value=digest if '@sha256:' in ref else s['tags'].get(key)
  if not value:print('manifest unknown',file=sys.stderr);sys.exit(1)
  print(json.dumps({'digest':value}))
 elif args[:2]==['buildx','build']:
  s['builds']+=1
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
        self.temp = tempfile.TemporaryDirectory(prefix='hongshu-ops-', dir=os.environ.get('HONGSHU_TEST_TMP', '/home/lvziw/tmp/opencode') if pathlib.Path('/home/lvziw/tmp/opencode').is_dir() else None)
        self.root = pathlib.Path(self.temp.name)
        tools = self.root / 'bin'
        tools.mkdir()
        for tool in ('gh', 'docker'):
            file = tools / tool
            file.write_text(MOCK)
            file.chmod(0o700)
        self.state = self.root / 'state.json'
        self.state.write_text(json.dumps(dict(tags={}, release=None, builds=0, promotions=[])))
        release = self.root / 'build/release'
        release.mkdir(parents=True)
        for name in ('hongshu-v0.1.0-alpha.99-android.apk',
                     'hongshu-v0.1.0-alpha.99-server-tools-linux-amd64.tar.gz',
                     'hongshu-v0.1.0-alpha.99-deployment.tar.gz'):
            (release / name).write_text('synthetic fixture')
        self.notes = self.root / 'release-notes.md'
        self.notes.write_text('''# Synthetic release\n\n## 本次更新\n\n- Synthetic fixture\n\n## 快捷下载\n\n- [APK](https://github.com/synthetic/hongshu/releases/download/v0.1.0-alpha.99/hongshu-v0.1.0-alpha.99-android.apk)\n''')
        self.env = dict(os.environ, PATH=str(tools)+':'+os.environ['PATH'], MOCK_STATE=str(self.state),
                        HONGSHU_BUILD_DIR=str(self.root/'build'), RUNNER_TEMP=str(self.root/'runner'),
                        RELEASE_NOTES_FILE=str(self.notes),
                        TAG='v0.1.0-alpha.99', VERSION='0.1.0-alpha.99', IS_PRERELEASE='true',
                        RELEASE_IMAGE='ghcr.io/synthetic/hongshu', RELEASE_OWNER='synthetic',
                        RELEASE_SHA='b'*40, GH_TOKEN='synthetic-token', GITHUB_SHA='b'*40,
                        GITHUB_RUN_ID='1', GITHUB_RUN_ATTEMPT='1')

    def tearDown(self):
        self.temp.cleanup()

    def run_publish(self):
        return subprocess.run(['bash', str(ROOT/'scripts/publish-release.sh')], env=self.env, capture_output=True).returncode

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


if __name__ == '__main__':
    unittest.main()
