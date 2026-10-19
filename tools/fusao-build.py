#!/usr/bin/env python3
"""fusao-build.py — packaging v1.0 (offline, stdlib, forward slashes).
- stage: fusao-v2.0/ (ativo; v1.0/v0.1 estão em _historico/)
- regenera .sha256 por arquivo (formato Encore: arquivo com hex puro)
- zip default: _releases/<versao>-M54.zip, forward slashes, inclui .sha256
- verifica self-check (customize.sh cobre todos os arquivos novos)
Uso: python tools/fusao-build.py [--stage STAGE --out ZIP]
Ex:  python tools/fusao-build.py --stage fusao-v2.0 --out _releases/fusao-v2.9.5-M54.zip
"""
import hashlib
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAGE = ROOT / "fusao-v2.0"
OUT = ROOT / "_releases" / "fusao-v2.0-M54.zip"
for i, a in enumerate(sys.argv):
    if a == "--stage" and i + 1 < len(sys.argv):
        STAGE = Path(sys.argv[i + 1])
    if a == "--out" and i + 1 < len(sys.argv):
        OUT = Path(sys.argv[i + 1])

SKIP_DIRS = {"__pycache__"}
files = []
for f in sorted(STAGE.rglob("*")):
    if not f.is_file():
        continue
    if f.suffix == ".sha256":
        continue
    if any(part in SKIP_DIRS for part in f.parts):
        continue
    files.append(f)

# 1) sha256 irmãos
for f in files:
    h = hashlib.sha256(f.read_bytes()).hexdigest()
    # newline LF explícito: no Windows o default gravaria CRLF e o
    # sha256sum -c no aparelho falharia (\r entra no hash via $(cat)).
    (f.parent / (f.name + ".sha256")).write_text(h + "\n", newline="\n")
print(f"sha: {len(files)} arquivos")

# 2) zip forward slashes
OUT.parent.mkdir(parents=True, exist_ok=True)
allfiles = sorted([f for f in STAGE.rglob("*") if f.is_file()])
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for f in allfiles:
        arc = f.relative_to(STAGE).as_posix()
        z.write(f, arc)
print(f"zip: {OUT.name} ({OUT.stat().st_size} bytes, {len(allfiles)} entries)")

# 3) self-check: customize.sh deve citar cada executável novo
cust = (STAGE / "customize.sh").read_text(encoding="utf-8", errors="ignore")
need = ["system/bin/fusaod", "system/bin/fusao", "fusao/watch.sh", "fusao/framework.sh",
        "fusao/perf.sh", "fusao/bugreport.sh", "fusao/tune.sh", "fusao/dnd.sh",
        "fusao/display.sh", "fusao/observer.sh",
        "fusao/observer.dex", "fusao/gamelist.txt", "system/bin/fas-rs", "fusao/fas.sh"]
missing = [n for n in need if n not in cust]
if missing:
    print("SELF-CHECK FALHOU, customize.sh não cita:", missing)
    sys.exit(1)
print(f"self-check OK: customize cobre os {len(need)}")
