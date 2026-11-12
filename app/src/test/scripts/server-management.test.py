"""Isolated shell integration tests. No real installation, package manager, sudo or network is used."""
import base64, fcntl, hashlib, io, os, pathlib, shlex, subprocess, tarfile, tempfile, unittest, shutil

BASE=pathlib.Path(__file__).resolve().parents[2]
SOURCE=(BASE/'main/assets/server-management.sh').read_text()
RUNNER=(BASE/'main/assets/server-job-runner.sh').read_text()

class ManagementTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='dexcode-server-test-')
        self.root=pathlib.Path(self.temp.name)
        self.home=self.root/"user's home"
        self.prefix=self.home/'.local'
        self.bin=self.root/'fake-bin'
        self.bin.mkdir()
        (self.prefix/'bin').mkdir(parents=True)
        self.env={**os.environ,'DEXCODE_TEST_ROOT':str(self.home),'PATH':str(self.bin)+':'+os.environ['PATH'],
                  'PORT':'18977','DEXCODE_TEST_ARCHIVE':str(self.root/'asset.tar.gz'),'DEXCODE_TEST_FETCHES':str(self.root/'fetches')}
        self.install('4.19.1')
        self.launcher=self.prefix/'bin/code-server'
        self.launcher.symlink_to(self.prefix/'lib/code-server-4.19.1/bin/code-server')
        self.program('id','[ "${1:-}" = -u ] && printf "1001\\n" || exit 1')
        self.program('ss','exit 0')
        self.program('uname','case "$1" in -s)echo Linux;;-m)echo x86_64;;*)exit 1;;esac')
        self.program('dpkg-query','exit 1')
        self.program('rpm','exit 1')
        self.program('curl','''out=""; url=""
while [ "$#" -gt 0 ]; do
 case "$1" in -o)out=$2;shift 2;;http*)url=$1;shift;;*)shift;;esac
done
case "$url" in
 http://127.0.0.1:*)printf 000;exit 7;;
 https://github.com/coder/code-server/releases/download/*)printf x >> "$DEXCODE_TEST_FETCHES";cp "$DEXCODE_TEST_ARCHIVE" "$out";;
 *)exit 9;;esac''')
        self.script=SOURCE.replace('$HOME','${DEXCODE_TEST_ROOT}').replace('/.dockerenv',str(self.root/'container-marker'))
    def tearDown(self): self.temp.cleanup()
    def program(self,name,body):
        p=self.bin/name;p.write_text('#!/bin/sh\n'+body+'\n');p.chmod(0o755)
    def install(self,version):
        root=self.prefix/('lib/code-server-'+version)
        (root/'bin').mkdir(parents=True);(root/'lib').mkdir()
        (root/'package.json').write_text('{"name":"code-server"}')
        p=root/'bin/code-server';p.write_text('#!/bin/sh\nprintf "%s test\\n" '+shlex.quote(version)+'\n');p.chmod(0o755)
        p=root/'lib/node';p.write_text('#!/bin/sh\nexit 0\n');p.chmod(0o755)
    def call(self,op,**fields):
        return subprocess.run(['sh','-s'],input=self.script,text=True,capture_output=True,env={**self.env,'OP':op,**fields},timeout=25)
    def info(self):
        r=self.call('inspect');self.assertEqual(r.returncode,0,r.stderr)
        return {line.split('\t',2)[1]:base64.b64decode(line.split('\t',2)[2]).decode() for line in r.stdout.splitlines() if line.startswith('DEXCODE_SERVER\t')}
    def update(self,version='4.20.0',**extra):
        s=self.info()
        fields={'VERSION':version,'EXPECTED_LAUNCHER':s['launcher'],'EXPECTED_REAL':s['realPath'],
                'EXPECTED_METHOD':s['method'],'EXPECTED_VERSION':s['version'],'ALLOW_SUDO':'0',
                'EXPECTED_ASSET':'code-server-'+version+'-linux-amd64.tar.gz',
                'EXPECTED_SHA256':hashlib.sha256((self.root/'asset.tar.gz').read_bytes()).hexdigest() if (self.root/'asset.tar.gz').exists() else ''}
        return self.call('update',**{**fields,**extra})
    def archive(self,version='4.20.0'):
        prefix='code-server-'+version+'-linux-amd64/'
        with tarfile.open(self.root/'asset.tar.gz','w:gz') as tar:
            for name,data,mode in [('bin/code-server',('#!/bin/sh\necho '+version+'\n').encode(),0o755),('lib/node',b'#!/bin/sh\nexit 0\n',0o755),('package.json',b'{"name":"code-server"}',0o644)]:
                info=tarfile.TarInfo(prefix+name);info.size=len(data);info.mode=mode;tar.addfile(info,io.BytesIO(data))
    def test_detects_offline_existing_standalone_with_quoted_path(self):
        s=self.info();self.assertEqual(s['method'],'standalone');self.assertEqual(s['version'],'4.19.1')
        self.assertEqual(s['targetVerified'],'1');self.assertEqual(s['needsAdmin'],'0');self.assertIn("user's home",s['launcher'])
    def test_unknown_wrapper_and_container_are_not_replaced(self):
        (self.root/'container-marker').touch()
        self.assertEqual(self.info()['method'],'unknown')
        r=self.update();self.assertNotEqual(r.returncode,0);self.assertFalse((self.root/'fetches').exists())
    def test_stale_snapshot_is_rejected_before_download(self):
        r=self.update(EXPECTED_REAL='/different/path');self.assertNotEqual(r.returncode,0);self.assertFalse((self.root/'fetches').exists())
    def test_malformed_version_never_executes_shell_data(self):
        marker=self.root/'injected'
        r=self.update(version='1.2.3;touch '+str(marker));self.assertNotEqual(r.returncode,0);self.assertFalse(marker.exists())
    def test_bad_checksum_does_not_change_launcher(self):
        self.archive();old=self.launcher.resolve()
        r=self.update(EXPECTED_SHA256='0'*64);self.assertNotEqual(r.returncode,0);self.assertIn('checksum',r.stderr)
        self.assertEqual(self.launcher.resolve(),old);self.assertFalse((self.prefix/'lib/code-server-4.20.0').exists())
    def test_verified_update_and_local_rollback_keep_both_versions(self):
        self.archive();r=self.update();self.assertEqual(r.returncode,0,r.stderr)
        self.assertIn('code-server-4.20.0',str(self.launcher.resolve()))
        self.assertTrue((self.prefix/'lib/code-server-4.19.1').exists())
        count=(self.root/'fetches').read_text()
        r=self.update('4.19.1');self.assertEqual(r.returncode,0,r.stderr)
        self.assertIn('code-server-4.19.1',str(self.launcher.resolve()))
        self.assertEqual((self.root/'fetches').read_text(),count)
        self.assertEqual((self.home/'.cache/dexcode/code-server-18977.previous-version').read_text().strip(),'4.20.0')
    def test_installation_lock_blocks_concurrent_update(self):
        self.archive();cache=self.home/'.cache/dexcode';cache.mkdir(parents=True,exist_ok=True)
        data=('standalone:'+str(self.prefix)).encode();key=subprocess.run(['cksum'],input=data,capture_output=True).stdout.split()[0].decode()
        with open(cache/('server-update-'+key+'.lock'),'w') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            r=self.update();self.assertNotEqual(r.returncode,0);self.assertIn('already running',r.stderr)
        r=self.update();self.assertEqual(r.returncode,0,r.stderr)
    def test_account_operation_lock_blocks_update_and_restart(self):
        self.archive();cache=self.home/'.cache/dexcode';cache.mkdir(parents=True,exist_ok=True)
        with open(cache/'server-operation.lock','w') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            r=self.update();self.assertNotEqual(r.returncode,0);self.assertIn('server operation',r.stderr)
            r=self.call('restart');self.assertNotEqual(r.returncode,0);self.assertIn('server operation',r.stderr)
            self.assertFalse((self.root/'fetches').exists())
        r=self.update();self.assertEqual(r.returncode,0,r.stderr)
    def test_admin_is_not_used_without_explicit_choice(self):
        self.program('dpkg-query','printf "code-server: %s\\n" "$2"')
        marker=self.root/'sudo-called'
        self.program('sudo','printf x > '+shlex.quote(str(marker))+';exit 1')
        self.archive();r=self.update();self.assertNotEqual(r.returncode,0);self.assertIn('authorization',r.stderr);self.assertFalse(marker.exists())
    def test_pid_file_without_identity_or_matching_listener_cannot_stop_process(self):
        process=subprocess.Popen(['sleep','20'])
        try:
            cache=self.home/'.cache/dexcode';cache.mkdir(parents=True,exist_ok=True)
            (cache/'code-server-18977.pid').write_text(str(process.pid))
            self.assertEqual(self.info()['canRestart'],'0')
            r=self.call('restart',EXPECTED_PID=str(process.pid),EXPECTED_PROCESS_ID='invalid')
            self.assertNotEqual(r.returncode,0);self.assertIsNone(process.poll())
        finally:process.terminate();process.wait()
    def test_restart_requires_current_identity_and_only_stops_recorded_process(self):
        process=subprocess.Popen(['sleep','20'])
        try:
            cache=self.home/'.cache/dexcode';cache.mkdir(parents=True,exist_ok=True)
            pid_file=cache/'code-server-18977.pid';pid_file.write_text(str(process.pid))
            stat=pathlib.Path('/proc/'+str(process.pid)+'/stat').read_text().rsplit(') ',1)[1].split()
            identity=pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip()+':'+stat[19]
            pathlib.Path(str(pid_file)+'.identity').write_text(identity)
            self.assertEqual(self.info()['canRestart'],'1')
            r=self.call('restart',EXPECTED_PID=str(process.pid),EXPECTED_PROCESS_ID=identity+'0')
            self.assertNotEqual(r.returncode,0);self.assertIsNone(process.poll())
            r=self.call('restart',EXPECTED_PID=str(process.pid),EXPECTED_PROCESS_ID=identity)
            self.assertEqual(r.returncode,0,r.stderr);process.wait(timeout=2)
            self.assertFalse(pid_file.exists())
        finally:
            if process.poll() is None:process.terminate();process.wait()
    def test_replaced_running_node_is_identified_and_requires_restart(self):
        node=self.prefix/'lib/code-server-4.19.1/lib/node'
        shutil.copy2('/bin/sleep',node)
        process=subprocess.Popen([str(node),'20'])
        try:
            node.unlink();shutil.copy2('/bin/sleep',node)
            self.program('ss','echo '+shlex.quote('users:((node,pid='+str(process.pid)+',fd=9))'))
            info=self.info()
            self.assertTrue(info['runningExecutable'].endswith(' (deleted)'))
            self.assertEqual(info['method'],'standalone');self.assertEqual(info['restartRequired'],'1')
            self.assertEqual(info['targetVerified'],'1');self.assertEqual(info['canRestart'],'0')
            r=self.update();self.assertNotEqual(r.returncode,0);self.assertIn('Restart',r.stderr)
            self.assertFalse((self.root/'fetches').exists());self.assertIsNone(process.poll())
        finally:process.terminate();process.wait()
    def test_runner_bounds_logs_and_records_terminal_result(self):
        job=self.home/'.cache/dexcode/server-jobs/12345678-1234-1234-1234-123456789abc';job.mkdir(parents=True)
        (job/'payload.sh').write_text("head -c 100000 /dev/zero | tr '\\000' x\nprintf '\\nRESULT\\n'\nexit 7\n")
        r=subprocess.run(['sh','-s'],input='JOB_DIR='+shlex.quote(str(job))+'\n'+RUNNER,text=True,capture_output=True,timeout=10)
        self.assertEqual(r.returncode,0,r.stderr);self.assertEqual((job/'state').read_text().strip(),'failed')
        self.assertEqual((job/'exit').read_text().strip(),'7');self.assertLessEqual((job/'output').stat().st_size,65536)
        self.assertTrue((job/'output').read_text().endswith('RESULT\n'))

if __name__=='__main__':unittest.main(verbosity=2)
