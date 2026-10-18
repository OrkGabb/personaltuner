"""Testes offline do kernlab (sem aparelho): parsers sobre dumps REAIS do M54, compilador de
plano, validação de trial e a estatística com controles sintéticos.

  python -m unittest discover -s tools/kernlab/tests -v
"""
from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from kl import analysis, emstune, parse, plan, stats  # noqa: E402
from kl import knobs as K  # noqa: E402
from kl import workloads as W  # noqa: E402

FIX = HERE / "fixtures"


def fx(name: str) -> str:
    return (FIX / name).read_text(encoding="utf-8")


class TestParsers(unittest.TestCase):
    def test_curset_level2_is_boost(self):
        # capturado logo após `am start`: a Samsung sobe o emstune para o nível 2 no launch,
        # e nesse nível support_uclamp=1 e o ontime do top-app desliga
        cs = emstune.parse(fx("curset_l2.txt"))
        self.assertEqual((cs["mode"], cs["level"]), (0, 2))
        self.assertEqual(cs["fields"][("support_uclamp", None)], 1)
        self.assertEqual(cs["fields"][("ontime_en", "ta")], 0)

    def test_curset_real(self):
        cs = emstune.parse(fx("curset_l0.txt"))
        self.assertEqual((cs["mode"], cs["level"]), (0, 0))
        f = cs["fields"]
        self.assertEqual(f[("pelt_margin", 0)], 25)
        self.assertEqual(f[("pelt_margin", 4)], 25)
        self.assertEqual(f[("rate_limit", 4)], 4)
        self.assertEqual(f[("htask_boost", 0)], 150)
        self.assertEqual(f[("freqboost", (0, "ta"))], 5)
        self.assertEqual(f[("freqboost", (4, "fg"))], 0)
        self.assertEqual(f[("ontime_en", "ta")], 1)
        self.assertEqual(f[("ontime_en", "fg")], 0)
        self.assertEqual(f[("support_uclamp", None)], 0)
        self.assertEqual(f[("should_spread", None)], 0)
        self.assertEqual(f[("frt_active_ratio", 0)], 50)
        self.assertEqual(f[("frt_active_ratio", 4)], 100)
        self.assertEqual(f[("ecs_busy_ratio", 4)], 70)
        self.assertEqual(f[("cpuidle_expired_ratio", 4)], 100)
        self.assertEqual(f[("ntu_ratio", "bg")], 100)
        self.assertEqual(f[("gsc_en", "ta")], 1)
        self.assertEqual(f[("tex_prio", None)], 0)

    def test_every_ems_knob_resolves_in_real_curset(self):
        # o registro e o parser não podem divergir: todo nó EMS do conjunto ativo tem valor
        for name, ml in (("curset_l0.txt", (0, 0)), ("curset_l2.txt", (0, 2))):
            cs = emstune.parse(fx(name))
            missing = [k.id for k in K.all_knobs()
                       if k.access == "ems" and K.ems_mode_level(k) == ml and emstune.value_of(cs, k.ems_field) is None]
            self.assertEqual(missing, [], name)

    def test_energy_table(self):
        et = parse.parse_energy_table(fx("energy_table.txt"))
        self.assertEqual(et[0][533000], 13.0)
        self.assertEqual(et[0][2002000], 146.0)
        self.assertEqual(et[4][533000], 82.0)
        self.assertIn(1824000, et[4])
        self.assertAlmostEqual(parse.tis_energy_mj("0:533000:100,4:960000:10", et), 100 * 0.01 * 13 + 10 * 0.01 * 184)
        self.assertIsNone(parse.tis_energy_mj("0:999999:1", et))

    def test_gfxinfo_real(self):
        g = parse.parse_gfxinfo(fx("gfxinfo.txt"))
        self.assertGreater(g["frames"], 100)
        self.assertIn("janky_pct", g)
        self.assertIn("ft_p99_ms", g)
        self.assertIn("gpu_p50_ms", g)

    def test_timestats_real(self):
        t = parse.timestats_for(fx("timestats.txt"), "com.android.settings")
        self.assertAlmostEqual(t["ts_fps"], 61.963, places=2)
        self.assertEqual(t["ts_frames"], 245)
        self.assertEqual(t["ts_p2p_p50_ms"], 16.0)
        self.assertEqual(t["ts_display_hz"], 60.0)
        self.assertEqual(parse.timestats_for(fx("timestats.txt"), "com.nao.existe"), {})

    def test_timestats_adaptive_two_records_and_hitches(self):
        # mesma camada em dois blocos (60 e 120 Hz), como o TimeStats grava no refresh adaptativo
        def block(hz, frames, fps, hist):
            return (f"displayRefreshRate = {hz} fps\nrenderRate = {hz} fps\nuid = 1000\n"
                    f"layerName = com.x/com.x.Main#1\ntotalFrames = {frames}\njankyFrames = 0\n"
                    f"totalTimelineFrames = {frames}\naverageFPS = {fps}\n"
                    f"present2present histogram is as below:\n{hist}\n")
        txt = (block(60, 100, 55.0, "16ms=96 33ms=3 400ms=1")
               + block(120, 300, 110.0, "8ms=290 16ms=8 50ms=1 500ms=1"))
        t = parse.timestats_for(txt, "com.x")
        self.assertEqual(t["ts_frames"], 400)
        self.assertAlmostEqual(t["ts_fps"], (55 * 100 + 110 * 300) / 400)
        self.assertAlmostEqual(t["ts_display_hz"], (60 * 100 + 120 * 300) / 400)
        self.assertAlmostEqual(t["ts_frames_at_120_pct"], 75.0)
        # atrasos: 33ms@60 (3) + 16ms@120 (8) + 50ms@120 (1) = 12; pausas >=100ms não contam
        self.assertEqual(t["ts_hitch_n"], 12)
        self.assertAlmostEqual(t["ts_hitch_pct"], 100 * 12 / 398)

    def test_cpulist(self):
        self.assertEqual(K.format_cpulist(K.parse_cpulist("0,1,2,3")), "0-3")
        self.assertEqual(K.format_cpulist(K.parse_cpulist("0-2,4-7")), "0-2,4-7")
        self.assertEqual(K.get("cpuset.top-app.cpus").normalize("0-7\n"), "0-7")


