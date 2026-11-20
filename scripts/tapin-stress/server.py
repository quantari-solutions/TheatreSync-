import http.server, json, time, re, threading, sys, os
# Simulated TheatreSync host. Controls: /ctl?cmd=pause|play|seek&ms=..|slow&kbps=..|fast|offset&ms=..|stallstate
L=threading.Lock()
G={}; REMOVED=set(); LEFT=[]
S={'playing':True,'pos0':10_000,'at':time.monotonic(),'kbps':0,'offset':0,'drop':False}
def host_ms(): return int(time.monotonic()*1000)+S['offset']
def pos():
    with L:
        return S['pos0']+(int((time.monotonic()-S['at'])*1000) if S['playing'] else 0)
DATA=open(sys.argv[1] if len(sys.argv)>1 else 'sound.mp4','rb').read()
MIME='audio/mp4'
class H(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*a): pass
    def send(self, code, ctype, body, extra=None, rate=0):
        self.send_response(code); self.send_header('Content-Type',ctype); self.send_header('Content-Length',str(len(body)))
        self.send_header('Cache-Control','no-store')
        for k,v in (extra or {}).items(): self.send_header(k,v)
        self.end_headers()
        if not rate: self.wfile.write(body); return
        step=max(1024,rate*1024//8//20)
        try:
            for i in range(0,len(body),step):
                self.wfile.write(body[i:i+step]); time.sleep(0.05)
                if S['drop']: S['drop']=False; self.close_connection=True; return
        except Exception: pass
    def do_POST(self):
        n=int(self.headers.get('Content-Length') or 0)
        if n: self.rfile.read(n)
        p,_,q=self.path.partition('?'); qs=dict(x.split('=',1) for x in q.split('&') if '=' in x)
        if p=='/leave': G.pop(qs.get('id',''),None); LEFT.append(time.time()); self.send(204,'text/plain',b'')
        else: self.send(404,'text/plain',b'no')
    def do_GET(self):
        p,_,q=self.path.partition('?'); qs=dict(x.split('=',1) for x in q.split('&') if '=' in x)
        if p=='/listen': self.send(200,'text/html',open(os.path.join(os.path.dirname(__file__),'build','listen.html'),'rb').read())
        elif p=='/clock':
            t=host_ms(); self.send(200,'application/json',json.dumps({"payload":{"seq":int(qs['seq']),"t0":int(qs['t0']),"t1":t,"t2":host_ms()}}).encode())
        elif p=='/state':
            t=host_ms()
            self.send(200,'application/json',json.dumps({"payload":{"state":"playing" if S['playing'] else "paused","mediaPositionMs":pos(),"hostTimeMs":t,"playbackRate":1.0,"mediaTitle":"Stress test"}}).encode())
        elif p=='/truth': self.send(200,'application/json',json.dumps({"pos":pos(),"playing":S['playing']}).encode())
        elif p=='/guest':
            gid=qs.get('id','')
            if gid in REMOVED: self.send(200,'application/json',b'{"muted":true,"volume":0,"removed":true}'); return
            from urllib.parse import unquote
            g=G.setdefault(gid,{'muted':False,'volume':100,'joined':time.time()})
            g.update(name=unquote(qs.get('name','')),drift=qs.get('drift'),status=qs.get('status'),hidden=qs.get('hidden'),seen=time.time())
            self.send(200,'application/json',json.dumps({"muted":g['muted'],"volume":g['volume'],"removed":False}).encode())
        elif p=='/guests':
            now=time.time()
            for k in [k for k,g in G.items() if now-g['seen']>4]: del G[k]
            self.send(200,'application/json',json.dumps(G).encode())
        elif p=='/ctl':
            c=qs['cmd']
            with L:
                cur=S['pos0']+(int((time.monotonic()-S['at'])*1000) if S['playing'] else 0)
                if c=='pause': S.update(playing=False,pos0=cur,at=time.monotonic())
                elif c=='play': S.update(playing=True,pos0=cur,at=time.monotonic())
                elif c=='seek': S.update(pos0=int(qs['ms']),at=time.monotonic())
                elif c=='slow': S['kbps']=int(qs['kbps'])
                elif c=='fast': S['kbps']=0
                elif c=='offset': S['offset']=int(qs['ms'])
                elif c=='drop': S['drop']=True
                elif c=='mute': [g.update(muted=qs.get('on')=='1') for g in G.values()]
                elif c=='volume': [g.update(volume=int(qs['v'])) for g in G.values()]
                elif c=='remove': REMOVED.update(G.keys()); G.clear()
            self.send(200,'text/plain',b'ok')
        elif p=='/audio':
            size=len(DATA); r=self.headers.get('Range')
            if r:
                m=re.match(r'bytes=(\d*)-(\d*)',r); a=int(m.group(1) or 0); b=int(m.group(2)) if m.group(2) else size-1
                b=min(b,size-1)
                self.send(206,MIME,DATA[a:b+1],{'Content-Range':f'bytes {a}-{b}/{size}','Accept-Ranges':'bytes'},rate=S['kbps'])
            else: self.send(200,MIME,DATA,{'Accept-Ranges':'bytes'},rate=S['kbps'])
        else: self.send(404,'text/plain',b'no')
class Srv(http.server.ThreadingHTTPServer):
    def handle_error(self, request, client_address): pass  # browsers drop ranges they no longer need
Srv(('127.0.0.1',8766),H).serve_forever()
