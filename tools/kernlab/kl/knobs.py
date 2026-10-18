"""Registro único (SSOT) dos nós que o kernlab pode LER e ESCREVER.

Nada fora daqui é lido ou escrito no aparelho. A lista nasce do inventário real do M54
(2026-10-18), não de um catálogo genérico: cada caminho existiu, foi lido sem incidente e
tem domínio de valores restrito. Leitura às cegas de sysfs já derrubou o kernel deste
aparelho (`ems/multi_load` -> UBSAN -> panic), por isso também há uma lista negra que
nenhum caminho pode tocar, mesmo que alguém o registre por engano.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

# Caminhos que NUNCA podem ser lidos nem escritos (prova: /proc/auto_comment 2026-10-18,
# "PC is at multi_load_read+0x410/0x418 [ems]", processo `head`).
READ_DENYLIST = (
    re.compile(r"/ems/multi_load"),
    re.compile(r"/sys/kernel/ems(/|$)"),
    re.compile(r"/cpufreq_limit(/|$)"),
    re.compile(r"/sys/kernel/xperf(/|$)"),
    re.compile(r"/exynos_pm_qos(/|$)"),
)

EMS_TUNER = "/sys/devices/platform/ems/emstune/aio_tuner"
EMS_CURSET = "/sys/devices/platform/ems/emstune/cur_set"

# Ordem dos cgroups nas tabelas do emstune (cur_set) e no índice "group" do aio_tuner.
EMS_GROUPS = ("root", "fg", "bg", "ta", "rt", "sy", "syb", "n-h", "c-d", "mg")
EMS_MODES = ("normal", "power-scenario", "performance", "game", "balance", "camera")
EMS_LEVELS = 3
COREGROUPS = (0, 4)
CPU_FREQS = {
    0: (533000, 672000, 768000, 864000, 960000, 1056000, 1152000, 1248000, 1344000, 1440000,
        1536000, 1632000, 1728000, 1824000, 1920000, 2002000),
    4: (533000, 672000, 768000, 864000, 960000, 1056000, 1152000, 1248000, 1344000, 1440000,
        1536000, 1632000, 1728000, 1824000, 1920000, 2016000, 2112000, 2208000, 2304000, 2400000),
}
GPU_FREQS = (221000, 351000, 455000, 552000, 650000, 754000, 845000, 949000)

KINDS = ("int", "pct", "enum", "bracket", "cpulist", "str", "ems")


class KnobError(ValueError):
    pass


@dataclass(frozen=True)
class Knob:
    id: str
    section: str
    access: str                  # file | ems | setting
    target: str                  # caminho, chave "func,modo,nível[,idx]" ou "namespace chave"
    kind: str
    domain: tuple = ()           # int: (min, max); enum/bracket: valores; pct: (min, max)
    risk: str = "low"
    workloads: tuple = ()
    note: str = ""
    ems_field: tuple = field(default=())  # (função, índice) usados pelo parser do cur_set

    def validate(self, value: str) -> str:
        v = str(value).strip()
        if self.kind == "int" or self.kind == "ems":
            try:
                n = int(v)
            except ValueError as e:
                raise KnobError(f"{self.id}: '{v}' não é inteiro") from e
            lo, hi = self.domain
            if not lo <= n <= hi:
                raise KnobError(f"{self.id}: {n} fora do domínio seguro [{lo}, {hi}]")
            return str(n)
        if self.kind == "pct":
            if v == "max":
                return v
            try:
                f = float(v)
            except ValueError as e:
                raise KnobError(f"{self.id}: '{v}' não é porcentagem") from e
            lo, hi = self.domain
            if not lo <= f <= hi:
                raise KnobError(f"{self.id}: {f} fora do domínio seguro [{lo}, {hi}]")
            return f"{f:.2f}"
        if self.kind in ("enum", "bracket"):
            if v not in self.domain:
                raise KnobError(f"{self.id}: '{v}' não está em {self.domain}")
            return v
        if self.kind == "cpulist":
            cpus = parse_cpulist(v)
            if not cpus or not cpus <= set(range(8)):
                raise KnobError(f"{self.id}: lista de CPUs inválida '{v}'")
            return format_cpulist(cpus)
        if self.kind == "str":
            if self.domain and v not in self.domain:
                raise KnobError(f"{self.id}: '{v}' não está em {self.domain}")
            return v
        raise KnobError(f"{self.id}: tipo desconhecido {self.kind}")

    def normalize(self, raw: str | None) -> str | None:
        """Leitura crua do aparelho -> forma canônica comparável com validate()."""
        if raw is None:
            return None
        r = raw.strip()
        if self.kind == "pct":
            if r == "max":
                return "max"
            try:
                return f"{float(r):.2f}"
            except ValueError:
                return r
        if self.kind == "int":
            m = re.match(r"-?\d+", r)
            return str(int(m.group(0))) if m else r
        if self.kind == "bracket":
            m = re.search(r"\[([^\]]+)\]", r)
            return m.group(1) if m else r
        if self.kind == "cpulist":
            try:
                return format_cpulist(parse_cpulist(r))
            except ValueError:
                return r
        return r


def parse_cpulist(s: str) -> set[int]:
    out: set[int] = set()
    s = s.strip()
    if not s:
        return out
    for part in s.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            a, b = part.split("-", 1)
            lo, hi = int(a), int(b)
            if hi < lo:
                raise ValueError(part)
            out.update(range(lo, hi + 1))
        else:
            out.add(int(part))
    return out


def format_cpulist(cpus: set[int]) -> str:
    xs = sorted(cpus)
    out = []
    i = 0
    while i < len(xs):
        j = i
        while j + 1 < len(xs) and xs[j + 1] == xs[j] + 1:
            j += 1
        out.append(str(xs[i]) if i == j else f"{xs[i]}-{xs[j]}")
        i = j + 1
    return ",".join(out)


# ---------------------------------------------------------------------------- registro
_REG: dict[str, Knob] = {}

WL_CPU = ("burst", "scroll", "launch", "game")
WL_IO = ("io", "launch")
WL_MEM = ("appswitch", "launch")


def _add(k: Knob) -> None:
    for rx in READ_DENYLIST:
        if rx.search(k.target):
            raise KnobError(f"{k.id}: caminho na lista negra ({k.target})")
    if k.id in _REG:
        raise KnobError(f"duplicado: {k.id}")
    if k.kind not in KINDS:
        raise KnobError(f"{k.id}: kind {k.kind}")
    _REG[k.id] = k


def _f(id, section, path, kind, domain=(), risk="low", workloads=WL_CPU, note=""):
    _add(Knob(id, section, "file", path, kind, tuple(domain), risk, tuple(workloads), note))


# --- CPU
for _p, _cpu in (("policy0", 0), ("policy4", 4)):
    _base = f"/sys/devices/system/cpu/cpufreq/{_p}"
    _f(f"cpufreq.{_p}.max", "CPU", f"{_base}/scaling_max_freq", "int",
       (CPU_FREQS[_cpu][0], CPU_FREQS[_cpu][-1]),
       note="Teto da policy. Usado como controle positivo (capar e exigir que o efeito apareça).")
    _f(f"cpufreq.{_p}.governor", "CPU", f"{_base}/scaling_governor", "str",
       ("energy_aware", "schedutil"), risk="medium",
       note="energy_aware é o governor EMS da Samsung; schedutil desliga o ego.")

# --- Stune & Uclamp (cpuctl) + sysctls de uclamp
CPUCTL_GROUPS = ("top-app", "foreground", "foreground_window", "background", "system-background",
                 "system", "rt", "camera-daemon", "nnapi-hal", "dex2oat", "midground")
for _g in CPUCTL_GROUPS:
    _d = f"/dev/cpuctl/{_g}"
    _f(f"cpuctl.{_g}.uclamp.min", "Stune & Uclamp", f"{_d}/cpu.uclamp.min", "pct", (0.0, 100.0),
       note="Piso de utilização do grupo (0-100%). No EMS age em frequência e no fit do ontime.")
    _f(f"cpuctl.{_g}.uclamp.max", "Stune & Uclamp", f"{_d}/cpu.uclamp.max", "pct", (0.0, 100.0))
    _f(f"cpuctl.{_g}.uclamp.latency_sensitive", "Stune & Uclamp",
       f"{_d}/cpu.uclamp.latency_sensitive", "int", (0, 1),
       note="No EMS só desliga o sync-wakeup para a CPU de quem acorda (core.c).")
    _f(f"cpuctl.{_g}.shares", "Stune & Uclamp", f"{_d}/cpu.shares", "int", (64, 10240))
_f("sched.util_clamp_min", "Stune & Uclamp", "/proc/sys/kernel/sched_util_clamp_min", "int", (0, 1024))
_f("sched.util_clamp_max", "Stune & Uclamp", "/proc/sys/kernel/sched_util_clamp_max", "int", (0, 1024))
_f("sched.util_clamp_min_rt_default", "Stune & Uclamp",
   "/proc/sys/kernel/sched_util_clamp_min_rt_default", "int", (0, 1024),
   note="Boost padrão de tarefas RT (1024 = RT sempre na freq máxima). Samsung deixa 0.")

# --- CPU Sets
CPUSET_GROUPS = ("top-app", "foreground", "background", "system-background", "restricted",
                 "camera-daemon", "dexopt", "sf")
for _g in CPUSET_GROUPS:
    _f(f"cpuset.{_g}.cpus", "CPU Sets", f"/dev/cpuset/{_g}/cpus", "cpulist",
       note="Subconjunto de 0-7 (0-3 A55, 4-7 A78). Nunca o cpuset raiz.")

# --- Scheduler (sysctls)
_f("sched.energy_aware", "Scheduler", "/proc/sys/kernel/sched_energy_aware", "int", (0, 1))
_f("sched.pelt_multiplier", "Scheduler", "/proc/sys/kernel/sched_pelt_multiplier", "int", (1, 4),
   note="1=32ms, 2=16ms, 4=8ms de meia-vida do PELT (só 1, 2 ou 4 são aceitos).")
_f("sched.schedstats", "Scheduler", "/proc/sys/kernel/sched_schedstats", "int", (0, 1))
_f("sched.child_runs_first", "Scheduler", "/proc/sys/kernel/sched_child_runs_first", "int", (0, 1))
_f("sched.rr_timeslice_ms", "Scheduler", "/proc/sys/kernel/sched_rr_timeslice_ms", "int", (1, 100))
_f("kernel.timer_migration", "Scheduler", "/proc/sys/kernel/timer_migration", "int", (0, 1))

# --- CPU Idle
_f("cpuidle.governor", "CPU Idle", "/sys/devices/system/cpu/cpuidle/current_governor", "str",
   ("menu", "teo", "halo"), risk="medium", note="halo é o governor de idle do EMS.")
for _cpu in range(8):
    _f(f"cpuidle.cpu{_cpu}.state1.disable", "CPU Idle",
       f"/sys/devices/system/cpu/cpu{_cpu}/cpuidle/state1/disable", "int", (0, 1), risk="medium",
       note="Desliga o C2 desta CPU: corta latência de saída do idle, custa energia e calor.")

# --- GPU
_f("gpu.max_clock", "GPU", "/sys/kernel/gpu/gpu_max_clock", "int", (GPU_FREQS[0], GPU_FREQS[-1]),
   workloads=("game", "scroll"))
_f("gpu.min_clock", "GPU", "/sys/kernel/gpu/gpu_min_clock", "int", (GPU_FREQS[0], GPU_FREQS[-1]),
   workloads=("game", "scroll"))
_f("gpu.governor", "GPU", "/sys/kernel/gpu/gpu_governor", "str",
   ("Default", "Interactive", "Joint", "Static", "Booster", "Dynamic"), risk="medium",
   workloads=("game", "scroll"))
_f("gpu.poweroff_delay", "GPU", "/sys/kernel/gpu/gpu_poweroff_delay", "int", (0, 500),
   workloads=("game", "scroll"))
_f("gpu.cl_boost_disable", "GPU", "/sys/kernel/gpu/gpu_cl_boost_disable", "int", (0, 1),
   workloads=("game",))

# --- Memory
_VM = {
    "swappiness": (0, 200), "vfs_cache_pressure": (1, 500), "page-cluster": (0, 3),
    "watermark_scale_factor": (10, 1000), "watermark_boost_factor": (0, 15000),
    "extra_free_kbytes": (0, 262144), "compaction_proactiveness": (0, 100),
    "stat_interval": (1, 10), "dirty_expire_centisecs": (100, 3000),
    "dirty_writeback_centisecs": (100, 3000), "dirty_background_bytes": (4194304, 268435456),
    "dirty_bytes": (8388608, 536870912),
}
for _k, _dom in _VM.items():
    _f(f"vm.{_k}", "Memory", f"/proc/sys/vm/{_k}", "int", _dom, workloads=WL_MEM)
_f("vm.min_free_kbytes", "Memory", "/proc/sys/vm/min_free_kbytes", "int", (4096, 65536),
   risk="medium", workloads=WL_MEM)
_f("mm.lru_gen.enabled", "Memory", "/sys/kernel/mm/lru_gen/enabled", "str",
   ("0x0000", "0x0001", "0x0003", "0x0007"), risk="medium", workloads=WL_MEM,
   note="MGLRU. 0x0003 é o de fábrica.")
_f("mm.lru_gen.min_ttl_ms", "Memory", "/sys/kernel/mm/lru_gen/min_ttl_ms", "int", (0, 5000),
   workloads=WL_MEM)
_f("mm.thp.enabled", "Memory", "/sys/kernel/mm/transparent_hugepage/enabled", "bracket",
   ("always", "madvise", "never"), risk="medium", workloads=WL_MEM)
_f("mm.vmscan.mem_boost_mode", "Memory", "/sys/kernel/mm/vmscan/mem_boost_mode", "int", (0, 3),
   workloads=WL_MEM, note="Samsung: reclaim agressivo temporário.")
_f("zram.balance_ratio", "Memory", "/sys/module/zram/parameters/zram_balance_ratio", "int",
   (0, 100), workloads=WL_MEM)

# --- I/O (sda = LUN de dados)
_Q = "/sys/block/sda/queue"
_f("io.sda.scheduler", "I/O", f"{_Q}/scheduler", "bracket",
   ("mq-deadline", "kyber", "bfq", "ssg", "none"), workloads=WL_IO)
_f("io.sda.read_ahead_kb", "I/O", f"{_Q}/read_ahead_kb", "int", (16, 4096), workloads=WL_IO)
_f("io.sda.nr_requests", "I/O", f"{_Q}/nr_requests", "int", (4, 256), workloads=WL_IO)
_f("io.sda.rq_affinity", "I/O", f"{_Q}/rq_affinity", "int", (0, 2), workloads=WL_IO)
_f("io.sda.nomerges", "I/O", f"{_Q}/nomerges", "int", (0, 2), workloads=WL_IO)
_f("io.sda.add_random", "I/O", f"{_Q}/add_random", "int", (0, 1), workloads=WL_IO)
_f("io.sda.iostats", "I/O", f"{_Q}/iostats", "int", (0, 1), workloads=WL_IO)
_f("io.sda.max_sectors_kb", "I/O", f"{_Q}/max_sectors_kb", "int", (64, 1024), workloads=WL_IO)
for _k, _dom in {"read_expire": (50, 2000), "write_expire": (500, 10000),
                 "max_write_starvation": (1, 16), "front_merges": (0, 1)}.items():
    _f(f"io.sda.ssg.{_k}", "I/O", f"{_Q}/iosched/{_k}", "int", _dom, workloads=WL_IO,
       note="Só existe com o escalonador ssg ativo.")
_U = "/sys/devices/platform/13500000.ufs"
_f("ufs.clkgate_enable", "I/O", f"{_U}/clkgate_enable", "int", (0, 1), risk="medium", workloads=WL_IO)
_f("ufs.clkgate_delay_ms", "I/O", f"{_U}/clkgate_delay_ms", "int", (10, 1000), workloads=WL_IO)
_f("ufs.auto_hibern8", "I/O", f"{_U}/auto_hibern8", "int", (0, 10000), risk="medium",
   workloads=WL_IO)

# --- Display (settings do framework, não nós de kernel). O FUSAO framework.sh também escreve
# estes por perfil (game 120/120, balanced 120/60, battery 60/60): a deriva é detectada.
for _k, _note in (("peak_refresh_rate", "Teto do refresh. 60.0 trava a tela em 60 Hz."),
                  ("min_refresh_rate", "Piso do refresh. 120.0 com teto 120.0 = 120 Hz fixo, sem cair no adaptativo.")):
    _add(Knob(f"display.{_k}", "Display", "setting", f"system {_k}", "str", ("60.0", "120.0"),
              workloads=("scroll", "game", "launch"), note=_note))

# --- Ajuste de tela usado pelo próprio runner (não é nó de kernel)
_add(Knob("settings.screen_off_timeout", "Runner", "setting", "system screen_off_timeout", "int",
          (5000, 2147483647), workloads=(), note="O runner mantém a tela acesa e restaura no fim."))

# --- EMS emstune (Samsung). Não aparece no FKM. Escrita via aio_tuner, prova via cur_set.
# (nome, id da função, escopo, domínio seguro, seção do cur_set, coluna)
EMS_FUNCS = (
    ("freqboost", 3, "cpu_group", (-50, 100)),
    ("pelt_boost", 4, "cpu", (-50, 100)),
    ("htask_boost", 5, "cpu", (0, 300)),
    ("dis_buck_share", 6, "cpu", (0, 1)),
    ("pelt_margin", 7, "cpu", (-50, 200)),
    ("rate_limit", 9, "cpu", (0, 32)),
    ("ontime_en", 15, "group", (0, 1)),
    ("tex_prio", 20, "single", (0, 139)),
    ("ecs_busy_ratio", 22, "cpu", (0, 100)),
    ("ntu_ratio", 23, "group", (0, 200)),
    ("frt_active_ratio", 24, "cpu", (0, 100)),
    ("cpuidle_expired_ratio", 25, "cpu", (0, 400)),
    ("support_uclamp", 26, "single", (0, 1)),
    ("gsc_en", 27, "group", (0, 1)),
    ("should_spread", 30, "single", (0, 1)),
)


def _ems_add(name, func, scope, dom, mode, level):
    tag = f"m{mode}l{level}"
    if scope == "single":
        _add(Knob(f"ems.{name}.{tag}", "EMS (Samsung)", "ems", f"{func},{mode},{level}", "ems", dom,
                  "medium", WL_CPU, ems_field=(name, None)))
    elif scope == "cpu":
        for cg in COREGROUPS:
            _add(Knob(f"ems.{name}.{tag}.cg{cg}", "EMS (Samsung)", "ems", f"{func},{mode},{level},{cg}",
                      "ems", dom, "medium", WL_CPU, ems_field=(name, cg)))
    elif scope == "group":
        for gi, g in enumerate(EMS_GROUPS):
            _add(Knob(f"ems.{name}.{tag}.{g}", "EMS (Samsung)", "ems", f"{func},{mode},{level},{gi}",
                      "ems", dom, "medium", WL_CPU, ems_field=(name, g)))
    elif scope == "cpu_group":
        for cg in COREGROUPS:
            for gi, g in enumerate(EMS_GROUPS):
                _add(Knob(f"ems.{name}.{tag}.cg{cg}.{g}", "EMS (Samsung)", "ems",
                          f"{func},{mode},{level},{cg},{gi}", "ems", dom, "medium", WL_CPU,
                          ems_field=(name, (cg, g))))


for _mode in range(len(EMS_MODES)):
    for _level in range(EMS_LEVELS):
        for _name, _func, _scope, _dom in EMS_FUNCS:
            _ems_add(_name, _func, _scope, _dom, _mode, _level)


def get(knob_id: str) -> Knob:
    try:
        return _REG[knob_id]
    except KeyError:
        raise KnobError(f"nó não registrado: {knob_id}") from None


def all_knobs() -> list[Knob]:
    return list(_REG.values())


def file_knobs() -> list[Knob]:
    return [k for k in _REG.values() if k.access == "file"]


def ems_mode_level(knob: Knob) -> tuple[int, int]:
    parts = knob.target.split(",")
    return int(parts[1]), int(parts[2])
