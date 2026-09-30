"""One-worker release build under a Windows Job CPU limit; all new caches are temporary."""
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
CONFIG = json.loads((HERE / 'build-config.json').read_text(encoding='utf-8'))
WORKSPACE = Path(CONFIG['workspace']).resolve()
SCRATCH = Path(CONFIG['scratch']).resolve()
OUTPUT = Path(CONFIG['output']).resolve()
STATUS = HERE / 'status.json'
START = time.monotonic()
STATE = {'phase': 'starting', 'cpu_cap_percent': CONFIG['cpu_percent'], 'workers': 1,
         'heap_mb': CONFIG['heap_mb'], 'build_cache': False, 'configuration_cache': False}
JOB = None


def status(phase, **extra):
    STATE.update(phase=phase, elapsed_seconds=round(time.monotonic() - START), **extra)
    STATUS.write_text(json.dumps(STATE, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(STATE), flush=True)


def checked(path, base=WORKSPACE):
    path = Path(path).resolve()
    if path == base or not path.is_relative_to(base):
        raise RuntimeError('Refusing a filesystem operation outside the build directory: ' + str(path))
    return path


class BasicLimits(ctypes.Structure):
    _fields_ = [('process_time', ctypes.c_longlong), ('job_time', ctypes.c_longlong),
                ('flags', wintypes.DWORD), ('min_working_set', ctypes.c_size_t),
                ('max_working_set', ctypes.c_size_t), ('active_process_limit', wintypes.DWORD),
                ('affinity', ctypes.c_size_t), ('priority', wintypes.DWORD), ('scheduling', wintypes.DWORD)]


class IoCounters(ctypes.Structure):
    _fields_ = [(name, ctypes.c_ulonglong) for name in
                ['read_operations', 'write_operations', 'other_operations', 'read_bytes', 'write_bytes', 'other_bytes']]


class ExtendedLimits(ctypes.Structure):
    _fields_ = [('basic', BasicLimits), ('io', IoCounters), ('process_memory_limit', ctypes.c_size_t),
                ('job_memory_limit', ctypes.c_size_t), ('peak_process_memory', ctypes.c_size_t),
                ('peak_job_memory', ctypes.c_size_t)]


class CpuLimits(ctypes.Structure):
    _fields_ = [('flags', wintypes.DWORD), ('rate', wintypes.DWORD)]


class Accounting(ctypes.Structure):
    _fields_ = [('user_time', ctypes.c_longlong), ('kernel_time', ctypes.c_longlong),
                ('period_user_time', ctypes.c_longlong), ('period_kernel_time', ctypes.c_longlong),
                ('page_faults', wintypes.DWORD), ('total_processes', wintypes.DWORD),
                ('active_processes', wintypes.DWORD), ('terminated_processes', wintypes.DWORD)]


class MemoryStatus(ctypes.Structure):
    _fields_ = [('length', wintypes.DWORD), ('load', wintypes.DWORD)] + [
        (name, ctypes.c_ulonglong) for name in ['total_physical', 'free_physical', 'total_page',
                                              'free_page', 'total_virtual', 'free_virtual', 'extended_virtual']]


KERNEL = ctypes.WinDLL('kernel32', use_last_error=True)
KERNEL.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
KERNEL.CreateJobObjectW.restype = wintypes.HANDLE
KERNEL.GetCurrentProcess.restype = wintypes.HANDLE
KERNEL.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
KERNEL.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
KERNEL.QueryInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD, ctypes.c_void_p]


def limit_cpu():
    global JOB
    JOB = KERNEL.CreateJobObjectW(None, None)
    if not JOB:
        raise ctypes.WinError(ctypes.get_last_error())
    limits = ExtendedLimits()
    limits.basic.flags = 0x2000 | 0x20 | 0x10  # kill children on exit; below-normal priority; affinity
    limits.basic.affinity = 3  # at most two logical processors
    limits.basic.priority = 0x4000  # BELOW_NORMAL_PRIORITY_CLASS
    cpu = CpuLimits(1 | 4, CONFIG['cpu_percent'] * 100)  # ENABLE + HARD_CAP
    for kind, value in [(9, limits), (15, cpu)]:
        if not KERNEL.SetInformationJobObject(JOB, kind, ctypes.byref(value), ctypes.sizeof(value)):
            raise ctypes.WinError(ctypes.get_last_error())
    if not KERNEL.AssignProcessToJobObject(JOB, KERNEL.GetCurrentProcess()):
        raise ctypes.WinError(ctypes.get_last_error())
    status('resource_limits_applied', cpu_cap_enforced=True)


