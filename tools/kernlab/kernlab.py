#!/usr/bin/env python3
"""kernlab — tuning de kernel que se prova medindo (Galaxy M54 / Exynos 1380).

  python tools/kernlab/kernlab.py connect 192.168.1.75:40747
  python tools/kernlab/kernlab.py inventory [--section "Stune & Uclamp"]
  python tools/kernlab/kernlab.py list
  python tools/kernlab/kernlab.py run tools/kernlab/experiments/controle-aa.json [--dry-run]
  python tools/kernlab/kernlab.py attach <run_id>      # retoma após queda do Wi-Fi
  python tools/kernlab/kernlab.py stop                 # pede aborto (restaura tudo)
  python tools/kernlab/kernlab.py restore <run_id>     # restauração manual do plano
  python tools/kernlab/kernlab.py report tools/kernlab/runs/<run_id>
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from kl import DEVICE_HOME  # noqa: E402
from kl import adb as A  # noqa: E402
from kl import analysis, emstune, plan, report  # noqa: E402
from kl import knobs as K  # noqa: E402

RUNS = HERE / "runs"
EXPERIMENTS = HERE / "experiments"
MARK_CURSET = "=====KL_CURSET====="
MARK_ETABLE = "=====KL_ETABLE====="
MARK_ENV = "=====KL_ENV====="
MARK_END = "=====KL_END====="


def say(msg: str) -> None:
    print(msg, flush=True)


# ------------------------------------------------------------------ aparelho
def deploy(dev: A.Device) -> None:
    dev.shell(f"mkdir -p {DEVICE_HOME}/bin {DEVICE_HOME}/runs")
    dev.push(HERE / "device" / "agent.sh", f"{DEVICE_HOME}/agent.sh")
    for b in sorted((HERE / "device" / "bin").iterdir()):
        dev.push(b, f"{DEVICE_HOME}/bin/{b.name}")
    dev.root(f"chmod 755 {DEVICE_HOME}/bin/*; chmod 644 {DEVICE_HOME}/agent.sh\n")


def _read_script(kn_list: list[K.Knob], want_curset: bool) -> str:
    lines = []
    for kn in kn_list:
        for rx in K.READ_DENYLIST:
            if rx.search(kn.target):
                raise K.KnobError(f"leitura proibida: {kn.target}")
        if kn.access == "file":
            lines.append(f"if [ -e '{kn.target}' ]; then printf 'K\\t%s\\t%s\\n' '{kn.id}' \"$(head -n 1 '{kn.target}' 2>/dev/null | tr -d '\\r')\"; "
                         f"else printf 'K\\t%s\\t@absent\\n' '{kn.id}'; fi")
        elif kn.access == "setting":
            lines.append(f"printf 'K\\t%s\\t%s\\n' '{kn.id}' \"$(settings get {kn.target})\"")
    if want_curset:
        lines += [f"echo '{MARK_CURSET}'", f"cat {K.EMS_CURSET}", f"echo '{MARK_END}'"]
    lines += [f"echo '{MARK_ETABLE}'", "cat /sys/devices/platform/ems/energy_table/energy_table", f"echo '{MARK_END}'"]
    lines += [f"echo '{MARK_ENV}'", f". {DEVICE_HOME}/agent.sh", "KL_TZ_BIG=$(kl_zone BIG); KL_TZ_LIT=$(kl_zone LITTLE); KL_TZ_G3D=$(kl_zone G3D)",
              "kl_env", f"echo '{MARK_END}'"]
    return "\n".join(lines) + "\n"


def _section(out: str, mark: str) -> str:
    if mark not in out:
        return ""
    return out.split(mark, 1)[1].split(MARK_END, 1)[0].strip("\n")


def discover_sets(dev: A.Device, needed: set[tuple[int, int]], local: Path | None = None) -> dict:
    """Captura o cur_set de conjuntos do emstune que só ativam no toque (ex.: m0l1), fazendo um
    scroll curto na lista de apps enquanto o kl_sample vigia o cabeçalho a cada 100 ms."""
    from kl import posse
    d = f"{DEVICE_HOME}/disc"
    script = (f"rm -rf {d}; mkdir -p {d}\n"
              "am start -W -a android.settings.APPLICATION_SETTINGS >/dev/null 2>&1; sleep 2\n"
              f"cat > {d}/g.txt <<'KLG'\n{posse.GESTURE}KLG\n"
              f"{DEVICE_HOME}/bin/kl_sample -i 100 -d 9 --curset {K.EMS_CURSET} {d} up=/proc/uptime >/dev/null 2>&1 &\n"
              "SP=$!\n"
              f"{DEVICE_HOME}/bin/kl_touch {d}/g.txt >/dev/null 2>&1\n"
              "wait $SP\n"
              f"for f in {d}/curset_seen_*; do echo \"=====KL_FILE $f\"; cat \"$f\"; done\n")
    out = dev.root_read(script, timeout=60)
    found: dict[tuple[int, int], dict] = {}
    for chunk in out.split("=====KL_FILE ")[1:]:
        name, _, text = chunk.partition("\n")
        cs = emstune.parse(text)
        if cs["mode"] is not None:
            found[(cs["mode"], cs["level"])] = cs
            if local is not None:
                (local / f"curset_preflight_m{cs['mode']}l{cs['level']}.txt").write_text(text, encoding="utf-8")
    missing = needed - set(found)
    if missing:
        say(f"conjuntos EMS não observados no scroll de descoberta: {sorted(missing)}")
    return found


def preflight(dev: A.Device, knob_ids: list[str]) -> dict:
    kns = [K.get(k) for k in knob_ids]
    want_curset = any(k.access == "ems" for k in kns)
    out = dev.root(_read_script([k for k in kns if k.access != "ems"], want_curset), timeout=60)
    raw = {}
    for ln in out.splitlines():
        if ln.startswith("K\t"):
            parts = ln.split("\t", 2)
            raw[parts[1]] = None if parts[2] == "@absent" else parts[2]
    env = {}
    for ln in _section(out, MARK_ENV).splitlines():
        if "=" in ln:
            k, v = ln.split("=", 1)
            env[k] = v
    cs_text = _section(out, MARK_CURSET)
    return {"raw": raw, "curset_text": cs_text, "curset": emstune.parse(cs_text) if cs_text else None,
            "energy_table": _section(out, MARK_ETABLE), "env": env}


# ------------------------------------------------------------------ comandos
def cmd_connect(a) -> int:
    A.save_serial(a.serial)
    dev = A.Device(a.serial)
    dev.connect()
    dev.ensure()
    say(f"ok {a.serial} (salvo em {A.DEVICE_FILE.name})")
    return 0


def cmd_inventory(a) -> int:
    dev = A.Device()
    dev.ensure()
    deploy(dev)
    kns = [k for k in K.all_knobs() if k.access != "ems" and (not a.section or k.section == a.section)]
    pf = preflight(dev, [k.id for k in kns] + ["ems.support_uclamp.m0l0"])
    cs = pf["curset"]
    by_sec: dict[str, list] = {}
    for k in kns:
        by_sec.setdefault(k.section, []).append(k)
    for sec, items in by_sec.items():
        say(f"\n## {sec}")
        for k in items:
            v = pf["raw"].get(k.id)
            say(f"  {k.id:44s} {('ausente' if v is None else v)[:60]:60s} [{k.risk}]")
    if cs and not a.section:
        say(f"\n## EMS (Samsung) — conjunto ativo m{cs['mode']}l{cs['level']} "
            f"({K.EMS_MODES[cs['mode']] if cs['mode'] is not None else '?'})")
        for (name, idx), v in sorted(cs["fields"].items(), key=lambda kv: str(kv[0])):
            say(f"  {name}[{idx}] = {v}")
    if a.json:
        Path(a.json).write_text(json.dumps(pf, indent=1, default=str), encoding="utf-8")
    return 0


def cmd_list(a) -> int:
    for p in sorted(EXPERIMENTS.glob("*.json")):
        try:
            e = plan.load(p)
            say(f"{p.name:34s} {e.workload['kind']:9s} {e.hypothesis[:90]}")
        except Exception as ex:  # noqa: BLE001
            say(f"{p.name:34s} INVÁLIDO: {ex}")
    return 0


def _poll(dev: A.Device, run_id: str, est: float) -> str:
    rdir = f"{DEVICE_HOME}/runs/{run_id}"
    last = ""
    fails = 0
    t0 = time.time()
    while True:
        try:
            out = dev.root_read(f"cat {rdir}/status 2>/dev/null; [ -f {rdir}/DONE ] && echo KL_DONE; "
                           f"[ -f {rdir}/ABORTED ] && cat {rdir}/ABORTED; exit 0\n", timeout=30)
            fails = 0
        except A.AdbError as e:
            fails += 1
            if fails >= 8:
                say(f"\nconexão perdida ({e}). O experimento CONTINUA no aparelho e se restaura sozinho.\n"
                    f"Com a porta nova: kernlab.py connect IP:PORTA && kernlab.py attach {run_id}")
                return "lost"
            dev.connect()
            time.sleep(10)
            continue
        st = out.strip().splitlines()[0] if out.strip() else "?"
        if st != last:
            el = time.time() - t0
            say(f"  [{el:5.0f}s / ~{est:.0f}s] {st}")
            last = st
        if "KL_DONE" in out:
            return "done"
        if "reason=" in out:
            return "aborted"
        time.sleep(8)


def _collect(dev: A.Device, run_id: str) -> Path:
    rdir = f"{DEVICE_HOME}/runs/{run_id}"
    dev.root(f"chmod -R a+rX {rdir}\n")
    RUNS.mkdir(exist_ok=True)
    dev.pull(rdir, RUNS)
    return RUNS / run_id


def _analyze(local: Path) -> int:
    rd = analysis.load_run(local)
    md = report.render(rd)
    (local / "report.md").write_text(md, encoding="utf-8")
    prim = rd.exp.primary or analysis.W.make(rd.exp.workload).default_primary
    say(f"\n== {rd.exp.name}: {rd.status}; válidos {sum(t.valid for t in rd.trials)}/{len(rd.trials)}; "
        f"restauração {'OK' if rd.restore_ok else 'FALHOU'}")
    for r in analysis.results(rd):
        if r.metric == prim or r.metric in ("pbat_mw", "mj_per_frame"):
            say(f"   {r.arm:>6s} {r.metric:18s} {r.effect_pct:+7.1f}%  IC[{r.ci_lo_pct:+.1f},{r.ci_hi_pct:+.1f}]  "
                f"p={r.p_value:.3f}  {r.verdict}")
    for t in rd.trials:
        if not t.valid:
            say(f"   t{t.index} excluído: {'; '.join(t.problems)[:160]}")
    say(f"relatório: {local / 'report.md'}")
    return 0


def _stop_pending(dev: A.Device) -> bool:
    return "KL_STOP" in dev.root_read(f"[ -f {DEVICE_HOME}/STOP ] && echo KL_STOP\n", timeout=20)


def cmd_run(a) -> int:
    return run_experiment(Path(a.experiment), a.rounds, a.dry_run)


def run_experiment(path: Path, rounds: int | None = None, dry_run: bool = False) -> int:
    exp = plan.load(path)
    if rounds:
        exp.rounds = rounds
        plan.validate(exp)
    dev = A.Device()
    dev.ensure()
    if _stop_pending(dev):
        say("STOP pendente no aparelho: nada roda até `kernlab.py stop --clear`")
        return 5
    deploy(dev)
    pf = preflight(dev, exp.knob_ids() + [plan.SCREEN])
    env = pf["env"]
    say(f"aparelho: bateria {env.get('batt_level')}% {env.get('batt_status')} {int(env.get('batt_temp', 0)) / 10:.1f}C, "
        f"SoC {int(env.get('t_big') or 0) / 1000:.0f}C, tela {env.get('wakefulness')}, foco {env.get('focus') or '-'}, "
        f"emstune '{env.get('emstune')}'")
    if int(env.get("batt_level") or 0) < 30:
        say("bateria < 30%: recusado (o portão aborta em 15% e o desenho precisa de todos os blocos)")
        return 2
    if exp.screen == "on" and (env.get("wakefulness") != "Awake" or not env.get("focus")):
        say("tela apagada ou travada: recusado. Com a tela apagada a Samsung troca de regime (emstune "
            "power-scenario, top-app uclamp.min 0.00) e o experimento mediria outra coisa. Destrave e rode de novo.")
        return 6
    if env.get("batt_status", "").lower().startswith("charg"):
        say("AVISO: carregando — potência da bateria será descartada; temperatura sobe")
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + exp.name
    local = RUNS / run_id
    local.mkdir(parents=True, exist_ok=True)
    needed = plan.ems_sets_needed(exp)
    active = {(pf["curset"]["mode"], pf["curset"]["level"])} if pf["curset"] else set()
    seen = discover_sets(dev, needed - active, local) if needed - active else {}
    base = plan.baseline_from_preflight(exp, pf["raw"], pf["curset"], seen)
    rdir = f"{DEVICE_HOME}/runs/{run_id}"
    script, assets, manifest = plan.compile_plan(exp, base, run_id, rdir)
    (local / "plan.sh").write_text(script, encoding="utf-8", newline="\n")
    (local / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False), encoding="utf-8")
    (local / "energy_table.txt").write_text(pf["energy_table"], encoding="utf-8")
    if pf["curset_text"]:
        (local / "curset_preflight.txt").write_text(pf["curset_text"], encoding="utf-8")
    for name, content in assets.items():
        (local / name).write_text(content, encoding="utf-8", newline="\n")
    say(f"plano: {len(manifest['schedule'])} trials, ~{manifest['est_seconds'] / 60:.0f} min -> {local}")
    if dry_run:
        say("dry-run: nada executado")
        return 0
    dev.shell(f"mkdir -p {rdir}")
    for name in ["plan.sh", *assets]:
        dev.push(local / name, f"{rdir}/{name}")
    try:
        # a sessão do su do KernelSU segura o adb enquanto o filho destacado vive; o filho
        # segue rodando, então o timeout curto é esperado e a prova é o lock no aparelho
        dev.root(f"cd {rdir} && setsid nohup sh plan.sh > run.out 2>&1 < /dev/null &\n", timeout=8)
    except A.AdbError:
        pass
    time.sleep(2)
    lock = dev.root_read(f"cat {DEVICE_HOME}/lock 2>/dev/null; cat {rdir}/ABORTED 2>/dev/null\n", timeout=20)
    if run_id not in lock:
        say(f"o plano não subiu no aparelho: {lock.strip() or 'sem lock'}")
        return 3
    say(f"rodando no aparelho: {run_id}")
    state = _poll(dev, run_id, manifest["est_seconds"])
    if state == "lost":
        return 4
    _collect(dev, run_id)
    return _analyze(local)


def cmd_attach(a) -> int:
    dev = A.Device()
    dev.ensure()
    local = RUNS / a.run_id
    man = json.loads((local / "manifest.json").read_text(encoding="utf-8")) if (local / "manifest.json").is_file() else {}
    state = _poll(dev, a.run_id, man.get("est_seconds", 0))
    if state == "lost":
        return 4
    _collect(dev, a.run_id)
    return _analyze(local)


def cmd_stop(a) -> int:
    dev = A.Device()
    dev.ensure()
    if a.clear:
        dev.root(f"rm -f {DEVICE_HOME}/STOP\n")
        say("STOP removido: novos runs liberados")
        return 0
    dev.root(f"touch {DEVICE_HOME}/STOP\n")
    say("STOP pedido: o run atual aborta no próximo trial e restaura tudo; nenhum run novo começa "
        "até `kernlab.py stop --clear`")
    return 0


def cmd_queue(a) -> int:
    """Roda vários experimentos em sequência com a tela mantida acesa ENTRE eles: restaurar o
    timeout de tela ao fim de cada run apagava a tela na hora (o último toque real tinha mais
    de 30 min) e o run seguinte começava em Dozing (2026-10-18)."""
    dev = A.Device()
    dev.ensure()
    if a.wait_screen:
        say("esperando a tela ficar acesa e destravada (lockscreen seguro: só o dono destrava)...")
        t0 = time.time()
        while True:
            st = dev.root_read("dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'; "
                               "dumpsys activity activities | grep -m1 topResumedActivity\n", timeout=30)
            if "mWakefulness=Awake" in st and "topResumedActivity" in st:
                say(f"tela ok após {time.time() - t0:.0f}s; começo em 20 s (tempo de largar o aparelho)")
                time.sleep(20)
                break
            if time.time() - t0 > 3600:
                say("1 h sem tela destravada: desisti")
                return 6
            time.sleep(15)
    original = dev.root_read(f"settings get {K.get(plan.SCREEN).target}\n").strip()
    dev.root(f"settings put {K.get(plan.SCREEN).target} 2147483647\n")
    (RUNS / ".queue_screen_timeout").parent.mkdir(exist_ok=True)
    (RUNS / ".queue_screen_timeout").write_text(original, encoding="utf-8")
    say(f"fila de {len(a.experiments)}; timeout de tela {original} -> nunca (restaura no fim)")
    codes = {}
    try:
        for e in a.experiments:
            say(f"\n##### {Path(e).stem} {datetime.now():%H:%M:%S}")
            codes[e] = run_experiment(Path(e), a.rounds)
            if codes[e] in (4, 5, 6):
                say(f"fila interrompida ({Path(e).stem} rc={codes[e]})")
                break
    finally:
        try:
            dev.ensure()
            dev.root(f"settings put {K.get(plan.SCREEN).target} {original}\n")
            say(f"\ntimeout de tela restaurado para {original}")
        except A.AdbError as ex:
            say(f"\nNÃO restaurei o timeout de tela ({ex}); valor original: {original}")
    say("fila: " + ", ".join(f"{Path(k).stem}={v}" for k, v in codes.items()))
    return 0


def cmd_restore(a) -> int:
    dev = A.Device()
    dev.ensure()
    rdir = f"{DEVICE_HOME}/runs/{a.run_id}"
    out = dev.root(f". {DEVICE_HOME}/agent.sh; KL_RUN={rdir}; "
                   f"grep -q '\tems\t' $KL_RUN/knobs.tsv && KL_HAS_EMS=1; kl_restore; cat $KL_RUN/restore.tsv\n")
    say(out)
    return 0


def cmd_report(a) -> int:
    return _analyze(Path(a.run_dir))


POSSE_AMPLO = {
    "cpuctl.top-app.uclamp.min": "20", "cpuctl.top-app.uclamp.latency_sensitive": "1",
    "cpuctl.foreground.uclamp.min": "10", "cpuctl.background.uclamp.max": "50", "cpuctl.top-app.shares": "2048",
    "cpuset.background.cpus": "0-1", "cpuset.foreground.cpus": "0-7", "cpuset.system-background.cpus": "0-1",
    "sched.util_clamp_min_rt_default": "128", "vm.swappiness": "100", "vm.vfs_cache_pressure": "80",
    "io.sda.read_ahead_kb": "256", "io.sda.nr_requests": "128", "gpu.poweroff_delay": "100",
    "display.peak_refresh_rate": "120.0", "display.min_refresh_rate": "120.0",
    "ems.freqboost.m0l0.cg0.ta": "20", "ems.freqboost.m0l0.cg4.ta": "20",
    "ems.pelt_margin.m0l0.cg4": "40", "ems.support_uclamp.m0l0": "1",
}


def cmd_posse(a) -> int:
    from kl import posse
    sets = dict(POSSE_AMPLO) if a.preset == "amplo" else {}
    for s in a.set or []:
        k, _, v = s.partition("=")
        sets[k.strip()] = v.strip()
    if not sets:
        say("nada para testar: use --preset amplo e/ou --set nó=valor")
        return 2
    exp = plan.Experiment(name="posse", workload={"kind": "idle"}, arms={"base": {k: plan.BASE for k in sets}, "teste": sets},
                          allow_medium=True)
    plan.validate(exp)
    dev = A.Device()
    dev.ensure()
    if _stop_pending(dev):
        say("STOP pendente: `kernlab.py stop --clear`")
        return 5
    lock = dev.root_read(f"cat {DEVICE_HOME}/lock 2>/dev/null\n").strip()
    if lock:
        say(f"aparelho ocupado por outro run: {lock}")
        return 3
    deploy(dev)
    pf = preflight(dev, list(sets) + [plan.SCREEN])
    if pf["env"].get("wakefulness") != "Awake" or not pf["env"].get("focus"):
        say("tela apagada/travada: destrave e rode de novo (os eventos precisam da tela)")
        return 6
    base = plan.baseline_from_preflight(exp, pf["raw"], pf["curset"])
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S") + "-posse"
    rdir = f"{DEVICE_HOME}/runs/{run_id}"
    local = RUNS / run_id
    local.mkdir(parents=True, exist_ok=True)
    (local / "plan.sh").write_text(posse.compile_posse(sets, base, run_id), encoding="utf-8", newline="\n")
    (local / "gesture.txt").write_text(posse.GESTURE, encoding="utf-8", newline="\n")
    (local / "sets.json").write_text(json.dumps({"sets": sets, "baseline": base.write}, indent=1), encoding="utf-8")
    dev.shell(f"mkdir -p {rdir}")
    for name in ("plan.sh", "gesture.txt"):
        dev.push(local / name, f"{rdir}/{name}")
    try:
        dev.root(f"cd {rdir} && setsid nohup sh plan.sh > run.out 2>&1 < /dev/null &\n", timeout=8)
    except A.AdbError:
        pass
    say(f"posse: {len(sets)} nós, eventos {', '.join(posse.EVENTS)} (~2 min)")
    if _poll(dev, run_id, 120) == "lost":
        return 4
    _collect(dev, run_id)
    rows = posse.table(local, sets)
    tags = [t for t, _ in rows[0][2]] if rows else []
    md = ["# kernlab — posse dos nós", "", f"Run `{run_id}`. Valor gravado e relido depois de cada evento.", "",
          "| nó | base | pedido | " + " | ".join(t.split("_", 1)[1] for t in tags) + " | veredito |",
          "|---" * (len(tags) + 4) + "|"]
    for kid, want, seen, verdict in rows:
        md.append(f"| `{kid}` | {base.write[kid]} | {want} | " + " | ".join(str(g) for _, g in seen) + f" | **{verdict}** |")
    restored = (local / "restore.tsv").is_file()
    md += ["", f"Restauração ao fim: {'feita (restore.tsv)' if restored else 'AUSENTE'}"]
    (local / "posse.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    for kid, want, seen, verdict in rows:
        say(f"  {kid:42s} {base.write[kid]:>8s} -> {want:<8s} {verdict}")
    say(f"tabela: {local / 'posse.md'}")
    return 0


def cmd_summary(a) -> int:
    """Uma linha por (run, braço): veredito da métrica primária + custo em potência/energia."""
    rows = ["| run | experimento | braço vs base | métrica | efeito | IC 95% | veredito | pbat_mw | válidos |",
            "|---|---|---|---|---|---|---|---|---|"]
    for man in sorted(RUNS.glob("*/manifest.json")):
        local = man.parent
        if not ((local / "DONE").exists() or (local / "ABORTED").exists()):
            continue
        try:
            rd = analysis.load_run(local)
        except Exception as ex:  # noqa: BLE001
            say(f"{local.name}: ilegível ({ex})")
            continue
        prim = rd.exp.primary or analysis.W.make(rd.exp.workload).default_primary
        res = analysis.results(rd)
        nv = f"{sum(t.valid for t in rd.trials)}/{len(rd.trials)}"
        for arm in rd.exp.arm_names[1:]:
            r = next((x for x in res if x.arm == arm and x.metric == prim), None)
            p = next((x for x in res if x.arm == arm and x.metric == "pbat_mw"), None)
            if r is None:
                continue
            rows.append(f"| {local.name[:15]} | {rd.exp.name} | {arm} vs {rd.exp.baseline_arm} | `{prim}` | "
                        f"{r.effect_pct:+.1f}% | [{r.ci_lo_pct:+.1f}, {r.ci_hi_pct:+.1f}] | **{r.verdict}** | "
                        f"{(f'{p.effect_pct:+.1f}%' if p else '—')} | {nv} |")
    text = "# kernlab — resumo de todos os runs\n\n" + "\n".join(rows) + "\n"
    (RUNS / "SUMMARY.md").write_text(text, encoding="utf-8")
    say(text)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="kernlab", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("connect"); p.add_argument("serial"); p.set_defaults(fn=cmd_connect)
    p = sub.add_parser("inventory"); p.add_argument("--section"); p.add_argument("--json"); p.set_defaults(fn=cmd_inventory)
    p = sub.add_parser("list"); p.set_defaults(fn=cmd_list)
    p = sub.add_parser("run"); p.add_argument("experiment"); p.add_argument("--rounds", type=int)
    p.add_argument("--dry-run", action="store_true"); p.set_defaults(fn=cmd_run)
    p = sub.add_parser("attach"); p.add_argument("run_id"); p.set_defaults(fn=cmd_attach)
    p = sub.add_parser("stop"); p.add_argument("--clear", action="store_true"); p.set_defaults(fn=cmd_stop)
    p = sub.add_parser("queue"); p.add_argument("experiments", nargs="+"); p.add_argument("--rounds", type=int)
    p.add_argument("--wait-screen", action="store_true")
    p.set_defaults(fn=cmd_queue)
    p = sub.add_parser("restore"); p.add_argument("run_id"); p.set_defaults(fn=cmd_restore)
    p = sub.add_parser("report"); p.add_argument("run_dir"); p.set_defaults(fn=cmd_report)
    p = sub.add_parser("summary"); p.set_defaults(fn=cmd_summary)
    p = sub.add_parser("posse"); p.add_argument("--preset", choices=["amplo"]); p.add_argument("--set", action="append")
    p.set_defaults(fn=cmd_posse)
    a = ap.parse_args(argv)
    try:
        return a.fn(a)
    except (A.AdbError, K.KnobError) as e:
        say(f"erro: {e}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
