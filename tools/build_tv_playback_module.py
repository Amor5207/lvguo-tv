#!/usr/bin/env python3
"""Build the replaceable TV playback dex using the cached Kotlin compiler and Android SDK."""
import glob,os,pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
cache=pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
def one(pattern):
 matches=glob.glob(str(cache/pattern))
 if not matches: raise SystemExit('Missing Kotlin build dependency: '+pattern)
 return matches[0]
stdlib=one('org.jetbrains.kotlin/kotlin-stdlib/2.0.21/*/*.jar')
compiler=[one(p) for p in ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20/*/*.jar','org.jetbrains.kotlin/kotlin-script-runtime/2.1.20/*/*.jar','org.jetbrains.intellij.deps/trove4j/1.0.20200330/*/*.jar','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar','org.jetbrains/annotations/13.0/*/*.jar','org.jetbrains.kotlin/kotlin-stdlib/2.1.20/*/*.jar']]
sdk=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT',str(pathlib.Path.home()/'Android/Sdk')));android=sdk/'platforms/android-35/android.jar';d8=sdk/'build-tools/35.0.0/lib/d8.jar'
host=pathlib.Path(os.environ.get('LVGUO_BUILD_DIR','/tmp/lvguo-tv-build'))/'classes'
if not (host/'ink/pengfei/lvguo/net/hgsign/HgPlayback.class').is_file():raise SystemExit('Build the TV Java classes before building the module')
with tempfile.TemporaryDirectory(prefix='lvguo-tv-module-') as temp:
 temp=pathlib.Path(temp);classes=temp/'classes';classes.mkdir();out=temp/'dex';out.mkdir()
 subprocess.run(['java','-Xmx512m','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-language-version','2.0','-api-version','2.0','-no-stdlib','-no-reflect','-jvm-target','1.8','-classpath',os.pathsep.join(map(str,[stdlib,android,host])),'-d',str(classes),*map(str,(root/'tv-playback-module/src').rglob('*.kt'))],check=True)
 jar=temp/'module.jar';subprocess.run(['jar','cf',str(jar),'-C',str(classes),'.'],check=True)
 subprocess.run(['java','-Xmx512m','-cp',str(d8),'com.android.tools.r8.D8','--min-api','23','--lib',str(android),'--output',str(out),str(jar),stdlib],check=True)
 target=pathlib.Path(os.environ.get('LVGUO_MODULE_OUTPUT','/tmp/lvguo-playback-module.dex'));target.write_bytes((out/'classes.dex').read_bytes());print('Module:',target)