class TestRegistry(unittest.TestCase):
    def test_denylist_blocks_registration(self):
        with self.assertRaises(K.KnobError):
            K._add(K.Knob("x.bad", "t", "file", "/sys/devices/platform/ems/multi_load", "int", (0, 1)))

    def test_domain(self):
        k = K.get("cpuctl.top-app.uclamp.min")
        self.assertEqual(k.validate("20"), "20.00")
        self.assertEqual(k.normalize("20.00"), "20.00")
        with self.assertRaises(K.KnobError):
            k.validate("150")
        with self.assertRaises(K.KnobError):
            K.get("sched.pelt_multiplier").validate("8")
        with self.assertRaises(K.KnobError):
            K.get("cpuset.top-app.cpus").validate("0-9")
        self.assertEqual(K.get("io.sda.scheduler").normalize("mq-deadline kyber bfq [ssg] none"), "ssg")

    def test_ems_indices_safe(self):
        # aio_tuner aceita cpu == NR_CPUS (off-by-one) e não limita group: o registro só gera 0/4 e 0..9
        for k in K.all_knobs():
            if k.access != "ems":
                continue
            parts = [int(x) for x in k.target.split(",")]
            self.assertLess(parts[1], len(K.EMS_MODES))
            self.assertLess(parts[2], K.EMS_LEVELS)
            for x in parts[3:]:
                self.assertIn(x, (0, 4) + tuple(range(10)))

    def test_medium_risk_needs_opt_in(self):
        e = plan.Experiment(name="t", workload={"kind": "burst"},
                            arms={"A": {"cpuidle.governor": "@base"}, "B": {"cpuidle.governor": "teo"}})
        with self.assertRaises(K.KnobError):
            plan.validate(e)
        e.allow_medium = True
        plan.validate(e)


def _exp(**kw) -> plan.Experiment:
    d = dict(name="t", workload={"kind": "burst", "duration_s": 5},
             arms={"A": {"cpuctl.top-app.uclamp.min": "@base"}, "B": {"cpuctl.top-app.uclamp.min": "30"}},
             rounds=3)
    d.update(kw)
    return plan.Experiment(**d)


