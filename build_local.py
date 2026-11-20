from pathlib import Path
import subprocess,zipfile,sys,os,json,tempfile,re
root=Path(__file__).resolve().parent
version_code=int(os.environ.get('LVGUO_VERSION_CODE','2004'))
version_name=os.environ.get('LVGUO_VERSION_NAME','2.0.4')
application_id=os.environ.get('LVGUO_APPLICATION_ID','ink.pengfei.lvguo')
manifest_source=Path(os.environ.get('LVGUO_MANIFEST',str(root/'AndroidManifest.xml')))
if not manifest_source.is_absolute():manifest_source=root/manifest_source
output_names=[name for name in os.environ.get('LVGUO_OUTPUT_NAMES','lvguo.apk,绿果TV.apk,lvguo-tv.apk').split(',') if name]
config_file=root/'build.local.json'
config=json.loads(config_file.read_text(encoding='utf-8')) if config_file.exists() else {}
def setting(env,key,default=None):
 value=os.environ.get(env) or config.get(key) or default
 if not value:raise SystemExit('Missing build setting: '+env)
 return Path(value).expanduser()
b=setting('LVGUO_BUILD_DIR','build_dir',str(Path(tempfile.gettempdir())/'lvguo-tv-build'))
jdk=setting('JAVA_HOME','java_home')/'bin'
sdk=setting('ANDROID_SDK_ROOT','sdk_root',os.environ.get('ANDROID_HOME'))
bt=sdk/'build-tools'/'35.0.0'
platform=setting('LVGUO_ANDROID_JAR','android_jar',str(sdk/'platforms'/'android-35'/'android.jar'))
keystore=setting('LVGUO_KEYSTORE','keystore')
for folder in [b,b/'classes',b/'gen',b/'dexout']:folder.mkdir(parents=True,exist_ok=True)
zxing=root/'libs'/'zxing-core-3.5.3.jar'
sys.path.insert(0,str(root/'tools'))
from media3_dependencies import prepare
jars,library_resources,library_packages=prepare(root,root/'third_party'/'hgtv_media3_dependencies')
jars=[zxing]+jars
classpath=os.pathsep.join(str(p) for p in [platform]+jars)

def run(*args):subprocess.run([str(a) for a in args],check=True)
def executable(folder,name):
 native=folder/name
 return native if native.exists() else folder/(name+'.exe')
# Keep the standalone SDK build consistent with Gradle's manifest placeholders.
manifest_text=manifest_source.read_text(encoding='utf-8').replace('${appLabel}',os.environ.get('LVGUO_APP_LABEL','@string/app_name')).replace('${applicationId}',application_id).replace('${updateChannel}',os.environ.get('LVGUO_UPDATE_CHANNEL','stable'))
manifest_text=re.sub(r'android:versionCode="[^"]+"',f'android:versionCode="{version_code}"',manifest_text,count=1)
manifest_text=re.sub(r'android:versionName="[^"]+"',f'android:versionName="{version_name}"',manifest_text,count=1)
manifest=b/'AndroidManifest.xml'
manifest.write_text(manifest_text,encoding='utf-8')

run(executable(bt,'aapt2'),'compile','--dir',root/'res','-o',b/'res.zip')
compiled_libraries=[]
for n,folder in enumerate(library_resources):
 archive=b/('library-'+str(n)+'.zip')
 run(executable(bt,'aapt2'),'compile','--dir',folder,'-o',archive)
 compiled_libraries.extend(['-R',archive])
run(executable(bt,'aapt2'),'link','--auto-add-overlay','--custom-package','ink.pengfei.lvguo','--rename-manifest-package',application_id,'--extra-packages',':'.join(library_packages),'-o',b/'base.apk','--manifest',manifest,'-I',platform,'--java',b/'gen','-A',root/'assets','--min-sdk-version','23','--target-sdk-version','28','--version-code',str(version_code),'--version-name',version_name,*compiled_libraries,b/'res.zip')
for candidate in (b/'classes').rglob('*.class'):
 if (b/'classes').resolve() in candidate.resolve().parents:candidate.unlink()
run(executable(jdk,'javac'),'-J-Duser.language=en','-encoding','UTF-8','-source','8','-target','8','-nowarn','-cp',classpath,'-d',b/'classes',*list((root/'src').rglob('*.java')),*list((b/'gen').rglob('*.java')))
run(executable(jdk,'java'),'-cp',bt/'lib'/'d8.jar','com.android.tools.r8.D8','--min-api','23','--lib',platform,'--output',b/'dexout',*list((b/'classes').rglob('*.class')),*jars)
with zipfile.ZipFile(b/'base.apk') as src,zipfile.ZipFile(b/'unsigned.apk','w') as out:
 for entry in src.infolist():out.writestr(entry,src.read(entry.filename))
 out.write(root/'third_party'/'hgtv_media3_dependencies'/'artifacts.json','assets/third_party/media3-artifacts.json')
 out.write(root/'THIRD_PARTY_NOTICES.md','assets/third_party/NOTICES.md')
 for notice in ['LICENSE','NOTICE']:out.write(root/notice,'assets/'+notice)
 for notice in (root/'third_party').glob('*.txt'):out.write(notice,'assets/third_party/'+notice.name)
 for n,jar in enumerate(jars):
  with zipfile.ZipFile(jar) as dependency:
   for item in dependency.infolist():
    upper=item.filename.upper()
    if upper.startswith('META-INF/') and any(word in upper for word in ['LICENSE','NOTICE','COPYING']) and not item.is_dir():
     out.writestr('assets/third_party/dependency-'+str(n)+'/'+item.filename,dependency.read(item))
 for dex in (b/'dexout').glob('classes*.dex'):out.write(dex,dex.name,compress_type=zipfile.ZIP_DEFLATED)
run(executable(bt,'zipalign'),'-f','-P','16','4',b/'unsigned.apk',b/'aligned.apk')
run(executable(jdk,'java'),'-jar',bt/'lib'/'apksigner.jar','sign','--ks',keystore,'--ks-pass','env:LVGUO_STORE_PASSWORD','--key-pass','env:LVGUO_KEY_PASSWORD','--ks-key-alias',os.environ.get('LVGUO_KEY_ALIAS','androiddebugkey'),'--out',b/'lvguo.apk',b/'aligned.apk')
for name in output_names:
 import shutil
 (root/'out').mkdir(exist_ok=True)
 shutil.copyfile(b/'lvguo.apk',root/'out'/name)
print('Version',version_code,'APK ready:',', '.join(str(root/'out'/name) for name in output_names))
