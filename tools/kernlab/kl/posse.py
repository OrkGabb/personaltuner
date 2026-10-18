"""Posse do nó: grava valores, provoca os eventos que a Samsung usa para reafirmar a política
dela e relê tudo depois de cada evento.

Responde "dá para controlar este nó?" com medição: um nó que volta sozinho depois de um
launch não está sob controle de quem o escreveu — e um tuning que mexe nele mede o próprio
escritor da Samsung, não o valor. (Caso real de 2026-10-18: top-app uclamp.min 25.00 -> 1.00
em 6/6 trials de scroll com relaunch.)
"""
from __future__ import annotations

from pathlib import Path

from . import emstune
from . import knobs as K
from . import parse
from .plan import SCREEN, Baseline, _q

EVENTS = ("wait", "launch", "scroll", "home", "other_app", "wait")
GESTURE = "swipe 540 1900 540 600 120\nsleep 700\nswipe 540 600 540 1900 120\nsleep 700\n" * 3


def compile_posse(sets: dict[str, str], base: Baseline, run_id: str) -> str:
    from . import DEVICE_HOME
    screen = K.get(SCREEN)
    lines = ["#!/system/bin/sh", f"# kernlab posse {run_id}", "umask 022", f". {DEVICE_HOME}/agent.sh",
             f"kl_begin {_q(run_id)} {len(EVENTS)}"]
    for kid in sets:
        kn = K.get(kid)
        lines.append(f"kl_knob {_q(kid)} {kn.access} {_q(kn.target)} {_q(base.write[kid])} {_q(base.raw[kid])}")
    lines.append(f"kl_knob {_q(SCREEN)} {screen.access} {_q(screen.target)} {_q(base.write[SCREEN])} {_q(base.raw[SCREEN])}")
    lines += ["kl_verify_baseline", f"KL_T=$KL_RUN; kl_set {SCREEN} 2147483647"]
    for kid, val in sets.items():
        lines.append(f"kl_set {_q(kid)} {_q(val)}")
    lines.append("kl_state 00_aplicado")
    for i, ev in enumerate(EVENTS, start=1):
        lines.append(f"kl_status 'evento {i}/{len(EVENTS)} {ev}'")
        lines.append(f"ev_{ev}")
        lines.append(f"kl_state {i:02d}_{ev}")
    lines.append("kl_end")
    return "\n".join(lines) + "\n"


def table(run_dir: Path, sets: dict[str, str]) -> list[tuple[str, str, list[tuple[str, str]], str]]:
    """[(nó, pedido, [(evento, lido)], veredito)]"""
    tags = sorted(p.name[len("state_"):-len(".tsv")] for p in run_dir.glob("state_*.tsv"))
    out = []
    for kid, want in sets.items():
        kn = K.get(kid)
        want_n = kn.validate(want)
        seen: list[tuple[str, str]] = []
        lost_at = None
        for tag in tags:
            if kn.access == "ems":
                cs_path = run_dir / f"curset_{tag}.txt"
                cs = emstune.parse(cs_path.read_text(encoding="utf-8", errors="replace")) if cs_path.is_file() else None
                mode, level = K.ems_mode_level(kn)
                if cs is None:
                    got = "?"
                elif (cs["mode"], cs["level"]) != (mode, level):
                    got = f"inativo(m{cs['mode']}l{cs['level']})"
                else:
                    got = str(emstune.value_of(cs, kn.ems_field))
            else:
                rows = {r[0]: (r[1] if len(r) > 1 else "") for r in parse.read_tsv(run_dir / f"state_{tag}.tsv")}
                got = kn.normalize(rows.get(kid))
            seen.append((tag, got))
            if lost_at is None and got != want_n and not str(got).startswith("inativo"):
                lost_at = tag
        if lost_at is None:
            verdict = "SEGURA"
        elif lost_at.endswith("_aplicado"):
            verdict = "NÃO ACEITOU a escrita"
        else:
            verdict = f"REVERTIDO em {lost_at.split('_', 1)[1]}"
        out.append((kid, want_n, seen, verdict))
    return out
