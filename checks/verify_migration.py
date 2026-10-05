"""Exercise real schema SQL against SQLite; this is not an Android build."""
from pathlib import Path
import re, sqlite3
source=(Path(__file__).parents[1]/'app/src/main/java/com/alsekretary/app/data/SecretaryDatabase.kt').read_text()
def schema(db,method):
    body=source.split('private fun '+method+'(')[1].split('\n    private fun ')[0]
    for match in re.finditer(r'db\.execSQL\((?:"""(.*?)"""\.trimIndent\(\)|"([^"\n]+)")\)',body,re.S):
        db.execute(match.group(1) or match.group(2))
db=sqlite3.connect(':memory:')
schema(db,'createV1Tables');schema(db,'createV2Tables')
db.execute("INSERT INTO tasks VALUES('preserved','original',NULL,'INBOX',NULL,NULL,20,NULL,NULL,0,1,1)")
schema(db,'createV3Tables');schema(db,'createV3Tables')
assert db.execute('SELECT title FROM tasks WHERE id=?',('preserved',)).fetchone()==('original',)
db.execute("INSERT INTO habits VALUES('h','walk','PHYSICAL',3,0)")
db.execute("INSERT INTO habit_checks VALUES('h','2026-10-05')")
try:
    db.execute("INSERT INTO habit_checks VALUES('h','2026-10-05')")
    raise AssertionError('duplicate check allowed')
except sqlite3.IntegrityError: pass
for target in (0,8):
    try:
        db.execute("INSERT INTO habits VALUES(?, 'invalid','MENTAL',?,0)",(str(target),target))
        raise AssertionError('invalid weekly target allowed')
    except sqlite3.IntegrityError: pass
db.execute("INSERT INTO weekly_reviews VALUES('r','2026-10-05','wins','blocker','next',3)")
db.execute("INSERT OR REPLACE INTO weekly_reviews VALUES('r','2026-10-05','changed','blocker','next',4)")
assert db.execute('SELECT COUNT(*) FROM weekly_reviews').fetchone()[0]==1
assert db.execute('SELECT energy FROM weekly_reviews').fetchone()[0]==4
print('PASS: v2→v3 preserves tasks; migration reruns; daily uniqueness; target bounds; review update')
