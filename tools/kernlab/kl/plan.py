"""Experimento (JSON) -> plano de execução (plan.sh) para o agente do aparelho.

Desenho: blocos espelhados. Cada bloco roda uma permutação dos braços e depois a mesma
permutação ao contrário (A B | B A, ou A C B | B C A). Isso cancela deriva linear (calor,
bateria) dentro do bloco, e a análise compara braços DENTRO do mesmo bloco. A ordem da
permutação muda a cada bloco (semente fixa) para não alinhar um braço sempre com a mesma
fase de algo periódico.

O estado de base é explícito: o PC lê o aparelho no preflight, escreve os valores no
plano, e o agente recusa começar se o estado mudou entre a leitura e o início.
"""
from __future__ import annotations

import json
import random
from dataclasses import dataclass, field
from pathlib import Path

from . import knobs as K
from . import workloads as W
from .emstune import value_of

BASE = "@base"
SCREEN = "settings.screen_off_timeout"   # o runner mantém a tela acesa e restaura no fim


@dataclass
class Experiment:
    name: str
    workload: dict
    arms: dict[str, dict[str, str]]
    hypothesis: str = ""
    rounds: int = 4
    settle_s: float = 3
    gate: dict = field(default_factory=lambda: {"soc_c": 45, "batt_c": 37, "timeout_s": 300})
    primary: str = ""
    min_effect_pct: float = 3.0
    seed: int = 1
    allow_medium: bool = False
    notes: str = ""
    screen: str = "on"           # "on": exige tela acesa e destravada no início e em todo trial; "any"

    @property
    def arm_names(self) -> list[str]:
        return list(self.arms)

    @property
    def baseline_arm(self) -> str:
        return self.arm_names[0]

    def knob_ids(self) -> list[str]:
        ids: list[str] = []
        for a in self.arms.values():
            for k in a:
                if k not in ids:
                    ids.append(k)
        return ids


def load(path: Path) -> Experiment:
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    d.pop("$comment", None)
    exp = Experiment(**d)
    validate(exp)
    return exp


def validate(exp: Experiment) -> None:
    if len(exp.arms) < 2:
        raise K.KnobError("um experimento precisa de pelo menos 2 braços")
    if exp.rounds < 2:
        raise K.KnobError("rounds >= 2 (precisa de blocos para parear)")
    wl = W.make(exp.workload)
    if exp.primary and exp.primary not in wl.metrics and exp.primary not in W.COMMON_METRICS:
        raise K.KnobError(f"métrica primária desconhecida para {wl.kind}: {exp.primary}")
    if exp.gate.get("floor", "min") not in ("min", "start"):
        raise K.KnobError("gate.floor: 'min' (piso no mínimo físico) ou 'start' (piso do início do run)")
    for arm, settings in exp.arms.items():
        for kid, val in settings.items():
            kn = K.get(kid)
            if kn.risk != "low" and not exp.allow_medium:
                raise K.KnobError(f"{kid} tem risco '{kn.risk}': exige \"allow_medium\": true no experimento")
            if val != BASE:
                kn.validate(val)


def schedule(exp: Experiment) -> list[tuple[int, str]]:
    """[(bloco, braço)] na ordem de execução."""
    rng = random.Random(exp.seed)
    out: list[tuple[int, str]] = []
    arms = exp.arm_names
    for b in range(exp.rounds):
        perm = arms[:]
        if b == 0:
            pass
        elif len(arms) == 2:
            perm = arms[::-1] if b % 2 else arms[:]
        else:
            rng.shuffle(perm)
        for a in perm + perm[::-1]:
            out.append((b, a))
    return out


@dataclass
class Baseline:
    raw: dict[str, str]          # leitura crua (o agente compara exatamente esta string)
    write: dict[str, str]        # valor canônico para escrever/restaurar
    ems: dict | None = None      # cur_set parseado, se houver nó EMS


def ems_sets_needed(exp: Experiment) -> set[tuple[int, int]]:
    return {K.ems_mode_level(K.get(k)) for k in exp.knob_ids() if K.get(k).access == "ems"}


