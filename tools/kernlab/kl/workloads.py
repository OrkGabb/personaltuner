"""Workloads: o que roda no aparelho em cada trial e como o resultado vira métrica.

Cada classe sabe (1) a linha `kl_measure ...` do plano, (2) os arquivos de apoio que
precisam ir junto (roteiro de gestos), (3) como ler os artefatos do trial e (4) a direção
de cada métrica (menor ou maior é melhor). Um workload novo é uma classe nova registrada
em WORKLOADS — o compilador de plano e a análise não mudam (OCP).
"""
from __future__ import annotations

import shlex
from pathlib import Path
from statistics import median

from . import parse

LOWER, HIGHER = "lower", "higher"


class Workload:
    kind = ""
    metrics: dict[str, str] = {}
    default_primary = ""

    def __init__(self, spec: dict):
        self.spec = dict(spec)

    def device_args(self, run_dir: str) -> list[str]:
        raise NotImplementedError

    def assets(self) -> dict[str, str]:
        return {}

    def setup_lines(self, run_dir: str) -> list[str]:
        return []

    def est_seconds(self) -> float:
        return 20.0

    def parse(self, tdir: Path, ctx: dict) -> dict[str, float]:
        raise NotImplementedError

    def validity(self, tdir: Path, metrics: dict[str, float]) -> list[str]:
        kv = parse.read_kv(tdir / "metrics")
        problems = []
        if "error" in kv:
            problems.append("workload:" + kv["error"])
        return problems

    def direction(self, metric: str) -> str:
        return self.metrics.get(metric, COMMON_METRICS.get(metric, LOWER))


COMMON_METRICS = {
    "pbat_mw": LOWER, "f0_khz": LOWER, "f4_khz": LOWER, "gpu_khz": LOWER, "mif_khz": LOWER,
    "tbig_max_mc": LOWER, "tg3d_max_mc": LOWER, "tbat_max_dc": LOWER,
}


class Burst(Workload):
    """Frame sintético em 2 estágios (UI -> render) no cgroup pedido. Ver native/kl_burst.c."""
    kind = "burst"
    metrics = {
        "miss_pct": LOWER, "drop_pct": LOWER, "lat_p50_us": LOWER, "lat_p95_us": LOWER,
        "lat_p99_us": LOWER, "wake1_p99_us": LOWER, "wake2_p99_us": LOWER,
        "big1_pct": HIGHER, "big2_pct": HIGHER, "cpu_dyn_mj": LOWER, "mj_per_frame": LOWER,
        "completed": HIGHER,
    }
    default_primary = "lat_p95_us"
    _ARGS = (("period_us", "--period-us", 8333), ("work1", "--work1", 600000),
             ("work2", "--work2", 900000), ("mem_kb", "--mem-kb", 0), ("mem_steps", "--mem-steps", 0),
             ("duration_s", "--duration-s", 15), ("warmup_s", "--warmup-s", 2),
             ("cgroup", "--cgroup", "top-app"), ("noise", "--noise", 0),
             ("noise_cgroup", "--noise-cgroup", "background"), ("noise_duty", "--noise-duty", 50),
             ("fifo", "--fifo", 0))

    def device_args(self, run_dir: str) -> list[str]:
        args = ["burst"]
        for key, flag, default in self._ARGS:
            args += [flag, str(self.spec.get(key, default))]
        return args

    def est_seconds(self) -> float:
        return float(self.spec.get("duration_s", 15)) + float(self.spec.get("warmup_s", 2)) + 2

    def parse(self, tdir: Path, ctx: dict) -> dict[str, float]:
        kv = parse.read_kv(tdir / "metrics")
        out: dict[str, float] = {}
        for k in self.metrics:
            v = parse.num(kv.get(k))
            if v is not None:
                out[k] = v
        e = parse.tis_energy_mj(kv.get("tis", ""), ctx.get("energy_table", {}))
        if e is not None:
            out["cpu_dyn_mj"] = e
            if out.get("completed"):
                out["mj_per_frame"] = e / out["completed"]
        return out

    def validity(self, tdir: Path, metrics: dict[str, float]) -> list[str]:
        p = super().validity(tdir, metrics)
        if not metrics.get("completed"):
            p.append("burst:nenhum frame completo")
        return p


