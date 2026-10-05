from pathlib import Path
import re,sqlite3,xml.etree.ElementTree as ET
root=Path(__file__).parents[1]
source=(root/'app/src/main/java/com/alsekretary/app/data/SecretaryDatabase.kt').read_text()
def schema(db,method):
    body=source.split('private fun '+method+'(')[1].split('\n    private fun ')[0]
    for m in re.finditer(r'db\.execSQL\((?:"""(.*?)"""\.trimIndent\(\)|"([^"\n]+)")\)',body,re.S):db.execute(m.group(1) or m.group(2))
def task(db,id,generated=None,repeat=None):
    db.execute('INSERT INTO tasks(id,title,status,priority,created_at,updated_at,generated_from,repeat_days) VALUES(?,?,?,0,1,1,?,?)',(id,id,'PLANNED',generated,repeat))
def rejects(db,sql,args=()):
    try:db.execute(sql,args)
    except sqlite3.IntegrityError:return
    raise AssertionError('constraint was not enforced')
db=sqlite3.connect(':memory:')
for version in (1,2,3):schema(db,f'createV{version}Tables')
db.execute("INSERT INTO tasks VALUES('old','kept',NULL,'INBOX',NULL,NULL,30,NULL,NULL,0,1,1)")
schema(db,'createV4Tables')
assert db.execute("SELECT title,repeat_days,generated_from FROM tasks WHERE id='old'").fetchone()==('kept',None,None)
task(db,'next','old',7)
rejects(db,'INSERT INTO tasks(id,title,status,created_at,updated_at,generated_from) VALUES(?,?,?,?,?,?)',('duplicate','x','PLANNED',1,1,'old'))
rejects(db,"INSERT INTO task_dependencies VALUES('old','old')")
db.execute("INSERT INTO task_dependencies VALUES('next','old')")
rejects(db,"INSERT INTO task_dependencies VALUES('next','old')")
rejects(db,"INSERT INTO tasks(id,title,status,created_at,updated_at,repeat_days) VALUES('bad','bad','INBOX',1,1,0)")
db.execute("INSERT INTO milestones VALUES('m','project','Release',NULL,0)")
rejects(db,"UPDATE milestones SET completed=2")
db.commit()
# Failed occurrence creation must roll back parent completion as one operation.
try:
    with db:
        db.execute("UPDATE tasks SET status='DONE' WHERE id='old'")
        db.execute("INSERT INTO tasks(id,title,status,created_at,updated_at,generated_from) VALUES('conflict','x','PLANNED',1,1,'old')")
except sqlite3.IntegrityError:pass
assert db.execute("SELECT status FROM tasks WHERE id='old'").fetchone()==('INBOX',)
fresh=sqlite3.connect(':memory:')
for version in (1,2,3,4):schema(fresh,f'createV{version}Tables')
assert fresh.execute('SELECT COUNT(*) FROM tasks').fetchone()==(0,)
ET.parse(root/'app/src/main/AndroidManifest.xml')
print('PASS: upgrade preservation, fresh v4, occurrence uniqueness, dependency constraints, repeat bounds, milestone bounds, rollback, manifest XML')
