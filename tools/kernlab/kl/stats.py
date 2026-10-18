"""Estatística pareada por bloco.

Unidade de análise = bloco. Em cada bloco, a média do braço X e a média do braço de base
são tomadas sobre os trials DAQUELE bloco; a diferença por bloco cancela o que é comum ao
bloco (temperatura, bateria, estado do sistema). Sobre as diferenças relativas:
  * efeito (%) = média(X - base) / média(base)
  * IC 95% pela distribuição t das diferenças por bloco (com poucos blocos o bootstrap
    percentil fica estreito demais: no primeiro A/A real, 4 blocos, deu "MELHORA" de -8% em
    potência entre dois braços idênticos — 2026-10-18)
  * p por permutação de sinal (exata até 16 blocos)
Veredito de efeito exige p < 0,05 E IC fora de zero E |efeito| >= limiar declarado. Com a
permutação exata o menor p possível é 2/2^n: 4 blocos nunca concluem (0,125); 6 blocos
podem (0,031). Um IC inteiro dentro de ±limiar vira "equivalente" — também é resposta.
"""
from __future__ import annotations

import itertools
from dataclasses import dataclass

import numpy as np


@dataclass
class Result:
    metric: str
    arm: str
    n_blocks: int
    base_mean: float
    arm_mean: float
    effect_pct: float
    ci_lo_pct: float
    ci_hi_pct: float
    p_value: float
    verdict: str
    base_cv_pct: float


def block_pairs(rows: list[dict], metric: str, base_arm: str, arm: str) -> list[tuple[float, float]]:
    by_block: dict[int, dict[str, list[float]]] = {}
    for r in rows:
        v = r["metrics"].get(metric)
        if v is None:
            continue
        by_block.setdefault(r["block"], {}).setdefault(r["arm"], []).append(float(v))
    out = []
    for b in sorted(by_block):
        d = by_block[b]
        if d.get(base_arm) and d.get(arm):
            out.append((float(np.mean(d[base_arm])), float(np.mean(d[arm]))))
    return out


def sign_flip_p(diffs: np.ndarray, n_random: int = 20000, seed: int = 7) -> float:
    n = len(diffs)
    if n == 0:
        return 1.0
    obs = abs(diffs.mean())
    if np.allclose(diffs, 0):
        return 1.0
    if n <= 16:
        count = 0
        total = 0
        for signs in itertools.product((1.0, -1.0), repeat=n):
            total += 1
            if abs((diffs * np.array(signs)).mean()) >= obs - 1e-12:
                count += 1
        return count / total
    rng = np.random.default_rng(seed)
    signs = rng.choice((1.0, -1.0), size=(n_random, n))
    stats = np.abs((signs * diffs).mean(axis=1))
    return float((np.sum(stats >= obs - 1e-12) + 1) / (n_random + 1))


_T975 = (12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160,
         2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056,
         2.052, 2.048, 2.045, 2.042)


def t975(df: int) -> float:
    if df < 1:
        return float("nan")
    if df <= len(_T975):
        return _T975[df - 1]
    return 2.0 if df <= 60 else (1.98 if df <= 120 else 1.96)


def t_ci(base: np.ndarray, arm: np.ndarray) -> tuple[float, float]:
    """IC 95% do efeito relativo (%) pela t das diferenças por bloco, escaladas pela média da base."""
    n = len(base)
    bm = float(base.mean())
    if n < 2 or bm == 0:
        return float("nan"), float("nan")
    rel = 100.0 * (arm - base) / abs(bm)
    half = t975(n - 1) * float(rel.std(ddof=1)) / np.sqrt(n)
    m = float(rel.mean())
    return m - half, m + half


def verdict(effect: float, lo: float, hi: float, p: float, direction: str, min_eff: float, n: int) -> str:
    if n < 2 or not np.isfinite(lo):
        return "INSUFICIENTE"
    good_sign = -1 if direction == "lower" else 1
    if -min_eff <= lo and hi <= min_eff:
        return f"EQUIVALENTE (±{min_eff:g}%)"
    if (lo > 0 or hi < 0) and p < 0.05:
        if abs(effect) < min_eff:
            return f"REAL MAS PEQUENO (<{min_eff:g}%)"
        return "MELHORA" if (effect > 0) == (good_sign > 0) else "PIORA"
    return "INCONCLUSIVO"


def analyze(rows: list[dict], metric: str, base_arm: str, arm: str, direction: str,
            min_effect_pct: float) -> Result | None:
    pairs = block_pairs(rows, metric, base_arm, arm)
    if not pairs:
        return None
    base = np.array([p[0] for p in pairs])
    arm_v = np.array([p[1] for p in pairs])
    bm = float(base.mean())
    am = float(arm_v.mean())
    eff = 100.0 * (am - bm) / abs(bm) if bm != 0 else (0.0 if am == bm else float("inf"))
    lo, hi = t_ci(base, arm_v)
    if bm == 0 and am == 0:
        lo, hi = 0.0, 0.0
    p = sign_flip_p(arm_v - base)
    cv = float(100.0 * base.std(ddof=1) / abs(bm)) if len(base) >= 2 and bm != 0 else float("nan")
    return Result(metric, arm, len(pairs), bm, am, eff, lo, hi, p,
                  verdict(eff, lo, hi, p, direction, min_effect_pct, len(pairs)), cv)
