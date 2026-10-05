from pathlib import Path
import re,sqlite3
source=(Path(__file__).parents[1]/'app/src/main/java/com/alsekretary/app/data/SecretaryDatabase.kt').read_text()
def schema(db,method):
 body=source.split('private fun '+method+'(')[1].split('\n    private fun ')[0]
 for m in re.finditer(r'db\.execSQL\((?:"""(.*?)"""\.trimIndent\(\)|"([^"\n]+)")\)',body,re.S):db.execute(m.group(1) or m.group(2))
d=sqlite3.connect(':memory:')
for version in (1,2,3,4):schema(d,f'createV{version}Tables')
d.execute("INSERT INTO goals VALUES('g','goal','MENTAL','specific','metric',10,2,'unit',NULL,'relevant','achievable','ACTIVE',123,123)")
d.execute("INSERT INTO tasks(id,title,status,created_at,updated_at) VALUES('task','kept','INBOX',123,123)")
schema(d,'createV5Tables')
assert d.execute("SELECT started_at FROM goals WHERE id='g'").fetchone()==(123,)
assert d.execute("SELECT title,parent_id FROM tasks WHERE id='task'").fetchone()==('kept',None)
d.execute("INSERT INTO daily_checks VALUES('2026-10-05',8,5,'wins','obstacles','next')")
d.execute("INSERT OR REPLACE INTO daily_checks VALUES('2026-10-05',7,4,'wins','obstacles','changed')")
assert d.execute('SELECT COUNT(*) FROM daily_checks').fetchone()==(1,)
d.execute("INSERT INTO social_outbox VALUES('id','POST','/shares','{}','PENDING',NULL,123)")
try:d.execute("INSERT INTO social_outbox VALUES('id','POST','/shares','{}','PENDING',NULL,123)");raise AssertionError('duplicate queue ID')
except sqlite3.IntegrityError:pass
print('PASS: v4→v5 preservation, goal baseline migration, daily upsert and outbox uniqueness')
