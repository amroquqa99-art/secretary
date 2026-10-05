#!/usr/bin/env python3
"""Secretary social backend. Python standard library only; place behind HTTPS in production."""
import argparse,hashlib,hmac,json,os,re,secrets,sqlite3,time,uuid,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.parse import urlparse,parse_qs

METRICS={'LEARNING','RELIABILITY','GOAL','CONSISTENCY','PROJECTS','IMPROVEMENT'}
class ApiError(Exception):
 def __init__(self,code,message):self.code,self.message=code,message
class Store:
 def __init__(self,path):
  self.path=path;self.auth_attempts={};self.rate_lock=threading.Lock()
  with self.connect() as d:d.executescript('''
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,name TEXT NOT NULL,salt TEXT NOT NULL,password_hash TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY,user_id TEXT NOT NULL,expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS friendships(a TEXT NOT NULL,b TEXT NOT NULL,status TEXT NOT NULL,requested_by TEXT NOT NULL,PRIMARY KEY(a,b));
CREATE TABLE IF NOT EXISTS challenges(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,title TEXT NOT NULL,metric TEXT NOT NULL,visibility TEXT NOT NULL,deadline INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS members(challenge_id TEXT NOT NULL,user_id TEXT NOT NULL,target REAL NOT NULL,progress REAL NOT NULL DEFAULT 0,status TEXT NOT NULL,PRIMARY KEY(challenge_id,user_id));
CREATE TABLE IF NOT EXISTS shares(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL,visibility TEXT NOT NULL,group_id TEXT,created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS progress_events(id TEXT PRIMARY KEY,challenge_id TEXT NOT NULL,user_id TEXT NOT NULL,delta REAL NOT NULL,summary TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS requests(user_id TEXT NOT NULL,id TEXT NOT NULL,fingerprint TEXT NOT NULL,response TEXT NOT NULL,PRIMARY KEY(user_id,id));
''')
 def connect(self):
  d=sqlite3.connect(self.path,timeout=15);d.row_factory=sqlite3.Row;return d
 def auth(self,token,d):
  row=d.execute('SELECT user_id FROM sessions WHERE hash=? AND expires>?',(hashlib.sha256(token.encode()).hexdigest(),int(time.time()))).fetchone()
  if not row:raise ApiError(401,'انتهت الجلسة أو لم تسجل الدخول')
  return row['user_id']
 def friends(self,d,a,b):
  return d.execute('SELECT 1 FROM friendships WHERE a=? AND b=? AND status=?',(*sorted((a,b)),'ACCEPTED')).fetchone() is not None
 def member(self,d,c,u):return d.execute("SELECT * FROM members WHERE challenge_id=? AND user_id=? AND status='ACTIVE'",(c,u)).fetchone()
 def challenge(self,d,c):
  row=d.execute('SELECT * FROM challenges WHERE id=?',(c,)).fetchone()
  if not row:raise ApiError(404,'التحدي غير موجود')
  return row
 def visible_challenge(self,d,c,u):return c['owner_id']==u or c['visibility']=='PUBLIC' or (c['visibility']=='FRIENDS' and self.friends(d,u,c['owner_id'])) or self.member(d,c['id'],u)
 def text(self,b,k,maxlen=2000):
  value=b.get(k,'')
  if not isinstance(value,str) or not value.strip() or len(value)>maxlen:raise ApiError(422,'قيمة غير صالحة: '+k)
  return value.strip()
 def number(self,b,k,low,high):
  n=b.get(k)
  if isinstance(n,bool) or not isinstance(n,(int,float)) or not low<=n<=high:raise ApiError(422,'رقم غير صالح: '+k)
  return float(n)
 def session(self,d,u):
  token=secrets.token_urlsafe(32);d.execute('INSERT INTO sessions VALUES(?,?,?)',(hashlib.sha256(token.encode()).hexdigest(),u,int(time.time())+30*86400))
  row=d.execute('SELECT id,username,name FROM users WHERE id=?',(u,)).fetchone();return {'token':token,'user':dict(row)}
 def handle(self,method,path,body,token='',request_id='',peer='local'):
  parsed=urlparse(path);path=parsed.path
  if path=='/health':return {'status':'ok','schema':1}
  with self.connect() as d:
   if path in ('/register','/login') and method=='POST':
    with self.rate_lock:
     attempts=[t for t in self.auth_attempts.get(peer,[]) if t>time.time()-600]
     if len(attempts)>=20:raise ApiError(429,'محاولات كثيرة؛ حاول لاحقاً')
     attempts.append(time.time());self.auth_attempts[peer]=attempts
    username=self.text(body,'username',40).lower();password=self.text(body,'password',512)
    if not re.fullmatch(r'[a-z0-9_]{3,40}',username) or len(password)<8:raise ApiError(422,'اسم المستخدم 3–40 حرفاً لاتينياً أو رقماً أو _ وكلمة المرور 8 أحرف على الأقل')
    if path=='/register':
     name=self.text(body,'name',100);salt=secrets.token_hex(16);u=str(uuid.uuid4())
     hashed=hashlib.pbkdf2_hmac('sha256',password.encode(),bytes.fromhex(salt),600000).hex()
     try:d.execute('INSERT INTO users VALUES(?,?,?,?,?)',(u,username,name,salt,hashed))
     except sqlite3.IntegrityError:raise ApiError(409,'اسم المستخدم مستخدم')
    else:
     row=d.execute('SELECT * FROM users WHERE username=?',(username,)).fetchone()
     if row is None:raise ApiError(401,'بيانات الدخول غير صحيحة')
     hashed=hashlib.pbkdf2_hmac('sha256',password.encode(),bytes.fromhex(row['salt']),600000).hex()
     if not hmac.compare_digest(hashed,row['password_hash']):raise ApiError(401,'بيانات الدخول غير صحيحة')
     u=row['id']
    return self.session(d,u)
   u=self.auth(token,d)
   if method=='GET':
    if path=='/snapshot':return self.snapshot(d,u)
    if path=='/users':
     q=parse_qs(parsed.query).get('q',[''])[0].lower()
     return {'users':[dict(r) for r in d.execute('SELECT id,username,name FROM users WHERE username=? AND id!=?',(q,u))]}
    raise ApiError(404,'المسار غير موجود')
   # serialize retries and atomically retain response with state mutation
   if not request_id or len(request_id)>100:raise ApiError(422,'يلزم معرف ثابت للطلب')
   d.execute('BEGIN IMMEDIATE')
   fingerprint=hashlib.sha256((method+path+json.dumps(body,sort_keys=True)).encode()).hexdigest()
   prior=d.execute('SELECT * FROM requests WHERE user_id=? AND id=?',(u,request_id)).fetchone()
   if prior:
    if prior['fingerprint']!=fingerprint:raise ApiError(409,'معرف الطلب مستخدم لمحتوى مختلف')
    return json.loads(prior['response'])
   result=self.mutate(d,u,method,path,body,token)
   d.execute('INSERT INTO requests VALUES(?,?,?,?)',(u,request_id,fingerprint,json.dumps(result,ensure_ascii=False)))
   return result
 def mutate(self,d,u,method,path,b,token):
  if path=='/logout':
   d.execute('DELETE FROM sessions WHERE hash=?',(hashlib.sha256(token.encode()).hexdigest(),));return {'ok':True}
  if path=='/friends/request':
   other=self.text(b,'user_id',100)
   if other==u or not d.execute('SELECT 1 FROM users WHERE id=?',(other,)).fetchone():raise ApiError(422,'مستخدم غير صالح')
   a,z=sorted((u,other));row=d.execute('SELECT * FROM friendships WHERE a=? AND b=?',(a,z)).fetchone()
   if row and row['status'] in ('PENDING','ACCEPTED'):raise ApiError(409,'العلاقة موجودة بالفعل')
   d.execute('INSERT OR REPLACE INTO friendships VALUES(?,?,?,?)',(a,z,'PENDING',u));return {'ok':True}
  if path=='/friends/respond':
   other=self.text(b,'user_id',100);a,z=sorted((u,other));row=d.execute('SELECT * FROM friendships WHERE a=? AND b=?',(a,z)).fetchone()
   if not row or row['status']!='PENDING' or row['requested_by']==u:raise ApiError(403,'ليس طلباً موجهاً لك')
   accepted=b.get('accept') is True
   d.execute('UPDATE friendships SET status=? WHERE a=? AND b=?',('ACCEPTED' if accepted else 'REJECTED',a,z));return {'ok':True}
  if path=='/friends/remove':
   other=self.text(b,'user_id',100);d.execute('DELETE FROM friendships WHERE a=? AND b=?',tuple(sorted((u,other))));return {'ok':True}
  if path=='/challenges':
   title=self.text(b,'title',200);metric=self.text(b,'metric',30);visibility=self.text(b,'visibility',20)
   if metric not in METRICS or visibility not in ('PUBLIC','FRIENDS','PRIVATE'):raise ApiError(422,'إعداد تحدٍ غير صالح')
   deadline=int(self.number(b,'deadline',int(time.time()*1000)+1000,4102444800000));target=self.number(b,'target',0.0001,1000000);c=self.text(b,'id',100)
   d.execute('INSERT INTO challenges VALUES(?,?,?,?,?,?)',(c,u,title,metric,visibility,deadline));d.execute('INSERT INTO members VALUES(?,?,?,0,?)',(c,u,target,'ACTIVE'));return {'id':c}
  match=re.fullmatch(r'/challenges/([^/]+)/(join|invite|progress|leave)',path)
  if match:
   c,action=match.groups();ch=self.challenge(d,c)
   if action=='invite':
    other=self.text(b,'user_id',100)
    if ch['owner_id']!=u or not self.friends(d,u,other):raise ApiError(403,'الدعوة لصديق من مالك التحدي فقط')
    d.execute('INSERT OR IGNORE INTO members VALUES(?,?,1,0,?)',(c,other,'INVITED'));return {'ok':True}
   if action=='join':
    invitation=d.execute('SELECT * FROM members WHERE challenge_id=? AND user_id=?',(c,u)).fetchone()
    if not self.visible_challenge(d,ch,u) and not (invitation and invitation['status']=='INVITED'):raise ApiError(403,'تحدٍ خاص')
    target=self.number(b,'target',0.0001,1000000)
    if invitation and invitation['status']=='ACTIVE':raise ApiError(409,'أنت مشارك بالفعل')
    d.execute('INSERT OR REPLACE INTO members VALUES(?,?,?,0,?)',(c,u,target,'ACTIVE'));return {'ok':True}
   if action=='leave':
    if ch['owner_id']==u:raise ApiError(422,'لا يغادر مالك التحدي؛ يمكن إغلاقه بعد الموعد')
    d.execute('DELETE FROM members WHERE challenge_id=? AND user_id=?',(c,u));return {'ok':True}
   if not self.member(d,c,u):raise ApiError(403,'يلزم الانضمام أولاً')
   if ch['deadline']<int(time.time()*1000):raise ApiError(422,'انتهى التحدي')
   delta=self.number(b,'delta',0.0001,1000000);summary=self.text(b,'summary',1000);eid=self.text(b,'id',100)
   d.execute('INSERT INTO progress_events VALUES(?,?,?,?,?,?)',(eid,c,u,delta,summary,int(time.time()*1000)))
   d.execute('UPDATE members SET progress=progress+? WHERE challenge_id=? AND user_id=?',(delta,c,u));return {'ok':True}
  if path=='/shares':
   visibility=self.text(b,'visibility',20);group=b.get('group_id')
   if visibility not in ('PRIVATE','FRIENDS','PUBLIC','GROUP'):raise ApiError(422,'صلاحية مشاركة غير صالحة')
   if visibility=='GROUP' and not self.member(d,group,u):raise ApiError(403,'يلزم عضوية المجموعة')
   share=self.text(b,'id',100);title=self.text(b,'title',200);summary=self.text(b,'summary',2000)
   d.execute('INSERT INTO shares VALUES(?,?,?,?,?,?,?)',(share,u,title,summary,visibility,group if visibility=='GROUP' else None,int(time.time()*1000)));return {'id':share}
  match=re.fullmatch(r'/shares/([^/]+)',path)
  if match and method=='DELETE':
   row=d.execute('SELECT owner_id FROM shares WHERE id=?',(match[1],)).fetchone()
   if not row or row['owner_id']!=u:raise ApiError(403,'لا تملك المشاركة')
   d.execute('DELETE FROM shares WHERE id=?',(match[1],));return {'ok':True}
  raise ApiError(404,'المسار غير موجود')
 def snapshot(self,d,u):
  me=dict(d.execute('SELECT id,username,name FROM users WHERE id=?',(u,)).fetchone())
  friends=[]
  for r in d.execute('SELECT * FROM friendships WHERE a=? OR b=?',(u,u)):
   other=r['b'] if r['a']==u else r['a'];person=dict(d.execute('SELECT id,username,name FROM users WHERE id=?',(other,)).fetchone())
   friends.append({**person,'status':r['status'],'incoming':r['requested_by']!=u})
  challenges=[]
  for row in d.execute('SELECT * FROM challenges ORDER BY deadline DESC LIMIT 500'):
   invited=d.execute("SELECT 1 FROM members WHERE challenge_id=? AND user_id=? AND status='INVITED'",(row['id'],u)).fetchone()
   if not self.visible_challenge(d,row,u) and not invited:continue
   c=dict(row);c['invited']=bool(invited);mine=self.member(d,row['id'],u);c['joined']=bool(mine)
   members=[dict(r) for r in d.execute("SELECT m.user_id,u.name,m.target,m.progress FROM members m JOIN users u ON u.id=m.user_id WHERE challenge_id=? AND status='ACTIVE'",(row['id'],))] if self.visible_challenge(d,row,u) else []
   for m in members:m['score']=min(1,m['progress']/m['target'])*100
   c['leaderboard']=sorted(members,key=lambda m:(-m['score'],m['name']));challenges.append(c)
  shares=[]
  for row in d.execute('SELECT s.*,u.name FROM shares s JOIN users u ON s.owner_id=u.id ORDER BY s.created_at DESC LIMIT 500'):
   if row['owner_id']==u or row['visibility']=='PUBLIC' or (row['visibility']=='FRIENDS' and self.friends(d,u,row['owner_id'])) or (row['visibility']=='GROUP' and self.member(d,row['group_id'],u)):
    shares.append(dict(row))
  return {'me':me,'friends':friends,'challenges':challenges,'shares':shares,'server_time':int(time.time()*1000),'scoring':'self_reported_normalized_target'}