class TestPlan(unittest.TestCase):
    def test_schedule_mirrored_and_balanced(self):
        s = plan.schedule(_exp(rounds=4))
        self.assertEqual(len(s), 16)
        for b in range(4):
            arms = [a for blk, a in s if blk == b]
            self.assertEqual(arms, arms[::-1])
            self.assertEqual(arms.count("A"), 2)
        s3 = plan.schedule(_exp(rounds=3, arms={"A": {}, "B": {}, "C": {}}))
        self.assertEqual(len(s3), 18)

    def test_compile_and_baseline(self):
        e = _exp()
        raw = {"cpuctl.top-app.uclamp.min": "1.00", plan.SCREEN: "1800000"}
        base = plan.baseline_from_preflight(e, raw, None)
        self.assertEqual(base.write["cpuctl.top-app.uclamp.min"], "1.00")
        script, assets, man = plan.compile_plan(e, base, "rid", "/data/local/tmp/kernlab/runs/rid")
        self.assertIn("kl_verify_baseline", script)
        self.assertEqual(script.count("kl_measure burst"), 13)   # 12 + aquecimento descartado
        self.assertIn("kl_trial 0 'aquecimento' -1", script)
        self.assertEqual(man["intended"]["B"]["cpuctl.top-app.uclamp.min"], "30.00")
        self.assertEqual(man["intended"]["A"]["cpuctl.top-app.uclamp.min"], "1.00")
        self.assertTrue(script.rstrip().endswith("kl_end"))

    def test_absent_node_refused(self):
        e = _exp()
        with self.assertRaises(K.KnobError):
            plan.baseline_from_preflight(e, {plan.SCREEN: "1800000", "cpuctl.top-app.uclamp.min": None}, None)

    def test_ems_inactive_set_refused(self):
        cs = emstune.parse(fx("curset_l0.txt"))
        e = _exp(arms={"A": {"ems.pelt_margin.m3l0.cg4": "@base"}, "B": {"ems.pelt_margin.m3l0.cg4": "50"}},
                 allow_medium=True)
        with self.assertRaises(K.KnobError):
            plan.baseline_from_preflight(e, {plan.SCREEN: "1800000"}, cs)
        e2 = _exp(arms={"A": {"ems.pelt_margin.m0l0.cg4": "@base"}, "B": {"ems.pelt_margin.m0l0.cg4": "50"}},
                  allow_medium=True)
        b = plan.baseline_from_preflight(e2, {plan.SCREEN: "1800000"}, cs)
        self.assertEqual(b.write["ems.pelt_margin.m0l0.cg4"], "25")


class TestStats(unittest.TestCase):
    def _rows(self, n_blocks, base_mu, arm_mu, sd, seed):
        rng = np.random.default_rng(seed)
        rows = []
        for b in range(n_blocks):
            drift = rng.normal(0, sd * 2)   # deriva comum ao bloco (calor), cancelada pelo pareamento
            for arm, mu in (("A", base_mu), ("B", arm_mu), ("B", arm_mu), ("A", base_mu)):
                rows.append({"block": b, "arm": arm, "metrics": {"m": mu + drift + rng.normal(0, sd)}})
        return rows

    def test_false_positive_rate_aa(self):
        # controle negativo sintético: 200 experimentos A/A, a taxa de MELHORA/PIORA tem de ser baixa
        fp = 0
        for s in range(200):
            r = stats.analyze(self._rows(6, 100, 100, 3, s), "m", "A", "B", "lower", 3.0)
            fp += r.verdict in ("MELHORA", "PIORA")
        self.assertLessEqual(fp / 200, 0.05)

    def test_detects_real_effect(self):
        hits = 0
        for s in range(100):
            r = stats.analyze(self._rows(6, 100, 115, 3, s), "m", "A", "B", "lower", 3.0)
            hits += r.verdict == "PIORA"
        self.assertGreaterEqual(hits / 100, 0.9)

    def test_equivalence(self):
        r = stats.analyze(self._rows(12, 100, 100, 0.3, 1), "m", "A", "B", "lower", 3.0)
        self.assertTrue(r.verdict.startswith("EQUIVALENTE"), r.verdict)

    def test_exact_permutation(self):
        self.assertAlmostEqual(stats.sign_flip_p(np.array([1.0, 1.0, 1.0])), 2 / 8)

    def test_real_aa_power_is_not_a_verdict(self):
        # pbat_mw do A/A real de 2026-10-18 (4 blocos, braços idênticos): o bootstrap percentil
        # deu IC [-12.6, -3.1] e "MELHORA". Isto tem de ser INCONCLUSIVO.
        trials = [(0, "A", 841.7), (0, "A2", 616.5), (0, "A2", 708.5), (0, "A", 714.5),
                  (1, "A2", 654.7), (1, "A", 752.3), (1, "A", 758.1), (1, "A2", 701.7),
                  (2, "A", 780.9), (2, "A2", 793.5), (2, "A2", 709.2), (2, "A", 737.1),
                  (3, "A2", 695.9), (3, "A", 741.7), (3, "A", 734.8), (3, "A2", 703.4)]
        rows = [{"block": b, "arm": a, "metrics": {"pbat_mw": v}} for b, a, v in trials]
        r = stats.analyze(rows, "pbat_mw", "A", "A2", "lower", 5.0)
        self.assertEqual(r.verdict, "INCONCLUSIVO")
        self.assertLess(r.ci_lo_pct, 0)
        self.assertGreater(r.ci_hi_pct, 0)