class Scroll(Workload):
    """Scroll real num app HWUI com gesto nativo (uinput) e frames do gfxinfo + TimeStats."""
    kind = "scroll"
    metrics = {
        "frames": HIGHER, "janky_pct": LOWER, "janky": LOWER, "ft_p50_ms": LOWER, "ft_p90_ms": LOWER,
        "ft_p95_ms": LOWER, "ft_p99_ms": LOWER, "missed_vsync": LOWER, "slow_ui": LOWER,
        "deadline_missed": LOWER, "gpu_p50_ms": LOWER, "gpu_p99_ms": LOWER,
        "ts_fps": HIGHER, "ts_janky_pct": LOWER, "ts_p2p_p95_ms": LOWER, "ts_p2p_p99_ms": LOWER,
        "ts_p2p_ge33ms_pct": LOWER, "ts_hitch_n": LOWER, "ts_hitch_pct": LOWER,
        "ts_display_hz": HIGHER, "ts_frames_at_120_pct": HIGHER,
    }
    default_primary = "ts_hitch_pct"

    def __init__(self, spec: dict):
        super().__init__(spec)
        self.package = spec.get("package", "com.android.settings")
        self.am = spec.get("am", ["-a", "android.settings.APPLICATION_SETTINGS"])

    def _gesture(self) -> str:
        n = int(self.spec.get("flings", 6))
        ms = int(self.spec.get("swipe_ms", 120))
        gap = int(self.spec.get("gap_ms", 900))
        x = int(self.spec.get("x", 540))
        y_lo, y_hi = int(self.spec.get("y_top", 600)), int(self.spec.get("y_bottom", 1900))
        lines = []
        for _ in range(n):
            lines += [f"swipe {x} {y_hi} {x} {y_lo} {ms}", f"sleep {gap}"]
        for _ in range(n):
            lines += [f"swipe {x} {y_lo} {x} {y_hi} {ms}", f"sleep {gap}"]
        return "\n".join(lines) + "\n"

    def assets(self) -> dict[str, str]:
        return {"gesture.txt": self._gesture()}

    def device_args(self, run_dir: str) -> list[str]:
        relaunch = "1" if self.spec.get("relaunch", True) else "0"
        return ["scroll", self.package, f"{run_dir}/gesture.txt", str(self.spec.get("settle_s", 2)), relaunch] + list(self.am)

    def est_seconds(self) -> float:
        n = int(self.spec.get("flings", 6))
        return 2 * n * (int(self.spec.get("swipe_ms", 120)) + int(self.spec.get("gap_ms", 900))) / 1000 + 8

    def parse(self, tdir: Path, ctx: dict) -> dict[str, float]:
        out: dict[str, float] = {}
        gfx = tdir / "gfxinfo.txt"
        if gfx.is_file():
            out.update(parse.parse_gfxinfo(gfx.read_text(encoding="utf-8", errors="replace")))
        ts = tdir / "timestats.txt"
        if ts.is_file():
            out.update(parse.timestats_for(ts.read_text(encoding="utf-8", errors="replace"), self.package))
        return out

    def validity(self, tdir: Path, metrics: dict[str, float]) -> list[str]:
        p = super().validity(tdir, metrics)
        if metrics.get("frames", 0) < 30:
            p.append("scroll:poucos frames (gesto não chegou ao app?)")
        return p


class Launch(Workload):
    """Abertura de app a frio via `am start -W`: processo morto; cache de página quente, ou frio de disco com
    `"drop_caches": true` (o único jeito de read_ahead / scheduler de I/O aparecerem numa abertura)."""
    kind = "launch"
    metrics = {"launch_ms_median": LOWER, "launch_ms_mean": LOWER, "launch_ms_max": LOWER,
               "launch_cold_n": HIGHER}
    default_primary = "launch_ms_median"

    def device_args(self, run_dir: str) -> list[str]:
        return ["launch", self.spec["package"], self.spec["component"], str(self.spec.get("reps", 5)),
                "1" if self.spec.get("drop_caches") else "0"]

    def est_seconds(self) -> float:
        return int(self.spec.get("reps", 5)) * (7.0 if self.spec.get("drop_caches") else 5.0)

    def parse(self, tdir: Path, ctx: dict) -> dict[str, float]:
        kv = parse.read_kv(tdir / "metrics")
        times = [parse.num(v) for k, v in kv.items() if k.endswith("_total_ms")]
        times = [t for t in times if t is not None]
        out: dict[str, float] = {}
        if times:
            out["launch_ms_median"] = median(times)
            out["launch_ms_mean"] = sum(times) / len(times)
            out["launch_ms_max"] = max(times)
        out["launch_cold_n"] = float(sum(1 for k, v in kv.items() if k.endswith("_state") and v == "COLD"))
        return out

    def validity(self, tdir: Path, metrics: dict[str, float]) -> list[str]:
        p = super().validity(tdir, metrics)
        if "launch_ms_median" not in metrics:
            p.append("launch:sem TotalTime")
        return p


class AppSwitch(Workload):
    """Memória: abre N apps e revisita todos. Revisita que volta COLD = app expulso da RAM."""
    kind = "appswitch"
    metrics = {"revisit_cold_n": LOWER, "revisit_ms_median": LOWER, "first_ms_median": LOWER}
    default_primary = "revisit_cold_n"

    def device_args(self, run_dir: str) -> list[str]:
        return ["appswitch"] + list(self.spec["components"])

    def est_seconds(self) -> float:
        return len(self.spec["components"]) * 2 * 4.0

    def parse(self, tdir: Path, ctx: dict) -> dict[str, float]:
        kv = parse.read_kv(tdir / "metrics")
        p1 = [parse.num(v) for k, v in kv.items() if k.startswith("p1_") and k.endswith("_total_ms")]
        p2 = [parse.num(v) for k, v in kv.items() if k.startswith("p2_") and k.endswith("_total_ms")]
        out: dict[str, float] = {
            "revisit_cold_n": float(sum(1 for k, v in kv.items()
                                        if k.startswith("p2_") and k.endswith("_state") and v == "COLD")),
        }
        p1 = [x for x in p1 if x is not None]
        p2 = [x for x in p2 if x is not None]
        if p1:
            out["first_ms_median"] = median(p1)
        if p2:
            out["revisit_ms_median"] = median(p2)
        return out


