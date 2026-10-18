"""Parser do /sys/devices/platform/ems/emstune/cur_set.

O aio_tuner do EMS ignora em silêncio valor fora do domínio (tune.c: set_value_* retorna
sem erro), então o código de retorno da escrita não prova nada. A única prova é o cur_set,
que mostra o conjunto ATIVO (modo/nível) — este parser o transforma em
{(função, índice): valor} no mesmo formato de Knob.ems_field.
"""
from __future__ import annotations

import re

from .knobs import EMS_GROUPS

_HEADER = re.compile(r"\[mode=(\d+)\s+level=(\d+)\]")
_ROW = re.compile(r"^\s*cpu(\d+):\s*(.*)$")
_KV = re.compile(r"^\s*([a-z_ ]+?)\s*:\s*(-?\d+)")


def _ints(s: str) -> list[int]:
    return [int(x) for x in re.findall(r"-?\d+", s)]


def parse(text: str) -> dict:
    out: dict = {"mode": None, "level": None, "fields": {}}
    lines = [ln.rstrip("\r") for ln in text.strip().splitlines()]
    if lines:
        m = _HEADER.search(lines[0])
        if m:
            out["mode"], out["level"] = int(m.group(1)), int(m.group(2))
    sections: dict[str, list[str]] = {}
    cur = None
    for ln in lines[1:]:
        s = ln.strip()
        if s.startswith("[") and s.endswith("]"):
            cur = s[1:-1]
            sections[cur] = []
        elif cur is not None:
            sections[cur].append(ln)
    f = out["fields"]

    def group_row(body: list[str], name: str) -> None:
        # primeira linha só de números logo após o cabeçalho "root fg bg ..."
        for i, ln in enumerate(body):
            if ln.split()[:2] == ["root", "fg"] and i + 1 < len(body):
                vals = _ints(body[i + 1])
                if len(vals) == len(EMS_GROUPS):
                    for g, v in zip(EMS_GROUPS, vals):
                        f[(name, g)] = v
                return

    def cpu_rows(body: list[str]):
        for ln in body:
            m = _ROW.match(ln)
            if m:
                yield int(m.group(1)), m.group(2)

    for sec, body in sections.items():
        if sec in ("freq-boost", "active-weight", "idle-weight"):
            name = {"freq-boost": "freqboost", "active-weight": "active_weight",
                    "idle-weight": "idle_weight"}[sec]
            for cpu, rest in cpu_rows(body):
                vals = _ints(rest)
                if len(vals) == len(EMS_GROUPS):
                    for g, v in zip(EMS_GROUPS, vals):
                        f[(name, (cpu, g))] = v
        elif sec == "cpufreq gov":
            for cpu, rest in cpu_rows(body):
                tok = rest.split()
                if len(tok) < 7:
                    continue
                names = ("pelt_boost", "htask_boost", "dis_buck_share", "pelt_margin",
                         "pelt_margin_freq", "rate_limit", "rate_limit_freq")
                for n, t in zip(names, tok):
                    if re.fullmatch(r"-?\d+", t):
                        f[(n, cpu)] = int(t)
        elif sec == "cpuidle gov":
            for cpu, rest in cpu_rows(body):
                v = _ints(rest)
                if v:
                    f[("cpuidle_expired_ratio", cpu)] = v[0]
        elif sec == "frt":
            for cpu, rest in cpu_rows(body):
                v = _ints(rest)
                if v:
                    f[("frt_active_ratio", cpu)] = v[0]
        elif sec == "ecs":
            for cpu, rest in cpu_rows(body):
                v = _ints(rest)
                if v:
                    f[("ecs_busy_ratio", cpu)] = v[0]
        elif sec == "ontime":
            group_row(body, "ontime_en")
        elif sec == "new task util ratio":
            group_row(body, "ntu_ratio")
        elif sec == "gsc":
            group_row(body, "gsc_en")
            for ln in body:
                m = _KV.match(ln)
                if m and m.group(1) in ("up_threshold", "down_threshold"):
                    f[("gsc_" + m.group(1), None)] = int(m.group(2))
        elif sec == "tex":
            for ln in body:
                m = _KV.match(ln)
                if m and m.group(1) == "prio":
                    f[("tex_prio", None)] = int(m.group(2))
        elif sec in ("support uclamp", "should spread"):
            for ln in body:
                m = _KV.match(ln)
                if m and m.group(1) == "enabled":
                    f[(sec.replace(" ", "_"), None)] = int(m.group(2))
    return out


def value_of(parsed: dict, ems_field: tuple) -> int | None:
    """Valor de um campo para um Knob.ems_field; para escopo de coregrupo usa a CPU-líder."""
    name, idx = ems_field
    return parsed["fields"].get((name, idx))
