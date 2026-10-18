"""Parsers dos artefatos que o agente grava em cada trial."""
from __future__ import annotations

import re
from pathlib import Path


def read_kv(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path.is_file():
        return out
    for ln in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" in ln:
            k, v = ln.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def read_tsv(path: Path) -> list[list[str]]:
    if not path.is_file():
        return []
    return [ln.split("\t") for ln in path.read_text(encoding="utf-8", errors="replace").splitlines() if ln]


def num(s: str | None) -> float | None:
    if s is None:
        return None
    m = re.match(r"\s*(-?\d+(?:\.\d+)?)", s)
    return float(m.group(1)) if m else None


# ------------------------------------------------------------------ tabela de energia do EMS
def parse_energy_table(text: str) -> dict[int, dict[int, float]]:
    """{cpu_líder: {freq_khz: potência_dinâmica}} a partir de ems/energy_table/energy_table."""
    out: dict[int, dict[int, float]] = {}
    cur = None
    for ln in text.splitlines():
        m = re.match(r"\[Energy Table: cpu(\d+)\]", ln.strip())
        if m:
            cur = int(m.group(1))
            out[cur] = {}
            continue
        if cur is None:
            continue
        cols = [c.strip() for c in ln.strip().strip("|").split("|")]
        if len(cols) >= 3 and cols[0].isdigit():
            out[cur][int(cols[0])] = float(cols[2])
    return out


def tis_energy_mj(tis: str, table: dict[int, dict[int, float]]) -> float | None:
    """Energia dinâmica de CPU (mJ) pelo modelo do EMS: soma ticks(10 ms) x P_din(freq)."""
    if not tis or not table:
        return None
    total = 0.0
    for item in tis.split(","):
        parts = item.split(":")
        if len(parts) != 3:
            continue
        cl, fr, tk = int(parts[0]), int(parts[1]), int(parts[2])
        p = table.get(cl, {}).get(fr)
        if p is None:
            return None
        total += tk * 0.01 * p
    return total


# ------------------------------------------------------------------ gfxinfo (HWUI)
_GFX = {
    "frames": r"Total frames rendered:\s*(\d+)",
    "janky": r"Janky frames:\s*(\d+)",
    "janky_pct": r"Janky frames:\s*\d+\s*\(([\d.]+)%\)",
    "ft_p50_ms": r"^50th percentile:\s*(\d+)ms",
    "ft_p90_ms": r"^90th percentile:\s*(\d+)ms",
    "ft_p95_ms": r"^95th percentile:\s*(\d+)ms",
    "ft_p99_ms": r"^99th percentile:\s*(\d+)ms",
    "missed_vsync": r"Number Missed Vsync:\s*(\d+)",
    "slow_ui": r"Number Slow UI thread:\s*(\d+)",
    "deadline_missed": r"Number Frame deadline missed:\s*(\d+)",
    "gpu_p50_ms": r"^50th gpu percentile:\s*(\d+)ms",
    "gpu_p99_ms": r"^99th gpu percentile:\s*(\d+)ms",
}


def parse_gfxinfo(text: str) -> dict[str, float]:
    out: dict[str, float] = {}
    for k, rx in _GFX.items():
        m = re.search(rx, text, re.M)
        if m:
            out[k] = float(m.group(1))
    return out


# ------------------------------------------------------------------ SurfaceFlinger TimeStats
def _hist(line: str) -> list[tuple[int, int]]:
    return [(int(a), int(b)) for a, b in re.findall(r"(\d+)ms=(\d+)", line)]


def hist_pct(h: list[tuple[int, int]], q: float) -> float | None:
    total = sum(c for _, c in h)
    if total == 0:
        return None
    target = q * total
    acc = 0
    for ms, c in h:
        acc += c
        if acc >= target:
            return float(ms)
    return float(h[-1][0])


IDLE_GAP_MS = 100   # intervalo maior que isto é pausa entre gestos, não frame atrasado


def _layer_records(text: str) -> list[dict]:
    """Cada registro de camada com o displayRefreshRate do bloco em que aparece. O TimeStats
    separa a MESMA camada por taxa de refresh: no adaptativo há um registro a 60 e outro a 120."""
    out = []
    hz = None
    cur = None
    pending = None
    for ln in text.splitlines():
        s = ln.strip()
        m = re.match(r"displayRefreshRate = (\d+) fps", s)
        if m:
            hz = float(m.group(1))
            continue
        if s.startswith("layerName = "):
            cur = {"layerName": s.split("=", 1)[1].strip(), "hz": hz}
            out.append(cur)
            pending = None
            continue
        if cur is None:
            continue
        if pending is not None:
            if "ms=" in s:
                cur[pending] = _hist(s)
            pending = None
            continue
        m = re.match(r"(\w+) histogram is as below:", s)
        if m:
            pending = m.group(1)
            continue
        m = re.match(r"(\w+) = (-?[\d.]+)$", s)
        if m and m.group(1) not in cur:
            cur[m.group(1)] = float(m.group(2))
    return out


def timestats_for(text: str, package: str) -> dict[str, float]:
    """Métricas do pacote somando todos os registros dele (todas as taxas de refresh).

    ts_hitch_n / ts_hitch_pct = frames apresentados com atraso de mais de 1,5 vsync da taxa
    em vigor e menos de IDLE_GAP_MS — a "travadinha" que se vê no scroll."""
    recs = [r for r in _layer_records(text) if package in r.get("layerName", "") and r.get("totalFrames", 0) > 0]
    if not recs:
        return {}
    out: dict[str, float] = {}
    frames = sum(r.get("totalFrames", 0) for r in recs)
    out["ts_frames"] = frames
    out["ts_dropped"] = sum(r.get("droppedFrames", 0) for r in recs)
    out["ts_janky"] = sum(r.get("jankyFrames", 0) for r in recs)
    out["ts_timeline_frames"] = sum(r.get("totalTimelineFrames", 0) for r in recs)
    if out["ts_timeline_frames"]:
        out["ts_janky_pct"] = 100.0 * out["ts_janky"] / out["ts_timeline_frames"]
    if frames:
        out["ts_fps"] = sum(r.get("averageFPS", 0) * r.get("totalFrames", 0) for r in recs) / frames
        hz_recs = [r for r in recs if r.get("hz")]
        if hz_recs:
            out["ts_display_hz"] = sum(r["hz"] * r.get("totalFrames", 0) for r in hz_recs) / max(
                1, sum(r.get("totalFrames", 0) for r in hz_recs))
            out["ts_frames_at_120_pct"] = 100.0 * sum(r.get("totalFrames", 0) for r in hz_recs if r["hz"] >= 119) / frames
    merged: dict[int, int] = {}
    hitch = motion = 0
    for r in recs:
        h = r.get("present2present") or []
        period = 1000.0 / r["hz"] if r.get("hz") else 1000.0 / 60
        for ms, c in h:
            merged[ms] = merged.get(ms, 0) + c
            if ms < IDLE_GAP_MS:
                motion += c
                if ms > 1.5 * period:
                    hitch += c
    if merged:
        hm = sorted(merged.items())
        moving = [(ms, c) for ms, c in hm if ms < IDLE_GAP_MS]
        for q, name in ((0.5, "ts_p2p_p50_ms"), (0.95, "ts_p2p_p95_ms"), (0.99, "ts_p2p_p99_ms")):
            v = hist_pct(moving or hm, q)
            if v is not None:
                out[name] = v
        out["ts_p2p_ge33ms_pct"] = 100.0 * sum(c for ms, c in moving if ms >= 33) / motion if motion else 0.0
        out["ts_hitch_n"] = float(hitch)
        out["ts_hitch_pct"] = 100.0 * hitch / motion if motion else 0.0
    return out


# ------------------------------------------------------------------ telemetria (kl_sample)
def parse_telemetry(path: Path) -> dict[str, float]:
    kv = read_kv(path)
    out: dict[str, float] = {}
    for key, name in (("pbat_mw_mean", "pbat_mw"), ("f0_mean", "f0_khz"), ("f4_mean", "f4_khz"),
                      ("gpu_mean", "gpu_khz"), ("mif_mean", "mif_khz"), ("tbig_max", "tbig_max_mc"),
                      ("tg3d_max", "tg3d_max_mc"), ("tbat_max", "tbat_max_dc"), ("sample_s", "sample_s")):
        v = num(kv.get(key))
        if v is not None:
            out[name] = v
    return out


def psi_delta(pre: Path, post: Path) -> dict[str, float]:
    a, b = read_kv(pre), read_kv(post)
    out: dict[str, float] = {}
    for k in b:
        va, vb = num(a.get(k)), num(b.get(k))
        if va is not None and vb is not None:
            out["psi_" + k.replace("_total", "") + "_ms"] = (vb - va) / 1000.0
    return out
