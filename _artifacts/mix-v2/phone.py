import subprocess,xml.etree.ElementTree as ET,re,sys,pathlib
adb=[r'C:/Users/mw/Documents/projects/apps/reset/.android-sdk/platform-tools/adb.exe','-s','0016615BM001167']
def run(*args): return subprocess.check_output(adb+list(args))
def dump():
    run('shell','uiautomator','dump','/sdcard/aurora-mix-ui.xml')
    return ET.fromstring(run('shell','cat','/sdcard/aurora-mix-ui.xml'))
def show():
    root=dump()
    for n in root.iter('node'):
        t=n.get('text') or n.get('content-desc')
        if t: print(t,n.get('bounds'))
def tap(label):
    root=dump()
    matches=[n for n in root.iter('node') if (n.get('text')==label or n.get('content-desc')==label)]
    if not matches: raise RuntimeError('Not on screen: '+label)
    n=matches[0];a=list(map(int,re.findall(r'\d+',n.get('bounds'))));run('shell','input','tap',str((a[0]+a[2])//2),str((a[1]+a[3])//2))
if __name__=='__main__':
    if sys.argv[1]=='show':show()
    elif sys.argv[1]=='tap':tap(sys.argv[2])
    elif sys.argv[1]=='shot':pathlib.Path(sys.argv[2]).write_bytes(run('exec-out','screencap','-p'))
