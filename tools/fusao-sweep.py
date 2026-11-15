#!/usr/bin/env python3
"""fusao-sweep.py — Fase 7 kill-list (offline, stdlib).
Falha (exit 1) se achar no stage fusao-v2.0/:
 - IPv4 (fora docs .md) — VPS/IP fora de docs, em texto E nas strings de ELF/dex
 - serialno / TAMPER / ACCESS DENIED / Engine-main / license verify
 - gmail.com / generativelanguage (chave Gemini) / 202.155.143.189
 - rm recursivo (qualquer grafia: -rf, -fr, -Rf, -r -f, --recursive) fora de uninstall.sh/cleanup.sh,
   e rm recursivo com ".." mesmo em alvo permitido
 - chmod 444 em qualquer script (trava de nó sysfs: nodes-m54.md §10)
 - ferramenta de rede em script (curl/wget/nc/ncat/telnet, /dev/tcp)
 - $MOD bug (Rairin perf1.sh: GPU="$MOD/...")
 - thermal-spoof: extreme2/lite2, bind-mount thermal, /proc/mounts thermal
 - GPU ELFs banidos: gpu_max/balance/eco/reset binários (não devem existir)
 - spoof global: resetprop global (só Zygisk por app é permitido, e nem incluímos)
 - REGRA Nº 1: qualquer caminho com o componente "ems" (/sys/kernel/ems, .../ems/..., emstune), em texto
   (inclusive montado por variável: K=/sys/kernel; cat $K/ems/x) e nas strings de TODO ELF/dex
 - marca/dependência residual (Rairin / Encore) em QUALQUER arquivo de texto do stage

Limite honesto: análise estática de texto. Um caminho montado caractere a caractere em tempo de execução
(e=em; cat /sys/kernel/${e}s) não é visível aqui; a regra do componente "ems" cobre as formas escritas.
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
fails: list[str] = []

IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
ALLOW_IP_FILES = {".md", ".html", ".json"}  # docs + poster? json não deve ter IP, mas permite
TEXT_EXTS = {".sh", ".json", ".prop", ".txt", ".html", ".js", ".css", ".xml", ".md"}
SKIP_EXTS = {".webp", ".avif", ".apk", ".zip", ".webm", ".woff2"}
ROOT_SCRIPTS = {"service.sh", "customize.sh", "uninstall.sh", "cleanup.sh", "action.sh"}

# Componente de caminho "ems" (Regra nº 1). Delimitado à esquerda por início/barra/aspas/espaço/=/$/{/} e à
# direita por barra ou fim de token: pega /sys/kernel/ems, $K/ems/x, "ems/multi_load", .../ems" e emstune,
# sem pegar palavras (items, systems, problems).
EMS_TEXT = re.compile(r"""(?:^|[/\s"'=${}])ems(?:/|[\s"';)]|$)|emstune""", re.M)
EMS_BIN = ("sys/kernel/ems", "/ems/", "emstune")

# rm com flags: captura o bloco de flags para decidir se é recursivo em qualquer grafia.
RM_CMD = re.compile(r"(?:^|[\s;&|(`])rm((?:\s+-{1,2}[A-Za-z-]+)+)")
CHMOD_444 = re.compile(r"(?:^|[\s;&|(`])chmod\s+(?:-[A-Za-z]+\s+)*0*444\b")
NET_TOOL = re.compile(r"(?:^|[\s;&|(`$])(curl|wget|ncat|telnet|nc)(?=\s|$)|/dev/tcp/")
PRINTABLE = re.compile(rb"[\x20-\x7e]{4,}")

BANNED_BIN_STRINGS = [
    ("202.155.143.189", "VPS Rairin"),
    ("Engine-main", "Engine license"),
    ("TAMPER", "tamper-check"),
    ("ACCESS DENIED", "deny-string"),
    ("generativelanguage", "Gemini key"),
    ("com.termux", "leak Termux"),
]
BRAND_RESIDUE = [
    ("RaiRin-AI", "dependencia Rairin residual (v2.0 = zero)"),
    ("Rairin", "dependencia Rairin residual (v2.0 = zero)"),
    (".config/encore", "path .config/encore residual (v2.0 = /data/adb/fusao)"),
    ("Rem01Gaming", "referencia residual de copyright/marca Encore"),
    ("Encore Tweaks", "referencia residual de copyright/marca Encore"),
]


def is_text(p: Path) -> bool:
    # sem extensão e não-ELF = script/texto (ex.: o CLI system/bin/fusao)
    return p.suffix.lower() in TEXT_EXTS or p.suffix == "" or p.name in ROOT_SCRIPTS


def is_elf(p: Path) -> bool:
    try:
        with p.open("rb") as fh:
            return fh.read(4) == b"\x7fELF"
    except OSError:
        return False


def is_script(p: Path) -> bool:
    return p.suffix == ".sh" or p.suffix == "" or p.name in ROOT_SCRIPTS


def code_lines(text: str) -> list[str]:
    """Linhas que executam: fora as de comentário puro (o '#!' também sai: não é comando)."""
    return [ln for ln in text.splitlines() if not ln.lstrip().startswith("#")]


def rm_is_recursive(flags: str) -> bool:
    for tok in flags.split():
        if tok == "--recursive":
            return True
        if tok.startswith("--"):
            continue
        if "r" in tok[1:] or "R" in tok[1:]:
            return True
    return False


def check_binary(f: Path, rel: str) -> None:
    """ELF e dex: mesmas proibições, sobre as strings imprimíveis (antes só o fusaod era conferido)."""
    try:
        raw = f.read_bytes()
    except OSError:
        return
    strings = [m.group(0).decode("latin-1") for m in PRINTABLE.finditer(raw)]
    blob = "\n".join(strings)
    for pat, label in BANNED_BIN_STRINGS:
        if pat in blob:
            fails.append(f"{rel}: {label} em binario (proibido)")
    for pat in EMS_BIN:
        if pat in blob:
            fails.append(f"{rel}: caminho '{pat}' em binario (Regra n.1: /sys/kernel/ems causa reboot)")
            break
    for s in strings:
        m = IPV4.search(s)
        if m:
            fails.append(f"{rel}: IPv4 {m.group(0)} em binario")
            break


def check_text(f: Path, rel: str) -> None:
    try:
        t = f.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return
    code = code_lines(t)
    # IP fora de docs
    if f.suffix.lower() not in ALLOW_IP_FILES and f.name != "fusao-sweep.py":
        for m in IPV4.finditer(t):
            fails.append(f"{rel}: IPv4 {m.group(0)} fora de docs")
    low = t.lower()
    if is_script(f):
        for pat in ["serialno", "tamper", "access denied", "engine-main", "202.155.143.189",
                    "generativelanguage.googleapis.com", "gmail.com"]:
            if pat in low:
                fails.append(f"{rel}: string proibida '{pat}'")
    # rm recursivo: só uninstall/cleanup apagam dados reais; nos demais o alvo tem de ser temporário
    # (TMPDIR/MODPATH/TMPD do instalador e do bugreport) ou os diretórios legados do módulo encore durante a
    # migração em customize/service. Qualquer grafia das flags conta, e ".." nunca é aceito.
    if f.name not in {"uninstall.sh", "cleanup.sh"}:
        for line in code:
            m = RM_CMD.search(line)
            if not m or not rm_is_recursive(m.group(1)):
                continue
            ok = False
            if ".." not in line:
                if any(k in line for k in ["$MODPATH", "$TMPDIR", "$TMPD", "/data/local/tmp"]):
                    ok = True
                elif f.name in {"customize.sh", "service.sh"} and any(
                    k in line for k in ["$LEGACY_MOD", "$LEGACY_UPD", "/data/adb/modules/encore",
                                        "/data/adb/modules_update/encore"]
                ) and "/data/adb/fusao" not in line:
                    ok = True
            if not ok:
                fails.append(f"{rel}: 'rm' recursivo suspeito: {line.strip()[:90]}")
                break
    if is_script(f):
        for line in code:
            if CHMOD_444.search(line):
                fails.append(f"{rel}: chmod 444 (trava de no proibida, nodes-m54.md secao 10): {line.strip()[:80]}")
                break
        for line in code:
            m = NET_TOOL.search(line)
            if m:
                fails.append(f"{rel}: ferramenta de rede em script: {line.strip()[:80]}")
                break
    if "$MOD/" in t or "${MOD}" in t:
        fails.append(f"{rel}: bug $MOD (path Rairin nunca definido)")
    if "extreme2" in t or "lite2" in t:
        fails.append(f"{rel}: thermal-spoof extreme2/lite2 banido")
    if "thermal_zone" in t and "mount" in t:
        fails.append(f"{rel}: bind-mount thermal (spoof)")
    # Regra nº 1: vale também em comentário (um caminho de EMS documentado num script vira copiar-e-colar)
    if "sys/kernel/ems" in t or EMS_TEXT.search(t):
        fails.append(f"{rel}: acesso proibido a /sys/kernel/ems (Regra n.1: causa reboot)")
    # v2.0 single-module: ZERO refs Rairin/Encore em qualquer texto do stage (sem lista fixa de arquivos)
    for pat, label in BRAND_RESIDUE:
        if pat in t:
            fails.append(f"{rel}: {label}")
            break


def scan() -> None:
    for f in sorted(STAGE.rglob("*")):
        if not f.is_file():
            continue
        if f.suffix == ".sha256":
            continue
        if f.suffix.lower() in SKIP_EXTS:
            continue
        rel = f.relative_to(STAGE).as_posix()
        # binários pelo cabeçalho (ELF) ou pelo sufixo (dex): strings conferidas, nunca pulados
        if is_elf(f) or f.suffix.lower() == ".dex":
            check_binary(f, rel)
            continue
        if not is_text(f):
            continue
        check_text(f, rel)


SINGLE_BANNED = ["encored", "encore_profiler", "encore_utility", "binder_resolver.apk",
                 "gpu_max", "gpu_balance", "gpu_eco", "gpu_reset", "balance1", "balance2",
                 "Engine-main", "Engine-backup", "RaiRin-performance1"]


def check_absent_bins() -> None:
    for name in SINGLE_BANNED:
        hits = [h for h in STAGE.rglob(name) if h.suffix != ".sha256"]
        # .sha256 orfaos de arquivos removidos tambem contam como sujeira
        orphans = list(STAGE.rglob(name + ".sha256"))
        if hits:
            fails.append(f"binario banido presente: {hits[0]}")
        elif orphans:
            fails.append(f"sha256 orfao (dono removido): {orphans[0]}")


scan()
check_absent_bins()

if fails:
    print(f"KILL-LIST FALHOU ({len(fails)}):")
    for x in fails:
        print(" -", x)
    sys.exit(1)
print("kill-list OK: nenhum VPS/serial/spoof/rm-rf/chmod-444/rede/EMS/$MOD fora do lugar")