class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):self.respond('GET')
 def do_POST(self):self.respond('POST')
 def do_DELETE(self):self.respond('DELETE')
 def respond(self,method):
  try:
   size=int(self.headers.get('Content-Length','0'))
   if size<0 or size>65536:raise ApiError(413,'طلب أكبر من الحد')
   body=json.loads(self.rfile.read(size) or b'{}')
   if not isinstance(body,dict):raise ApiError(422,'يلزم JSON object')
   token=self.headers.get('Authorization','').removeprefix('Bearer ')
   result=self.server.store.handle(method,self.path,body,token,self.headers.get('Idempotency-Key',''),self.client_address[0]);status=200
  except ApiError as e:result={'error':e.message};status=e.code
  except (ValueError,sqlite3.IntegrityError):result={'error':'بيانات غير صالحة أو متعارضة'};status=422
  except Exception:result={'error':'تعذر تنفيذ الطلب'};status=500
  raw=json.dumps(result,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
def make_server(path,host='127.0.0.1',port=8080):
 server=ThreadingHTTPServer((host,port),Handler);server.store=Store(path);return server
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--db',default='secretary-social.sqlite');p.add_argument('--host',default='127.0.0.1');p.add_argument('--port',type=int,default=8080);a=p.parse_args()
 server=make_server(a.db,a.host,a.port);print('Secretary backend listening',server.server_address,flush=True);server.serve_forever()
