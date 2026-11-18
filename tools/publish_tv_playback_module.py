#!/usr/bin/env python3
"""Sign and publish a reviewed TV module; never stores its private key in the APK."""
import argparse, base64, hashlib, json, os, pathlib, shutil, subprocess, tempfile
p=argparse.ArgumentParser();p.add_argument('dex',type=pathlib.Path);p.add_argument('--revision',type=int,required=True);p.add_argument('--embed',action='store_true');args=p.parse_args()
if args.revision<1:raise SystemExit('revision must be positive')
manifest=pathlib.Path('/root/projects/update-service/data/tv-playback-module.json')
if manifest.exists() and args.revision <= json.loads(manifest.read_text())['revision']:
 raise SystemExit('revision must increase over the current manifest')
key=pathlib.Path('/root/secrets/lvguo/tv-playback-rsa.pem')
dex=args.dex.read_bytes()
if not 0<len(dex)<=4*1024*1024:raise SystemExit('invalid module size')
sha=hashlib.sha256(dex).hexdigest();name='module-'+sha+'.dex';url='https://update.pengfei.ink/files/tv-playback/'+name
signed=f'lvguo-tv-playback-module-v1\n{args.revision}\n{sha}\n{url}'.encode()
sig=subprocess.run(['openssl','dgst','-sha256','-sign',str(key)],input=signed,capture_output=True,check=True).stdout
meta={'revision':args.revision,'abi':1,'url':url,'sha256':sha,'signature':base64.b64encode(sig).decode()}
encoded=(json.dumps(meta,ensure_ascii=False,indent=2)+'\n').encode()
folder=pathlib.Path('/var/www/update.pengfei.ink/files/tv-playback');folder.mkdir(parents=True,exist_ok=True)
tmp=folder/(name+'.tmp');tmp.write_bytes(dex);tmp.chmod(0o644);os.replace(tmp,folder/name)
manifest=pathlib.Path('/root/projects/update-service/data/tv-playback-module.json');tmp=manifest.with_suffix('.tmp');tmp.write_bytes(encoded);tmp.chmod(0o644);os.replace(tmp,manifest)
if args.embed:
 root=pathlib.Path(__file__).resolve().parents[1]/'assets/playback';root.mkdir(parents=True,exist_ok=True);(root/'module.dex').write_bytes(dex);(root/'module.json').write_bytes(encoded)
print(json.dumps({'revision':args.revision,'sha256':sha,'size':len(dex),'url':url}))
