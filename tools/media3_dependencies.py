"""Pinned official Media3 dependencies for the standalone SDK build.

Gradle resolves these normally. The standalone builder also merges AAR resources
and generates their R classes; it must not silently drop AndroidX resources.
"""
from pathlib import Path
import io, json, re, urllib.request, zipfile
import xml.etree.ElementTree as ET

VERSION = '1.5.1'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

def prepare(root, cache):
    cache = Path(cache); cache.mkdir(parents=True, exist_ok=True)
    artifacts = {}; queue = [('androidx.media3', 'media3-exoplayer', VERSION, 'aar')]
    # Excluded annotation-only dependencies are not executed by Media3.
    excluded = {'jsr305', 'checker-qual', 'checker-compat-qual',
                'error_prone_annotations', 'j2objc-annotations', 'animal-sniffer-annotations'}
    def download(group, artifact, version, suffix):
        name = f'{artifact}-{version}.{suffix}'; target = cache / name
        base = 'https://dl.google.com/dl/android/maven2/' if group.startswith('androidx.') else 'https://repo.maven.apache.org/maven2/'
        url = base + group.replace('.', '/') + f'/{artifact}/{version}/{name}'
        if not target.exists():
            data = urllib.request.urlopen(url, timeout=40).read()
            target.write_bytes(data)
        return target
    def version_key(v): return tuple(int(n) for n in re.findall(r'\d+', v))
    while queue:
        group, artifact, version, kind = queue.pop(0)
        if artifact in excluded: continue
        version = version.strip('[]')
        previous = artifacts.get((group, artifact))
        if previous and version_key(previous[0]) >= version_key(version): continue
        pom = ET.fromstring(download(group, artifact, version, 'pom').read_bytes())
        packaging = pom.findtext('m:packaging', namespaces=NS) or kind
        if packaging == 'pom': continue
        if packaging == 'bundle': packaging = 'jar'
        file = download(group, artifact, version, packaging)
        artifacts[group, artifact] = (version, packaging, file)
        for dep in pom.findall('m:dependencies/m:dependency', NS):
            value = lambda key: dep.findtext('m:' + key, namespaces=NS)
            if value('scope') in ('test', 'provided') or value('optional') == 'true': continue
            dg, da, dv, dk = value('groupId'), value('artifactId'), value('version'), value('type') or 'jar'
            if da in excluded: continue
            if not dv or '${' in dv:
                # Guava inherits only these executable dependency versions.
                dv = {'failureaccess': '1.0.2', 'listenablefuture': '9999.0-empty-to-avoid-conflict-with-guava'}.get(da)
            if not dv: raise RuntimeError('Unresolved dependency: ' + str((dg, da)))
            queue.append((dg, da, dv, dk))
    jars, resources, packages, licenses = [], [], [], []
    for (group, artifact), (version, kind, file) in sorted(artifacts.items()):
        licenses.append({'artifact': f'{group}:{artifact}:{version}', 'repository': 'Google Maven' if group.startswith('androidx.') else 'Maven Central'})
        if kind == 'aar':
            folder = cache / f'{artifact}-{version}'; folder.mkdir(exist_ok=True)
            with zipfile.ZipFile(file) as archive:
                for name in archive.namelist():
                    if name == 'classes.jar' or name.startswith(('res/', 'libs/')):
                        archive.extract(name, folder)
                manifest = archive.read('AndroidManifest.xml').decode('utf-8')
                package = re.search(r'package="([^"]+)"', manifest)
                if package: packages.append(package[1])
            jars.append(folder / 'classes.jar')
            jars.extend((folder / 'libs').glob('*.jar'))
            if (folder / 'res').exists(): resources.append(folder / 'res')
        else: jars.append(file)
    (cache / 'artifacts.json').write_text(json.dumps(licenses, indent=2), encoding='utf-8')
    return jars, resources, packages