class TestEmsMultiLevel(unittest.TestCase):
    """Nó de um nível que só ativa no toque (m0l1) é provado pela cópia que o kl_sample grava
    quando o nível aparece; sem cópia, o nível não ativou e não há o que reprovar."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        l0 = fx("curset_l0.txt")
        self.l0 = l0
        self.l1_ok = l0.replace("normal mode : level0 [mode=0 level=0]", "normal mode : level1 [mode=0 level=1]") \
                       .replace("cpu4:   0   0   0   5", "cpu4:   0   0   0  30")
        (self.tmp / "curset_pre.txt").write_text(l0, encoding="utf-8")
        (self.tmp / "curset_post.txt").write_text(l0, encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_seen_level1_verifies(self):
        (self.tmp / "curset_seen_m0l1.txt").write_text(self.l1_ok, encoding="utf-8")
        self.assertEqual(analysis.check_knobs({"ems.freqboost.m0l1.cg4.ta": "30"}, self.tmp), [])
        p = analysis.check_knobs({"ems.freqboost.m0l1.cg4.ta": "60"}, self.tmp)
        self.assertTrue(p and "durante" in p[0], p)

    def test_level1_not_seen_is_not_a_failure(self):
        self.assertEqual(analysis.check_knobs({"ems.freqboost.m0l1.cg4.ta": "30"}, self.tmp), [])

    def test_level0_still_checked_from_pre_post(self):
        self.assertEqual(analysis.check_knobs({"ems.freqboost.m0l0.cg4.ta": "5"}, self.tmp), [])
        self.assertTrue(analysis.check_knobs({"ems.freqboost.m0l0.cg4.ta": "20"}, self.tmp))

    def test_baseline_from_seen_set(self):
        e = _exp(arms={"A": {"ems.freqboost.m0l1.cg4.ta": "@base"}, "B": {"ems.freqboost.m0l1.cg4.ta": "40"}},
                 allow_medium=True)
        with self.assertRaises(K.KnobError):
            plan.baseline_from_preflight(e, {plan.SCREEN: "1800000"}, emstune.parse(self.l0))
        b = plan.baseline_from_preflight(e, {plan.SCREEN: "1800000"}, emstune.parse(self.l0),
                                         {(0, 1): emstune.parse(self.l1_ok)})
        self.assertEqual(b.write["ems.freqboost.m0l1.cg4.ta"], "30")


class TestAnalysisValidity(unittest.TestCase):
    """Um trial em que o nó não segurou, ou derivou no meio, NÃO pode entrar na estatística."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    ENV_ON = "wakefulness=Awake\nemstune=normal mode : level0 [mode=0 level=0]\nfocus=com.android.settings/.Settings\n"
    ENV_DOZE = "wakefulness=Dozing\nemstune=power-scenario mode : level0 [mode=1 level=0]\nfocus=\n"

    def _make_run(self, post_override: dict[int, str], env_override: dict[int, str] | None = None):
        e = _exp(rounds=6)
        base = plan.baseline_from_preflight(e, {"cpuctl.top-app.uclamp.min": "1.00", plan.SCREEN: "1800000"}, None)
        _, _, man = plan.compile_plan(e, base, "rid", "/x")
        man["experiment"] = e.__dict__
        (self.tmp / "manifest.json").write_text(json.dumps(man), encoding="utf-8")
        (self.tmp / "energy_table.txt").write_text(fx("energy_table.txt"), encoding="utf-8")
        (self.tmp / "env_begin").write_text(self.ENV_ON, encoding="utf-8")
        for i, (blk, arm) in enumerate(man["schedule"], start=1):
            t = self.tmp / f"t{i}"
            t.mkdir()
            (t / "env_pre").write_text(self.ENV_ON, encoding="utf-8")
            (t / "env_post").write_text((env_override or {}).get(i, self.ENV_ON), encoding="utf-8")
            want = man["intended"][arm]["cpuctl.top-app.uclamp.min"]
            (t / "meta").write_text(f"trial={i}\narm={arm}\nblock={blk}\nrc=0\n", encoding="utf-8")
            (t / "END").write_text("", encoding="utf-8")
            (t / "knobs_pre.tsv").write_text(f"cpuctl.top-app.uclamp.min\t{want}\n", encoding="utf-8")
            (t / "knobs_post.tsv").write_text(f"cpuctl.top-app.uclamp.min\t{post_override.get(i, want)}\n",
                                              encoding="utf-8")
            lat = 13000 if arm == "A" else 5500
            (t / "metrics").write_text(f"completed=1400\nlat_p95_us={lat}\ntis=4:960000:100\n", encoding="utf-8")
        (self.tmp / "restore.tsv").write_text("cpuctl.top-app.uclamp.min\t1.00\t1.00\n"
                                              f"{plan.SCREEN}\t1800000\t1800000\n", encoding="utf-8")
        (self.tmp / "DONE").write_text("", encoding="utf-8")
        return analysis.load_run(self.tmp)

    def test_two_blocks_never_conclude(self):
        rows = [{"block": b, "arm": a, "metrics": {"m": v}} for b in (0, 1)
                for a, v in (("A", 100.0), ("B", 50.0), ("B", 50.0), ("A", 100.0))]
        self.assertEqual(stats.analyze(rows, "m", "A", "B", "lower", 3.0).verdict, "INCONCLUSIVO")

    def test_all_valid(self):
        rd = self._make_run({})
        self.assertTrue(all(t.valid for t in rd.trials))
        self.assertTrue(rd.restore_ok)
        r = [x for x in analysis.results(rd) if x.metric == "lat_p95_us"][0]
        self.assertEqual(r.verdict, "MELHORA")

    def test_screen_off_mid_trial_invalidates(self):
        rd = self._make_run({}, {5: self.ENV_DOZE})
        bad = [t for t in rd.trials if not t.valid]
        self.assertEqual([t.index for t in bad], [5])
        self.assertTrue(any("tela mudou" in p for p in bad[0].problems))
        self.assertTrue(any("emstune" in p for p in bad[0].problems))

    def test_launch_drop_caches_reaches_device(self):
        warm = W.make({"kind": "launch", "package": "p", "component": "p/.A", "reps": 3})
        cold = W.make({"kind": "launch", "package": "p", "component": "p/.A", "reps": 3, "drop_caches": True})
        self.assertEqual(warm.device_args("/x")[-1], "0")
        self.assertEqual(cold.device_args("/x")[-1], "1")
        e = _exp(workload={"kind": "launch", "package": "p", "component": "p/.A", "reps": 3, "drop_caches": True})
        script, _, _ = plan.compile_plan(e, plan.baseline_from_preflight(
            e, {"cpuctl.top-app.uclamp.min": "1.00", plan.SCREEN: "1800000"}, None), "rid", "/x")
        self.assertIn("kl_measure launch p p/.A 3 1\n", script)

    def test_gate_floor_mode(self):
        raw = {"cpuctl.top-app.uclamp.min": "1.00", plan.SCREEN: "1800000"}
        e = _exp()
        script, _, _ = plan.compile_plan(e, plan.baseline_from_preflight(e, raw, None), "rid", "/x")
        self.assertIn("kl_gate 45 37 300 min\n", script)
        g = _exp(gate={"soc_c": 70, "batt_c": 40, "timeout_s": 120, "floor": "start"})
        script, _, _ = plan.compile_plan(g, plan.baseline_from_preflight(g, raw, None), "rid", "/x")
        self.assertIn("kl_gate 70 40 120 start\n", script)
        self.assertNotIn(" min\n", script)
        with self.assertRaises(K.KnobError):
            plan.validate(_exp(gate={"floor": "fisico"}))

    def test_plan_keeps_screen_on_never(self):
        e = _exp()
        base = plan.baseline_from_preflight(e, {"cpuctl.top-app.uclamp.min": "1.00", plan.SCREEN: "1800000"}, None)
        script, _, _ = plan.compile_plan(e, base, "rid", "/x")
        self.assertIn(f"kl_set {plan.SCREEN} 2147483647", script)

    def test_curset_with_crlf_and_leading_blank(self):
        cs = emstune.parse("\r\n" + fx("curset_l0.txt").replace("\n", "\r\n"))
        self.assertEqual((cs["mode"], cs["level"]), (0, 0))
        self.assertEqual(cs["fields"][("pelt_margin", 4)], 25)

    def test_drift_invalidates(self):
        rd = self._make_run({2: "1.00"})   # trial 2 é do braço B: pediu 30, terminou em 1
        bad = [t for t in rd.trials if not t.valid]
        self.assertEqual([t.index for t in bad], [2])
        self.assertIn("derivou", bad[0].problems[0])


if __name__ == "__main__":
    unittest.main()
