import unittest,tempfile,threading,json,urllib.request,urllib.error,time,uuid
from pathlib import Path
from server import make_server
class SocialIntegration(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.server=make_server(str(Path(self.tmp.name)/'db.sqlite'),port=0)
  self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start();self.base='http://127.0.0.1:'+str(self.server.server_address[1])
  self.users=[]
  for i in range(3):self.users.append(self.call('/register',{'username':f'user{i}','password':'test-password-123','name':f'Person {i}'}))
 def tearDown(self):self.server.shutdown();self.server.server_close();self.tmp.cleanup()
 def call(self,path,body=None,user=None,method=None,key=None,code=200):
  headers={'Content-Type':'application/json'}
  if user:headers['Authorization']='Bearer '+user['token']
  if body is not None:headers['Idempotency-Key']=key or str(uuid.uuid4())
  r=urllib.request.Request(self.base+path,json.dumps(body).encode() if body is not None else None,headers,method=method or ('POST' if body is not None else 'GET'))
  try:
   with urllib.request.urlopen(r,timeout=5) as response:actual=response.status;value=json.load(response)
  except urllib.error.HTTPError as e:actual=e.code;value=json.load(e)
  self.assertEqual(code,actual,value);return value
 def friends(self):
  a,b=self.users[:2];self.call('/friends/request',{'user_id':b['user']['id']},a);self.call('/friends/respond',{'user_id':a['user']['id'],'accept':True},b)
 def test_auth_and_privacy_revocation(self):
  a,b,c=self.users;self.call('/snapshot',code=401);self.call('/login',{'username':'user0','password':'wrong-password'},code=401)
  self.friends()
  for visibility in ('PRIVATE','FRIENDS','PUBLIC'):
   self.call('/shares',{'id':visibility,'title':visibility,'summary':'explicit summary only','visibility':visibility},a)
  self.assertEqual({'PRIVATE','FRIENDS','PUBLIC'},{s['id'] for s in self.call('/snapshot',user=a)['shares']})
  self.assertEqual({'FRIENDS','PUBLIC'},{s['id'] for s in self.call('/snapshot',user=b)['shares']})
  self.assertEqual({'PUBLIC'},{s['id'] for s in self.call('/snapshot',user=c)['shares']})
  self.call('/shares/PUBLIC',{},c,method='DELETE',code=403)
  self.call('/friends/remove',{'user_id':b['user']['id']},a)
  self.assertEqual({'PUBLIC'},{s['id'] for s in self.call('/snapshot',user=b)['shares']})
  self.call('/shares/PUBLIC',{},a,method='DELETE');self.assertEqual([],self.call('/snapshot',user=c)['shares'])
 def test_challenge_idempotency_and_group_permissions(self):
  a,b,c=self.users;self.friends();cid='challenge-one'
  self.call('/challenges',{'id':cid,'title':'Learning','metric':'LEARNING','target':10,'visibility':'PRIVATE','deadline':int(time.time()*1000)+86400000},a)
  self.assertEqual([],self.call('/snapshot',user=c)['challenges'])
  self.call(f'/challenges/{cid}/join',{'target':20},c,code=403)
  self.call(f'/challenges/{cid}/invite',{'user_id':b['user']['id']},a)
  self.call(f'/challenges/{cid}/join',{'target':20},b)
  body={'id':'progress1','delta':5,'summary':'self-reported study'}
  self.call(f'/challenges/{cid}/progress',body,a,key='retry-key');self.call(f'/challenges/{cid}/progress',body,a,key='retry-key')
  self.call(f'/challenges/{cid}/progress',{**body,'delta':8},a,key='retry-key',code=409)
  board=self.call('/snapshot',user=a)['challenges'][0]['leaderboard'];self.assertEqual(50,board[0]['score']);self.assertEqual(5,board[0]['progress'])
  self.call('/shares',{'id':'group-share','title':'group','summary':'members only','visibility':'GROUP','group_id':cid},a)
  self.assertEqual(1,len(self.call('/snapshot',user=b)['shares']));self.assertEqual([],self.call('/snapshot',user=c)['shares'])
  self.call(f'/challenges/{cid}/leave',{},b);self.assertEqual([],self.call('/snapshot',user=b)['shares'])
 def test_only_recipient_can_accept_and_isolation(self):
  a,b,c=self.users;self.call('/friends/request',{'user_id':b['user']['id']},a)
  self.call('/friends/respond',{'user_id':b['user']['id'],'accept':True},a,code=403)
  self.call('/friends/respond',{'user_id':a['user']['id'],'accept':True},c,code=403)
  self.call('/friends/respond',{'user_id':a['user']['id'],'accept':True},b)
  self.assertEqual('ACCEPTED',self.call('/snapshot',user=a)['friends'][0]['status'])
  self.call('/logout',{},a);self.call('/snapshot',user=a,code=401)
if __name__=='__main__':unittest.main(verbosity=2)