def guard():
    memory = MemoryStatus()
    memory.length = ctypes.sizeof(memory)
    KERNEL.GlobalMemoryStatusEx(ctypes.byref(memory))
    free_disk = shutil.disk_usage(SCRATCH if SCRATCH.exists() else WORKSPACE).free
    if memory.free_physical < 450 * 1024 ** 2:
        raise RuntimeError('Stopped: less than 450 MiB of physical memory remains.')
    if free_disk < 750 * 1024 ** 2:
        raise RuntimeError('Stopped: less than 750 MiB of disk space remains.')
    if time.monotonic() - START > CONFIG['deadline_minutes'] * 60:
        raise RuntimeError('Stopped at the configured time limit; see the retained build log.')
    return memory.free_physical, free_disk


def download(url, destination, algorithm, expected):
    destination = checked(destination, SCRATCH)
    digest = hashlib.new(algorithm)
    total, previous = 0, time.monotonic()
    request = urllib.request.Request(url, headers={'User-Agent': 'Gradle-release-build/1.0'})
    with urllib.request.urlopen(request, timeout=30) as response, destination.open('wb') as output:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            output.write(chunk)
            digest.update(chunk)
            total += len(chunk)
            if time.monotonic() - previous >= 20:
                free_memory, free_disk = guard()
                status('downloading_tools', downloaded_mb=round(total / 1024 ** 2),
                       free_ram_mb=round(free_memory / 1024 ** 2), free_disk_mb=round(free_disk / 1024 ** 2))
                previous = time.monotonic()
    if digest.hexdigest().lower() != expected.strip().lower():
        raise RuntimeError('Tool archive checksum did not match the official checksum.')


def unpack(archive, destination, strip_prefix=False):
    destination = checked(destination, SCRATCH)
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        prefix = source.namelist()[0].split('/')[0] if strip_prefix else None
        for member in source.infolist():
            name = member.filename
            if prefix:
                if not name.startswith(prefix + '/'):
                    continue
                name = name[len(prefix) + 1:]
            if not name:
                continue
            target = checked(destination / name, SCRATCH)
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(member) as reader, target.open('wb') as writer:
                    shutil.copyfileobj(reader, writer, 1024 * 1024)


def prepare():
    checked(SCRATCH)
    SCRATCH.mkdir(parents=True, exist_ok=True)
    (SCRATCH / 'temp').mkdir(exist_ok=True)
    gradle = SCRATCH / 'tools' / ('gradle-' + CONFIG['gradle_version'])
    if not (gradle / 'bin/gradle.bat').is_file():
        status('preparing_gradle')
        base = 'https://downloads.gradle.org/distributions/gradle-' + CONFIG['gradle_version'] + '-bin.zip'
        with urllib.request.urlopen(base + '.sha256', timeout=20) as response:
            checksum = response.read(200).decode().strip()
        archive = SCRATCH / 'gradle.zip'
        download(base, archive, 'sha256', checksum)
        unpack(archive, SCRATCH / 'tools')
        gradle = SCRATCH / 'tools' / ('gradle-' + CONFIG['gradle_version'])
        checked(archive, SCRATCH).unlink()
    sdk = SCRATCH / 'sdk'
    sdk.mkdir(exist_ok=True)
    existing = Path(CONFIG['existing_sdk'])
    for folder in ['build-tools', 'platform-tools']:
        link = sdk / folder
        if not link.exists():
            # A directory junction is removed explicitly without recursing into the installed SDK.
            command = "New-Item -ItemType Junction -Path '" + str(link) + "' -Target '" + str(existing / folder) + "' | Out-Null"
            subprocess.run(['powershell.exe', '-NoProfile', '-Command', command], check=True,
                           creationflags=subprocess.CREATE_NO_WINDOW)
    if not (sdk / 'licenses').exists():
        shutil.copytree(existing / 'licenses', sdk / 'licenses')
    if not (sdk / 'platforms/android-35/android.jar').is_file():
        status('preparing_android_platform')
        details = CONFIG['sdk_archive']
        archive = SCRATCH / 'platform.zip'
        download(details['url'], archive, details['checksum_type'], details['checksum'])
        unpack(archive, sdk / 'platforms/android-35', strip_prefix=True)
        checked(archive, SCRATCH).unlink()
    if not (sdk / 'platforms/android-35/android.jar').is_file():
        raise RuntimeError('Android platform archive did not contain android.jar.')
    return gradle, sdk


