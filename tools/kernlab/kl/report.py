"""Relatório em Markdown de um run analisado."""
from __future__ import annotations

from . import analysis
from . import workloads as W


def _fmt(v: float) -> str:
    if v != v:  # NaN
        return "—"
    a = abs(v)
    if a >= 1000:
        return f"{v:.0f}"
    if a >= 10:
        return f"{v:.1f}"
    return f"{v:.3g}"


def render(rd: analysis.RunData) -> str:
    exp = rd.exp
    wl = W.make(exp.workload)
    primary = exp.primary or wl.default_primary
    res = analysis.results(rd)
    valid = [t for t in rd.trials if t.valid]
    lines = [f"# kernlab — {exp.name}", ""]
    if exp.hypothesis:
        lines += [f"**Hipótese:** {exp.hypothesis}", ""]
    lines += [
        f"- Run: `{rd.manifest['run_id']}` — estado: **{rd.status}**",
        f"- Workload: `{wl.kind}` {', '.join(f'{k}={v}' for k, v in exp.workload.items() if k != 'kind')}",
        f"- Desenho: {exp.rounds} blocos espelhados, {len(rd.trials)} trials, **{len(valid)} válidos**; "
        f"limiar de efeito {exp.min_effect_pct:g}%; métrica primária `{primary}` "
        f"({'menor' if wl.direction(primary) == 'lower' else 'maior'} é melhor)",
        f"- Aparelho no início: boot `{rd.env_begin.get('boot_id', '?')[:8]}`, uptime {rd.env_begin.get('uptime', '?')}s, "
        f"bateria {rd.env_begin.get('batt_level', '?')}% ({rd.env_begin.get('batt_status', '?')}), "
        f"emstune `{rd.env_begin.get('emstune', '?')}`, foco `{rd.env_begin.get('focus', '?')}`",
        f"- Restauração ao fim: **{'OK' if rd.restore_ok else 'FALHOU'}**"
        + ("" if rd.restore_ok else " — " + "; ".join(rd.restore_problems)),
        "",
        "## Braços",
        "",
    ]
    for arm, changes in analysis.knob_change_summary(rd).items():
        desc = ", ".join(f"`{k}`={v}" for k, v in changes.items()) or "estado de base (como o aparelho estava)"
        lines.append(f"- **{arm}**: {desc}")
    lines.append("")

    def table(rows, title):
        out = [f"## {title}", "",
               "| métrica | braço | blocos | base | braço | efeito | IC 95% | p | veredito |",
               "|---|---|---|---|---|---|---|---|---|"]
        for r in rows:
            out.append(f"| `{r.metric}` | {r.arm} | {r.n_blocks} | {_fmt(r.base_mean)} | {_fmt(r.arm_mean)} | "
                       f"{r.effect_pct:+.1f}% | [{r.ci_lo_pct:+.1f}, {r.ci_hi_pct:+.1f}] | {r.p_value:.3f} | "
                       f"**{r.verdict}** |")
        return out + [""]

    lines += table([r for r in res if r.metric == primary], "Resultado (métrica primária)")
    lines += table([r for r in res if r.metric != primary],
                   "Métricas secundárias (exploratórias — não decidem sozinhas)")
    noise = [r for r in res if r.metric == primary and r.base_cv_pct == r.base_cv_pct]
    if noise:
        lines += [f"Ruído da base entre blocos (`{primary}`): CV {noise[0].base_cv_pct:.1f}%.", ""]

    bad = [t for t in rd.trials if not t.valid]
    warn = [t for t in rd.trials if t.warnings]
    lines += ["## Validade", ""]
    for w in analysis.regime_warnings(rd):
        lines.append(f"- {w}")
    if not bad and not warn:
        lines.append("Todos os trials mediram o que diziam: nós seguraram do início ao fim, sem deriva.")
    for t in bad:
        lines.append(f"- t{t.index} ({t.arm}, bloco {t.block}) **excluído**: " + "; ".join(t.problems))
    for t in warn:
        lines.append(f"- t{t.index} ({t.arm}, bloco {t.block}) aviso: " + "; ".join(t.warnings))
    lines.append("")
    lines += ["## Trials", "", f"| # | bloco | braço | {primary} | pbat_mw | tbig_max_mc | ok |", "|---|---|---|---|---|---|---|"]
    for t in rd.trials:
        lines.append(f"| {t.index} | {t.block} | {t.arm} | {_fmt(t.metrics.get(primary, float('nan')))} | "
                     f"{_fmt(t.metrics.get('pbat_mw', float('nan')))} | {_fmt(t.metrics.get('tbig_max_mc', float('nan')))} | "
                     f"{'sim' if t.valid else 'não'} |")
    lines.append("")
    return "\n".join(lines)
