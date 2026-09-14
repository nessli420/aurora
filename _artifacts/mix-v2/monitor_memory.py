import subprocess,time,json,re,pathlib
adb=[r'C:/Users/mw/Documents/projects/apps/reset/.android-sdk/platform-tools/adb.exe','-s','0016615BM001167']
samples=[]
for i in range(16):
    output=subprocess.check_output(adb+['shell','dumpsys','meminfo','com.aurora.music'],text=True)
    m=re.search(r'TOTAL PSS:\s*(\d+)',output)
    samples.append({'seconds':i*4,'pss_kb':int(m.group(1)) if m else 0})
    pathlib.Path('_artifacts/mix-v2/separation-memory.json').write_text(json.dumps(samples,indent=2))
    time.sleep(4)
print('Peak PSS KB',max(s['pss_kb'] for s in samples))