class Io(Workload):
    """Bloco: leitura aleatória O_DIRECT (só leitura), sequencial bufferizada ou mista."""
    kind = "io"
    metrics = {"rd_p50_us": LOWER, "rd_p90_us": LOWER, "rd_p99_us": LOWER, "rd_p999_us": LOWER,
               "rd_iops": HIGHER, "rd_mbps": HIGHER, "seq_mbps": HIGHER, "seq_p99_us": LOWER}
    default_primary = "rd_p99_us"
    IO_FILE = "io.bin"
    W_FILE = "io_w.bin"

    def device_args(self, run_dir: str) -> list[str]:
        from . import DEVICE_HOME
        mode = self.spec.get("mode", "rand")
        dev = self.spec.get("dev", "/dev/block/sda")
        dur = str(self.spec.get("duration_s", 10))
        if mode == "rand":
            return ["io", "rand", "--dev", dev, "--bs", str(self.spec.get("bs", 4096)),
                    "--qd", str(self.spec.get("qd", 1)), "--duration-s", dur]
        if mode == "seq":
            return ["io", "seq", "--file", f"{DEVICE_HOME}/{self.IO_FILE}", "--bs", str(self.spec.get("bs", 131072))]
        if mode == "mixed":
            return ["io", "mixed", "--dev", dev, "--wfile", f"{DEVICE_HOME}/{self.W_FILE}",
                    "--wmb", str(self.spec.get("wmb", 64)), "--wrate-mbs", str(self.spec.get("wrate_mbs", 40)),
                    "--duration-s", dur]
        raise ValueError(f"io.mode desconhecido: {mode}")

    def setup_lines(self, run_dir: str) -> list[str]:
        from . import DEVICE_HOME
        if self.spec.get("mode") == "seq":
            mb = int(self.spec.get("file_mb", 256))
            return [f'"$KL_BIN/kl_io" prep --file {DEVICE_HOME}/{self.IO_FILE} --mb {mb} >> "$KL_RUN/log" 2>&1']
        return []

    def est_seconds(self) -> float:
        return float(self.spec.get("duration_s", 10)) + 3

    def parse(self, tdir: Path, ctx: dict) -> dict[str, float]:
        kv = parse.read_kv(tdir / "metrics")
        out: dict[str, float] = {}
        for k in self.metrics:
            v = parse.num(kv.get(k))
            if v is not None:
                out[k] = v
        return out


class Game(Workload):
    """Jogo aberto pelo dono numa cena estável; o kernlab só mede (TimeStats da SurfaceView)."""
    kind = "game"
    metrics = {"ts_fps": HIGHER, "ts_janky_pct": LOWER, "ts_p2p_p50_ms": LOWER, "ts_p2p_p95_ms": LOWER,
               "ts_p2p_p99_ms": LOWER, "ts_p2p_ge33ms_pct": LOWER, "ts_frames": HIGHER,
               "ts_hitch_n": LOWER, "ts_hitch_pct": LOWER, "ts_display_hz": HIGHER}
    default_primary = "ts_p2p_p95_ms"

    def device_args(self, run_dir: str) -> list[str]:
        return ["game", self.spec["package"], str(self.spec.get("duration_s", 40))]

    def est_seconds(self) -> float:
        return float(self.spec.get("duration_s", 40)) + 3

    def parse(self, tdir: Path, ctx: dict) -> dict[str, float]:
        ts = tdir / "timestats.txt"
        if not ts.is_file():
            return {}
        return parse.timestats_for(ts.read_text(encoding="utf-8", errors="replace"), self.spec["package"])

    def validity(self, tdir: Path, metrics: dict[str, float]) -> list[str]:
        p = super().validity(tdir, metrics)
        if metrics.get("ts_frames", 0) < 100:
            p.append("game:poucos frames")
        return p


class Idle(Workload):
    """Só telemetria: potência e temperatura com o aparelho parado na tela atual."""
    kind = "idle"
    metrics = {}
    default_primary = "pbat_mw"

    def device_args(self, run_dir: str) -> list[str]:
        return ["idle", str(self.spec.get("duration_s", 60))]

    def est_seconds(self) -> float:
        return float(self.spec.get("duration_s", 60)) + 1

    def parse(self, tdir: Path, ctx: dict) -> dict[str, float]:
        return {}


WORKLOADS = {c.kind: c for c in (Burst, Scroll, Launch, AppSwitch, Io, Game, Idle)}


def make(spec: dict) -> Workload:
    kind = spec.get("kind")
    if kind not in WORKLOADS:
        raise ValueError(f"workload desconhecido: {kind} (conhecidos: {', '.join(WORKLOADS)})")
    return WORKLOADS[kind](spec)


def shell_line(args: list[str]) -> str:
    return " ".join(shlex.quote(a) for a in args)