def baseline_from_preflight(exp: Experiment, raw_reads: dict[str, str], curset: dict | None,
                            seen_cursets: dict[tuple[int, int], dict] | None = None) -> Baseline:
    """seen_cursets: cur_set capturado quando cada conjunto esteve ATIVO (ex.: nível 1, que só
    aparece durante o toque). Sem ele, um nó de conjunto inativo não tem base provável e é recusado."""
    raw: dict[str, str] = {}
    write: dict[str, str] = {}
    sets: dict[tuple[int, int], dict] = dict(seen_cursets or {})
    if curset is not None and curset.get("mode") is not None:
        sets[(curset["mode"], curset["level"])] = curset
    for kid in exp.knob_ids() + [SCREEN]:
        kn = K.get(kid)
        if kn.access == "ems":
            if not sets:
                raise K.KnobError("nó EMS no experimento, mas o cur_set não foi lido")
            mode, level = K.ems_mode_level(kn)
            cs = sets.get((mode, level))
            if cs is None:
                act = (curset or {}).get("mode"), (curset or {}).get("level")
                raise K.KnobError(
                    f"{kid} é do modo {mode} nível {level}, que não esteve ativo no preflight (ativo: modo "
                    f"{act[0]} nível {act[1]}): sem base provável, o efeito não seria verificável")
            v = value_of(cs, kn.ems_field)
            if v is None:
                raise K.KnobError(f"{kid}: campo {kn.ems_field} não encontrado no cur_set")
            raw[kid] = "@curset"
            write[kid] = str(v)
            continue
        if kid not in raw_reads or raw_reads[kid] in (None, ""):
            raise K.KnobError(f"{kid}: nó ausente ou ilegível no aparelho ({kn.target})")
        raw[kid] = raw_reads[kid]
        write[kid] = kn.validate(kn.normalize(raw_reads[kid]))
    return Baseline(raw, write, curset)


def intended(exp: Experiment, base: Baseline, arm: str) -> dict[str, str]:
    out = {}
    for kid in exp.knob_ids():
        v = exp.arms[arm].get(kid, BASE)
        out[kid] = base.write[kid] if v == BASE else K.get(kid).validate(v)
    return out


def _q(s: str) -> str:
    return "'" + str(s).replace("'", "'\"'\"'") + "'"


def compile_plan(exp: Experiment, base: Baseline, run_id: str, run_dir: str) -> tuple[str, dict[str, str], dict]:
    """-> (plan.sh, arquivos de apoio {nome: conteúdo}, manifesto json)."""
    from . import DEVICE_HOME
    wl = W.make(exp.workload)
    sched = schedule(exp)
    screen = K.get(SCREEN)
    lines = [
        "#!/system/bin/sh",
        f"# kernlab plan {run_id} — gerado no PC; não editar à mão",
        "umask 022",
        f". {DEVICE_HOME}/agent.sh",
        f"kl_begin {_q(run_id)} {len(sched)}",
    ]
    for kid in exp.knob_ids():
        kn = K.get(kid)
        lines.append(f"kl_knob {_q(kid)} {kn.access} {_q(kn.target)} {_q(base.write[kid])} {_q(base.raw[kid])}")
    lines.append(f"kl_knob {_q(screen.id)} {screen.access} {_q(screen.target)} {_q(base.write[SCREEN])} "
                 f"{_q(base.raw[SCREEN])}")
    lines.append("kl_verify_baseline")
    # "nunca" durante o run: a tela apagar troca a política da Samsung inteira (emstune vai para
    # power-scenario, top-app uclamp.min 1.00 -> 0.00) e o trial mediria outro regime
    lines.append(f"KL_T=$KL_RUN; kl_set {SCREEN} 2147483647")
    lines += wl.setup_lines(run_dir)
    measure = "kl_measure " + W.shell_line(wl.device_args(run_dir))
    gate = exp.gate
    # t0: aquecimento descartado. No primeiro A/A real o trial 1 saiu do estado deixado pelo
    # preflight/deploy (842 mW e 63% de perda contra ~720 mW e ~93% nos demais).
    lines.append(f"kl_trial 0 {_q('aquecimento')} -1")
    for kid, val in intended(exp, base, exp.baseline_arm).items():
        lines.append(f"kl_set {_q(kid)} {_q(val)}")
    lines.append(f"kl_settle {exp.settle_s:g}")
    lines.append(measure)
    lines.append("kl_trial_end")
    for i, (block, arm) in enumerate(sched, start=1):
        lines.append(f"kl_trial {i} {_q(arm)} {block}")
        for kid, val in intended(exp, base, arm).items():
            lines.append(f"kl_set {_q(kid)} {_q(val)}")
        lines.append(f"kl_gate {int(gate.get('soc_c', 45))} {int(gate.get('batt_c', 37))} {int(gate.get('timeout_s', 300))} "
                     f"{gate.get('floor', 'min')}")
        lines.append(f"kl_settle {exp.settle_s:g}")
        lines.append(measure)
        lines.append("kl_trial_end")
    lines.append("kl_end")
    manifest = {
        "run_id": run_id,
        "experiment": exp.__dict__,
        "schedule": sched,
        "baseline_raw": base.raw,
        "baseline_write": base.write,
        "intended": {a: intended(exp, base, a) for a in exp.arm_names},
        "est_seconds": len(sched) * (wl.est_seconds() + exp.settle_s + 6),
    }
    return "\n".join(lines) + "\n", wl.assets(), manifest
