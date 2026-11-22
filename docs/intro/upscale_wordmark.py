import numpy as np, sys
from PIL import Image
S=3
src=Image.open(sys.argv[1]).convert('RGBA'); W,H=src.size
a=np.asarray(src).astype(np.float32)/255.0
rgb=a[...,:3]; al=a[...,3:4]
pre=rgb*al                                  # premultiplied (= look on black)
lum=pre.max(axis=2)
# Letter regions: find rows/cols of crisp text (bright, low-saturation white; gold text) inside two boxes.
def box(mask_rows):
    pass
# Text masks: QUANTARI = near-white; SOLUTIONS = gold band below it. Detect by bounding boxes of strong pixels.
def tight(x0,y0,x1,y1,thr):
    sub=lum[y0:y1,x0:x1]; ys,xs=np.where(sub>thr)
    return x0+xs.min(),y0+ys.min(),x0+xs.max(),y0+ys.max()
qx0,qy0,qx1,qy1=tight(30,105,900,212,0.5)
sx0,sy0,sx1,sy1=tight(225,220,700,268,0.4)
print("QUANTARI",qx0,qy0,qx1,qy1,"SOLUTIONS",sx0,sy0,sx1,sy1)
pad=4
boxes=[(qx0-pad,qy0-pad,qx1+pad,qy1+pad),(sx0-pad,sy0-pad,sx1+pad,sy1+pad)]
up=lambda arr: np.asarray(Image.fromarray(np.clip(arr*255,0,255).astype(np.uint8)).resize((W*S,H*S),Image.LANCZOS)).astype(np.float32)/255.0
# Base: everything (swooshes etc.) enlarged smoothly, premultiplied.
base_pre=np.stack([up(pre[...,i]) for i in range(3)],-1); base_al=up(al[...,0])
out_pre=base_pre.copy(); out_al=base_al.copy()
def smooth(x,e0,e1):
    t=np.clip((x-e0)/(e1-e0),0,1); return t*t*(3-2*t)
from PIL import ImageFilter
def blur(arr,r):
    k=int(3*r)+1; x=np.arange(-k,k+1); g=np.exp(-x*x/(2*r*r)); g/=g.sum()
    p=np.pad(arr.astype(np.float32),k,mode='edge')
    p=np.apply_along_axis(lambda v: np.convolve(v,g,'valid'),1,p)
    return np.apply_along_axis(lambda v: np.convolve(v,g,'valid'),0,p)
lum_up=up(lum)
for (x0,y0,x1,y1),kind in zip(boxes,["white","gold"]):
    M=12*S
    X0,Y0,X1,Y1=max(0,x0*S-M),max(0,y0*S-M),min(W*S,(x1+1)*S+M),min(H*S,(y1+1)*S+M)
    m=blur(lum_up[Y0:Y1,X0:X1],1.6)           # smooth away compression wobble before re-sharpening
    peak=np.percentile(m[m>0.35],92)
    cov=smooth(m/peak,0.44,0.56)              # crisp, even anti-aliased edge
    # colour: white text pure white; gold keeps its own gentle gradient (enlarged colour, full brightness)
    reg_pre=base_pre[Y0:Y1,X0:X1]; reg_al=base_al[Y0:Y1,X0:X1]
    if kind=="white": col=np.ones_like(reg_pre)*min(1.0,peak)
    else:
        c=reg_pre/np.maximum(reg_pre.max(axis=2,keepdims=True),1e-4)
        g=np.median(rgb[y0:y1+1,x0:x1+1][lum[y0:y1+1,x0:x1+1]>0.45],axis=0); col=0.5*c*g.max()+0.5*g
    # background behind the letters: surrounding glow filled in (text masked out), so no dark ring
    textzone=smooth(m/peak,0.05,0.35)
    w=1-textzone
    fill_pre=np.stack([blur(reg_pre[...,i]*w,6*S)/np.maximum(blur(w,6*S),1e-3) for i in range(3)],-1)
    fill_al=blur(reg_al*w,6*S)/np.maximum(blur(w,6*S),1e-3)
    bg_pre=reg_pre*w[...,None]+fill_pre*textzone[...,None]; bg_al=reg_al*w+fill_al*textzone
    out_pre[Y0:Y1,X0:X1]=cov[...,None]*col+bg_pre*(1-cov[...,None])
    out_al[Y0:Y1,X0:X1]=cov+bg_al*(1-cov)
rgb_out=np.where(out_al[...,None]>1e-4,out_pre/np.maximum(out_al[...,None],1e-4),0)
img=np.dstack([np.clip(rgb_out,0,1),np.clip(out_al,0,1)])
Image.fromarray((img*255+0.5).astype(np.uint8),'RGBA').save(sys.argv[2],lossless=True,quality=100,method=6)
print("saved",W*S,H*S)
