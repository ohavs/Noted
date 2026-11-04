import numpy as np, wave, subprocess, sys
SR=44100
def env(t, a=0.004, d=1.0): 
    e=np.exp(-t/d); e*=np.clip(t/a,0,1); return e
def note(f, dur, partials=((1,1,1.0),), a=0.004):
    t=np.arange(int(SR*dur))/SR; y=np.zeros_like(t)
    for ratio,amp,dec in partials:
        y+=amp*np.sin(2*np.pi*f*ratio*t)*env(t,a,dec)
    return y
def place(total, parts):
    y=np.zeros(int(SR*total))
    for start,sig in parts:
        i=int(SR*start); n=min(len(sig),len(y)-i); y[i:i+n]+=sig[:n]
    return y
def out(name,y):
    y=y/np.max(np.abs(y))*0.7
    fade=int(SR*0.05); y[-fade:]*=np.linspace(1,0,fade)
    w=wave.open(name+'.wav','wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((y*32767).astype('<i2').tobytes()); w.close()
    subprocess.run(['ffmpeg','-y','-loglevel','error','-i',name+'.wav','-c:a','libvorbis','-q:a','4',sys.argv[1]+'/'+name+'.ogg'],check=True)
bell=((1,1,1.2),(2.76,.45,.5),(5.4,.25,.25),(8.9,.12,.12))
out('tone_bell', place(2.2,[(0,note(880,2.2,bell))]))
glass=((1,1,.6),(3.0,.3,.25),(4.2,.15,.15))
out('tone_glass', place(1.6,[(0,note(1568,1.2,glass)),(0.12,note(2093,1.4,glass))]))
mar=((1,1,.22),(4,.35,.06),(10,.08,.02))
out('tone_marimba', place(1.0,[(0,note(659,.8,mar,a=.002)),(0.14,note(988,.8,mar,a=.002))]))
soft=((1,1,.9),(2,.25,.4))
out('tone_soft', place(2.0,[(0,note(523,1.6,soft,a=.02)),(0.18,note(659,1.6,soft,a=.02)),(0.36,note(784,1.6,soft,a=.02))]))
# drop: falling-rising pitch blip
t=np.arange(int(SR*.5))/SR; f=1200*np.exp(-t*9)+500; ph=2*np.pi*np.cumsum(f)/SR
d1=np.sin(ph)*env(t,.002,.09)
out('tone_drop', place(.7,[(0,d1),(0.16,d1*.6)]))
pl=((1,1,.12),(2,.5,.06),(3,.25,.04))
out('tone_pop', place(.6,[(0,note(1046,.4,pl,a=.001)),(0.09,note(1318,.4,pl,a=.001))]))