def build(gradle, sdk):
    env = os.environ.copy()
    env.update(JAVA_HOME=CONFIG['java_home'], ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
               GRADLE_USER_HOME=str(SCRATCH / 'gradle-home'), TEMP=str(SCRATCH / 'temp'), TMP=str(SCRATCH / 'temp'),
               JAVA_OPTS='-Xms32m -Xmx128m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC')
    env['PATH'] = str(Path(CONFIG['java_home']) / 'bin') + os.pathsep + env['PATH']
    jvm = ('-Xms128m -Xmx' + str(CONFIG['heap_mb']) + 'm -XX:MaxMetaspaceSize=512m '
           '-XX:ActiveProcessorCount=2 -XX:+UseSerialGC -XX:CICompilerCount=2 -Dfile.encoding=UTF-8 '
           '-Djava.io.tmpdir=' + str(SCRATCH / 'temp'))
    arguments = [str(gradle / 'bin/gradle.bat'), ':app:assembleRelease', '--no-daemon', '--no-parallel',
                 '--max-workers=1', '--no-build-cache', '--no-configuration-cache', '--console=plain',
                 '--project-cache-dir', str(SCRATCH / 'project-cache'), '-Dorg.gradle.jvmargs=' + jvm,
                 '-Dorg.gradle.internal.http.connectionTimeout=15000', '-Dorg.gradle.internal.http.socketTimeout=30000',
                 '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.incremental=false']
    attempt = len(list(HERE.glob('build-attempt-*.log'))) + 1
    log = HERE / ('build-attempt-' + str(attempt) + '.log')
    command = subprocess.list2cmdline(arguments)
    status('building_release', build_log=str(log), attempt=attempt)
    with log.open('wb') as stream:
        process = subprocess.Popen(command, shell=True, cwd=WORKSPACE, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        baseline = Accounting()
        KERNEL.QueryInformationJobObject(JOB, 1, ctypes.byref(baseline), ctypes.sizeof(baseline), None)
        prior_cpu = (baseline.user_time + baseline.kernel_time) / 10_000_000
        prior_time = time.monotonic()
        while process.poll() is None:
            time.sleep(15)
            free_memory, free_disk = guard()
            counters = Accounting()
            KERNEL.QueryInformationJobObject(JOB, 1, ctypes.byref(counters), ctypes.sizeof(counters), None)
            cpu = (counters.user_time + counters.kernel_time) / 10_000_000
            now = time.monotonic()
            cpu_percent = 100 * (cpu - prior_cpu) / max(now - prior_time, 0.1) / os.cpu_count()
            prior_cpu, prior_time = cpu, now
            limits = ExtendedLimits()
            KERNEL.QueryInformationJobObject(JOB, 9, ctypes.byref(limits), ctypes.sizeof(limits), None)
            with log.open('rb') as reader:
                reader.seek(max(0, log.stat().st_size - 4000))
                lines = reader.read().decode('utf-8', errors='replace').splitlines()
            last = next((line for line in reversed(lines) if line.strip()), '')[:350]
            status('building_release', current_task=last, measured_job_cpu_percent=round(cpu_percent, 1),
                   free_ram_mb=round(free_memory / 1024 ** 2), free_disk_mb=round(free_disk / 1024 ** 2),
                   peak_job_committed_mb=round(limits.peak_job_memory / 1024 ** 2))
    if process.returncode:
        raise RuntimeError('Release build failed with exit code ' + str(process.returncode) + '; log: ' + str(log))
    apks = list((WORKSPACE / 'app/build/outputs/apk/release').glob('*.apk'))
    if len(apks) != 1:
        raise RuntimeError('Expected one release APK; found ' + str(len(apks)))
    OUTPUT.mkdir(exist_ok=True)
    target = OUTPUT / 'NetflixProTV-28.9.2026-release.apk'
    shutil.copy2(apks[0], target)
    status('apk_created', apk=str(target), apk_bytes=target.stat().st_size,
           sha256=hashlib.sha256(target.read_bytes()).hexdigest())
    return target


def cleanup():
    status('cleaning_temporary_files')
    for folder in ['build-tools', 'platform-tools']:
        link = SCRATCH / 'sdk' / folder
        if link.exists():
            # Check the link location rather than resolving its external target.
            if not link.absolute().is_relative_to(SCRATCH):
                raise RuntimeError('Unexpected SDK link path')
            os.rmdir(link)
    for path in [SCRATCH, WORKSPACE / 'app/build', WORKSPACE / 'build', WORKSPACE / '.kotlin']:
        if path.exists():
            safe_path = checked(path)
            # Gradle dependency paths can exceed the legacy 260-character Windows limit.
            shutil.rmtree(Path('\\\\?\\' + str(safe_path)))


if __name__ == '__main__':
    try:
        limit_cpu()
        if '--cleanup-only' in sys.argv:
            cleanup()
            status('temporary_files_removed')
        else:
            gradle, sdk = prepare()
            apk = build(gradle, sdk)
            cleanup()
            status('complete', apk=str(apk), free_disk_mb=round(shutil.disk_usage(WORKSPACE).free / 1024 ** 2))
    except BaseException as error:
        status('failed', error=str(error), temporary_files_retained=True)
        raise
