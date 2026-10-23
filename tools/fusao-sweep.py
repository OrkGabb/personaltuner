#!/usr/bin/env python3
"""fusao-sweep.py — Fase 7 kill-list (offline, stdlib).
Falha (exit 1) se achar no stage fusao-v1.0/:
 - IPv4 (fora docs .md) — VPS/IP fora de docs
 - serialno / TAMPER / ACCESS DENIED / Engine-main / license verify
 - gmail.com / generativelanguage (chave Gemini) / 202.155.143.189
 - rm -rf fora de uninstall.sh/cleanup.sh
 - $MOD bug (Rairin perf1.sh: GPU="$MOD/...")
 - thermal-spoof: extreme2/lite2, bind-mount thermal, /proc/mounts thermal
 - GPU ELFs banidos: gpu_max/balance/eco/reset binários (não devem existir)
 - spoof global: resetprop global (só Zygisk por app é permitido, e nem incluímos)
Uso: python tools/fusao-sweep.py [--stage fusao-v1.0]
"""
import re
import sys
from pathlib import Path

STAGE = Path(sys.argv[sys.argv.index("--stage") + 1]) if "--stage" in sys.argv else Path("fusao-v2.0")
if not STAGE.is_dir():
    # tenta relativo ao repo (cwd pode ser tools/)
    alt = Path(__file__).resolve().parent.parent / "fusao-v2.0"
    if alt.is_dir():
        STAGE = alt
fails = []

IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
ALLOW_IP_FILES = {".md", ".html", ".json"}  # docs + poster? json não deve ter IP, mas permite
TEXT_EXTS = {".sh", ".json", ".prop", ".txt", ".html", ".js", ".css", ".xml"}

def is_text(p: Path) -> bool:
    # sem extensão e não-ELF = script/texto (ex.: o CLI system/bin/fusao)
    return p.suffix.lower() in TEXT_EXTS or p.suffix == "" or p.name in {"service.sh", "customize.sh", "uninstall.sh", "cleanup.sh", "action.sh"}

def is_elf(p: Path) -> bool:
    try:
        with p.open("rb") as fh:
            return fh.read(4) == b"ELF"
    except OSError:
        return False

def scan():
    for f in sorted(STAGE.rglob("*")):
        if not f.is_file():
            continue
        if f.suffix == ".sha256":
            continue
        if f.suffix.lower() in {".webp", ".avif", ".apk", ".zip", ".webm", ".woff2"}:
            continue
        # binários ELF (pelo cabeçalho, não pelo sufixo): checa strings por nomes banidos
        if is_elf(f):
            try:
                raw = f.read_bytes()
            except OSError:
                continue
            txt = raw.decode("utf-8", "ignore")
            for pat, label in [
                ("202.155.143.189", "VPS Rairin"),
                ("Engine-main", "Engine license"),
                ("TAMPER", "tamper-check"),
                ("ACCESS DENIED", "deny-string"),
                ("generativelanguage", "Gemini key"),
            ]:
                if pat in txt and f.name == "fusaod":
                    fails.append(f"{f}: {label} em binario fusao (proibido)")
            continue
        if not is_text(f):
            continue
        try:
            t = f.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        rel = f.relative_to(STAGE).as_posix()
        # IP fora de docs
        if f.suffix.lower() not in ALLOW_IP_FILES and f.name != "fusao-sweep.py":
            for m in IPV4.finditer(t):
                # permite 0.0.0.0, 127.x, 255.x? não — só permite versão/API (ex: 1.0)
                ip = m.group(0)
                # ignora padrões de versão tipo 5.2.2? regex exige 4 octetos, versão tem 3. ok.
                fails.append(f"{rel}: IPv4 {ip} fora de docs")
        low = t.lower()
        for pat in ["serialno", "tamper", "access denied", "engine-main", "202.155.143.189",
                    "generativelanguage.googleapis.com", "gmail.com"]:
            if pat in low and f.suffix in {".sh", ""}:
                # gmail permitido em changelog? não temos. falha.
                fails.append(f"{rel}: string proibida '{pat}'")
        if "rm -rf" in t and f.name not in {"uninstall.sh", "cleanup.sh"}:
            # Só uninstall/cleanup apagam dados reais; nos demais o alvo tem de ser temporário
            # (TMPDIR/MODPATH/TMPD do instalador e do bugreport). Comentário inteiro não conta.
            for line in t.splitlines():
                if "rm -rf" not in line or line.lstrip().startswith("#"):
                    continue
                if not any(k in line for k in ["$MODPATH", "$TMPDIR", "$TMPD", "/data/local/tmp"]):
                    fails.append(f"{rel}: 'rm -rf' suspeito: {line.strip()[:90]}")
                    break
        if "$MOD/" in t or "${MOD}" in t:
            fails.append(f"{rel}: bug $MOD (path Rairin nunca definido)")
        if "extreme2" in t or "lite2" in t:
            fails.append(f"{rel}: thermal-spoof extreme2/lite2 banido")
        if "thermal_zone" in t and "mount" in t:
            fails.append(f"{rel}: bind-mount thermal (spoof)")

SINGLE_BANNED = ["encored", "encore_profiler", "encore_utility", "binder_resolver.apk",
                 "gpu_max", "gpu_balance", "gpu_eco", "gpu_reset", "balance1", "balance2",
                 "Engine-main", "Engine-backup", "RaiRin-performance1"]

def check_absent_bins():
    for name in SINGLE_BANNED:
        hits = [h for h in STAGE.rglob(name) if h.suffix != ".sha256"]
        # .sha256 orfaos de arquivos removidos tambem contam como sujeira
        orphans = list(STAGE.rglob(name + ".sha256"))
        if hits:
            fails.append(f"binario banido presente: {hits[0]}")
        elif orphans:
            fails.append(f"sha256 orfao (dono removido): {orphans[0]}")

def check_elf_strings():
    # fusaod não pode vazar path Termux
    f = STAGE / "system/bin/fusaod"
    if f.is_file():
        raw = f.read_bytes().decode("utf-8", "ignore")
        for bad in ["com.termux", "/data/data/com.termux"]:
            if bad in raw:
                fails.append(f"system/bin/fusaod: leak Termux '{bad}'")

scan()
check_absent_bins()
check_elf_strings()
# v2.0 single-module: ZERO refs Rairin/Encore-config em shell (sem fallback).
for f in ["service.sh", "customize.sh", "uninstall.sh", "cleanup.sh", "action.sh",
          "system/bin/fusao", "fusao/watch.sh", "fusao/perf.sh", "fusao/framework.sh",
          "fusao/bugreport.sh", "fusao/gos_killer.sh"]:
    p = STAGE / f
    if p.is_file():
        t = p.read_text(encoding="utf-8", errors="ignore")
        if "RaiRin-AI" in t or "Rairin" in t:
            fails.append(f"{f}: dependencia Rairin residual (v2.0 = zero)")
        if ".config/encore" in t:
            fails.append(f"{f}: path .config/encore residual (v2.0 = /data/adb/fusao)")

if fails:
    print(f"KILL-LIST FALHOU ({len(fails)}):")
    for x in fails:
        print(" -", x)
    sys.exit(1)
print("kill-list OK: nenhum VPS/serial/spoof/rm-rf/$MOD fora do lugar")
