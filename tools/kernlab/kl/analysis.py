"""Carrega um run puxado do aparelho, invalida trials que não mediram o que diziam medir e
roda a estatística.

Um trial só conta se o NÓ SEGUROU o valor pedido do início ao fim da medição. Escrever e
reler na hora não basta: outro escritor (framework Samsung, GOS, módulo) pode mudar o nó
no meio, e aí o trial mediu outra coisa. Por isso há duas leituras — antes e depois da
carga — e as duas têm de bater com o valor pedido. Para o EMS a prova é o cur_set, e o
conjunto ativo (modo/nível) também tem de ser o do nó; senão o valor existe mas não age.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from . import emstune
from . import knobs as K
from . import parse
from . import stats
from . import workloads as W
from .plan import FREE, SCREEN, Experiment


@dataclass
class Trial:
    index: int
    block: int
    arm: str
    metrics: dict = field(default_factory=dict)
    problems: list = field(default_factory=list)
    warnings: list = field(default_factory=list)
    env: dict = field(default_factory=dict)

    @property
    def valid(self) -> bool:
        return not self.problems


@dataclass
class RunData:
    run_dir: Path
    manifest: dict
    exp: Experiment
    trials: list
    restore_ok: bool
    restore_problems: list
    status: str
    env_begin: dict


def _curset(path: Path) -> dict | None:
    if not path.is_file():
        return None
    return emstune.parse(path.read_text(encoding="utf-8", errors="replace"))


def check_knobs(intended: dict[str, str], tdir: Path) -> list[str]:
    problems: list[str] = []
    pre = {r[0]: (r[1] if len(r) > 1 else "") for r in parse.read_tsv(tdir / "knobs_pre.tsv")}
    post = {r[0]: (r[1] if len(r) > 1 else "") for r in parse.read_tsv(tdir / "knobs_post.tsv")}
    cs_pre = _curset(tdir / "curset_pre.txt")
    cs_post = _curset(tdir / "curset_post.txt")
    seen_sets = {}
    for p in tdir.glob("curset_seen_m*l*.txt"):
        m = re.match(r"curset_seen_m(\d+)l(\d+)\.txt", p.name)
        if m:
            seen_sets[(int(m.group(1)), int(m.group(2)))] = _curset(p)
    for kid, want in intended.items():
        if want == FREE:
            continue
        kn = K.get(kid)
        if kn.access == "ems":
            # o cur_set só mostra o conjunto ATIVO; cada observação em que o conjunto do nó estava
            # ativo (antes, depois, ou visto durante a carga pelo kl_sample) tem de bater
            mode, level = K.ems_mode_level(kn)
            obs = [(tag, cs) for tag, cs in (("antes", cs_pre), ("depois", cs_post), ("durante", seen_sets.get((mode, level))))
                   if cs is not None and (cs["mode"], cs["level"]) == (mode, level)]
            if not obs and (mode, level) == (0, 0):
                problems.append(f"{kid}: conjunto m0l0 nunca observado ativo (cur_set ausente?)")
            for tag, cs in obs:
                got = emstune.value_of(cs, kn.ems_field)
                if got is None or str(got) != str(want):
                    problems.append(f"{kid}: {tag} = {got}, pedido {want}")
            # sem observação de um conjunto não-base: ele não ativou neste trial e o valor não agiu
            continue
        if kid not in pre:
            problems.append(f"{kid}: sem leitura antes da medição")
            continue
        got_pre = kn.normalize(pre[kid])
        if got_pre != want:
            problems.append(f"{kid}: não segurou (lido {got_pre!r}, pedido {want!r})")
        got_post = kn.normalize(post.get(kid))
        if got_post != got_pre:
            problems.append(f"{kid}: derivou durante a medição ({got_pre!r} -> {got_post!r})")
    return problems


def _ems_mode(header: str | None) -> str | None:
    m = re.search(r"\[mode=(\d+)", header or "")
    return m.group(1) if m else None


def check_env(begin: dict, tdir: Path) -> list[str]:
    """O regime do sistema tem de ser o mesmo do início do run: tela (wakefulness) e modo do
    emstune. Na fila de 2026-10-18 a tela apagou e 33 trials rodaram em Dozing/power-scenario,
    com a Samsung trocando o top-app uclamp.min de 1.00 para 0.00 por conta própria."""
    problems = []
    for tag in ("env_pre", "env_post"):
        env = parse.read_kv(tdir / tag)
        if not env:
            problems.append(f"{tag} ausente")
            continue
        if env.get("wakefulness") != begin.get("wakefulness"):
            problems.append(f"tela mudou ({tag}: {env.get('wakefulness')} != {begin.get('wakefulness')})")
        if _ems_mode(env.get("emstune")) != _ems_mode(begin.get("emstune")):
            problems.append(f"modo emstune mudou ({tag}: {env.get('emstune')})")
    return problems


def load_run(run_dir: Path) -> RunData:
    run_dir = Path(run_dir)
    manifest = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
    exp = Experiment(**manifest["experiment"])
    wl = W.make(exp.workload)
    et_path = run_dir / "energy_table.txt"
    ctx = {"energy_table": parse.parse_energy_table(et_path.read_text(encoding="utf-8")) if et_path.is_file() else {}}
    env_begin = parse.read_kv(run_dir / "env_begin")
    trials: list[Trial] = []
    for i, (block, arm) in enumerate(manifest["schedule"], start=1):
        tdir = run_dir / f"t{i}"
        t = Trial(i, int(block), arm)
        trials.append(t)
        if not tdir.is_dir():
            t.problems.append("trial não executado")
            continue
        meta = parse.read_kv(tdir / "meta")
        t.env = parse.read_kv(tdir / "env_pre")
        if not (tdir / "END").exists():
            t.problems.append("trial incompleto")
        if meta.get("rc") not in ("0", None) or "rc" not in meta:
            t.problems.append(f"workload rc={meta.get('rc')}")
        if meta.get("gate_timeout") == "1":
            t.problems.append(f"portão térmico estourou (soc={meta.get('gate_tmax')} bat={meta.get('gate_tbat')})")
        if meta.get("gate_floor_timeout") == "1":
            t.problems.append(f"piso de CPU externo ativo no início ({meta.get('gate_floor')}: hint da Samsung)")
        if "gate_floor" not in meta and "rc" in meta:
            t.warnings.append("run sem portão de piso (anterior a 2026-10-18 06:3x): pisos de CPU não verificados")
        t.problems += check_knobs(manifest["intended"][arm], tdir)
        if env_begin:
            t.problems += check_env(env_begin, tdir)
        if exp.screen == "on" and t.env.get("wakefulness") not in (None, "Awake"):
            t.problems.append(f"tela não estava acesa ({t.env.get('wakefulness')})")
        if t.env.get("batt_status", "").lower().startswith("charg"):
            t.warnings.append("carregando: potência da bateria não vale")
        t.metrics.update(wl.parse(tdir, ctx))
        t.metrics.update(parse.parse_telemetry(tdir / "telemetry"))
        t.metrics.update(parse.psi_delta(tdir / "psi_pre", tdir / "psi_post"))
        if "carregando: potência da bateria não vale" in t.warnings:
            t.metrics.pop("pbat_mw", None)
        t.problems += wl.validity(tdir, t.metrics)

    restore_problems: list[str] = []
    rows = parse.read_tsv(run_dir / "restore.tsv")
    if not rows:
        restore_problems.append("restore.tsv ausente")
    base_write = manifest["baseline_write"]
    cs_rest = _curset(run_dir / "curset_restored.txt")
    for r in rows:
        kid = r[0]
        kn = K.get(kid)
        if kn.access == "ems":
            mode, level = K.ems_mode_level(kn)
            if cs_rest is None or (cs_rest["mode"], cs_rest["level"]) != (mode, level):
                continue   # conjunto inativo no fim: a escrita foi feita (restore.tsv), sem leitura possível
            got = emstune.value_of(cs_rest, kn.ems_field)
            if got is None or str(got) != base_write[kid]:
                restore_problems.append(f"{kid}: restaurado {got}, base {base_write[kid]}")
            continue
        got = kn.normalize(r[2] if len(r) > 2 else None)
        if got != base_write.get(kid):
            restore_problems.append(f"{kid}: restaurado {got!r}, base {base_write.get(kid)!r}")
    status = "done" if (run_dir / "DONE").exists() else (
        "aborted: " + parse.read_kv(run_dir / "ABORTED").get("reason", "?") if (run_dir / "ABORTED").exists() else "?")
    return RunData(run_dir, manifest, exp, trials, not restore_problems, restore_problems, status,
                   parse.read_kv(run_dir / "env_begin"))


def regime_warnings(rd: RunData) -> list[str]:
    """O burst padrão é biestável no EMS: o ontime só sobe a tarefa para os A78 com util > 90 e só
    desce com util < 61; nos A55 o estágio 1 tem util ~63 (fica preso em 533 MHz, ~83% de perda),
    nos A78 ~123 (fica lá, ~1-2% de perda). Qual dos dois depende de onde a tarefa caiu primeiro.
    Se a base mistura os dois regimes entre blocos, o efeito medido mistura dois experimentos."""
    if rd.exp.workload.get("kind") != "burst":
        return []
    vals = [t.metrics.get("big1_pct") for t in rd.trials if t.valid and t.arm == rd.exp.baseline_arm]
    vals = [v for v in vals if v is not None]
    if len(vals) < 2:
        return []
    lo, hi = min(vals), max(vals)
    out = [f"regime da base: estágio 1 nos A78 em {lo:.0f}-{hi:.0f}% dos frames "
           f"({'A78 (equilíbrio bom)' if lo > 90 else 'A55 (equilíbrio preso)' if hi < 40 else 'MISTO'})"]
    if hi - lo > 40:
        out.append("AVISO: a base alternou entre os dois equilíbrios do ontime dentro do run; "
                   "o efeito é média de dois regimes — compare com cuidado")
    return out


def results(rd: RunData) -> list[stats.Result]:
    wl = W.make(rd.exp.workload)
    rows = [{"block": t.block, "arm": t.arm, "metrics": t.metrics} for t in rd.trials if t.valid]
    metrics = list(dict.fromkeys([rd.exp.primary or wl.default_primary] + list(wl.metrics) + list(W.COMMON_METRICS)))
    out = []
    for arm in rd.exp.arm_names[1:]:
        for m in metrics:
            r = stats.analyze(rows, m, rd.exp.baseline_arm, arm, wl.direction(m), rd.exp.min_effect_pct)
            if r is not None:
                out.append(r)
    return out


def knob_change_summary(rd: RunData) -> dict[str, dict[str, str]]:
    base = rd.manifest["baseline_write"]
    out = {}
    for arm, vals in rd.manifest["intended"].items():
        out[arm] = {k: v for k, v in vals.items() if k != SCREEN and v != base.get(k)}
    return out
