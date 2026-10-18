// kl_burst — carga periódica no formato de um frame (thread de UI -> thread de render),
// medida ponta a ponta. É o banco de prova dos nós de scheduler/frequência: uclamp,
// cpuset, sched_*, emstune. Cada "frame" k nasce num tick absoluto t_k = t0 + k*P:
//   estágio 1 acorda no tick, executa work1 iterações e sinaliza o estágio 2;
//   estágio 2 acorda pelo futex, executa work2 iterações e marca o frame como pronto.
// Latência do frame = pronto - t_k. Se o frame anterior ainda não terminou no tick,
// o frame atual é descartado (como um frame que nem começou) e conta como perda.
// O trabalho é fixo em ITERAÇÕES, não em tempo: frequência e posicionamento de CPU
// aparecem como latência, que é o que um frame real sente.
//
//   kl_burst run   [opções]     -> resumo key=value no stdout (+ CSV por frame com --raw)
//   kl_burst calib --cpu C      -> ns por iteração na CPU C (escolher work1/work2)
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/futex.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define MAX_CLUSTERS 4
#define MAX_FREQS 40

typedef struct {
  long period_us;
  long work1;
  long work2;
  long mem_kb;
  long mem_steps;
  double duration_s;
  double warmup_s;
  const char *cgroup;
  int noise;
  const char *noise_cgroup;
  int noise_duty;
  int fifo;
  int big_first;
  const char *raw_out;
} cfg_t;

typedef struct {
  int64_t tick_ns;
  int64_t post_ns;
  int32_t wake1_us;
  int32_t wake2_us;
  int32_t lat_us;
  int16_t cpu1;
  int16_t cpu2;
  uint8_t dropped;
  uint8_t done;
} rec_t;

typedef struct {
  int ncl;
  int cl_cpu[MAX_CLUSTERS];
  int nf[MAX_CLUSTERS];
  long freq[MAX_CLUSTERS][MAX_FREQS];
  long ticks[MAX_CLUSTERS][MAX_FREQS];
} tis_t;

static cfg_t g_cfg;
static rec_t *g_rec;
static long g_nframes;
static long g_warm_frames;
static _Atomic int g_posted = -1;
static _Atomic int g_done = -1;
static _Atomic int g_running = 1;
static uint32_t *g_chain;
static size_t g_chain_n;
static volatile uint64_t g_sink;
static tis_t g_tis_start[2];
static tis_t g_tis_end[2];

static int64_t now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void ns_to_ts(int64_t ns, struct timespec *ts) {
  ts->tv_sec = (time_t)(ns / 1000000000LL);
  ts->tv_nsec = (long)(ns % 1000000000LL);
}

static long futex_wait(_Atomic int *addr, int expected, long timeout_ns) {
  struct timespec ts;
  ts.tv_sec = timeout_ns / 1000000000L;
  ts.tv_nsec = timeout_ns % 1000000000L;
  return syscall(SYS_futex, (int *)addr, FUTEX_WAIT_PRIVATE, expected, &ts, NULL, 0);
}

static long futex_wake(_Atomic int *addr) {
  return syscall(SYS_futex, (int *)addr, FUTEX_WAKE_PRIVATE, 1, NULL, NULL, 0);
}

static uint64_t do_work(long iters, long mem_steps, uint64_t x) {
  long i;
  for (i = 0; i < iters; i++) {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
  }
  if (g_chain != NULL && mem_steps > 0) {
    uint32_t idx = (uint32_t)(x % g_chain_n);
    long s;
    for (s = 0; s < mem_steps; s++) idx = g_chain[(size_t)idx * 16];
    x += idx;
  }
  return x;
}

// Ciclo único aleatório (Sattolo) com passo de 64 bytes: cada salto é um cache miss
// dependente do anterior, então o tempo do estágio passa a depender de MIF/DSU também.
static int build_chain(long mem_kb) {
  size_t n = (size_t)mem_kb * 1024 / 64;
  size_t i;
  uint32_t *perm;
  uint64_t r = 0x9E3779B97F4A7C15ULL;
  if (n < 2) return 0;
  g_chain = (uint32_t *)calloc(n * 16, sizeof(uint32_t));
  perm = (uint32_t *)malloc(n * sizeof(uint32_t));
  if (g_chain == NULL || perm == NULL) return -1;
  for (i = 0; i < n; i++) perm[i] = (uint32_t)i;
  for (i = n - 1; i > 0; i--) {
    size_t j;
    r ^= r << 13;
    r ^= r >> 7;
    r ^= r << 17;
    j = (size_t)(r % i);
    uint32_t t = perm[i];
    perm[i] = perm[j];
    perm[j] = t;
  }
  for (i = 0; i < n; i++) g_chain[(size_t)perm[i] * 16] = perm[(i + 1) % n];
  g_chain_n = n;
  free(perm);
  return 0;
}

static int write_str(const char *path, const char *s) {
  int fd = open(path, O_WRONLY | O_CLOEXEC);
  ssize_t w;
  if (fd < 0) return -1;
  w = write(fd, s, strlen(s));
  close(fd);
  return w == (ssize_t)strlen(s) ? 0 : -1;
}

// Move o processo inteiro (todas as threads) para o cgroup nomeado nas hierarquias
// cpuset e cpu. Falha alta: um processo fora do grupo pedido mede outra coisa.
static int join_cgroup(const char *group) {
  char path[256];
  char pid[32];
  int rc = 0;
  if (group == NULL || group[0] == '\0') return 0;
  snprintf(pid, sizeof(pid), "%d", (int)getpid());
  snprintf(path, sizeof(path), "/dev/cpuset/%s/cgroup.procs", group);
  if (write_str(path, pid) != 0) {
    fprintf(stderr, "kl_burst: falha ao entrar em %s: %s\n", path, strerror(errno));
    rc = -1;
  }
  snprintf(path, sizeof(path), "/dev/cpuctl/%s/cgroup.procs", group);
  if (write_str(path, pid) != 0) {
    fprintf(stderr, "kl_burst: falha ao entrar em %s: %s\n", path, strerror(errno));
    rc = -1;
  }
  return rc;
}

static void read_tis(tis_t *t) {
  FILE *f = fopen("/proc/thread-self/time_in_state", "re");
  char line[128];
  int cl = -1;
  memset(t, 0, sizeof(*t));
  if (f == NULL) return;
  while (fgets(line, sizeof(line), f) != NULL) {
    long fr;
    long tk;
    int cpu;
    if (sscanf(line, "cpu%d", &cpu) == 1) {
      if (t->ncl >= MAX_CLUSTERS) break;
      cl = t->ncl++;
      t->cl_cpu[cl] = cpu;
      continue;
    }
    if (cl >= 0 && sscanf(line, "%ld %ld", &fr, &tk) == 2 && t->nf[cl] < MAX_FREQS) {
      t->freq[cl][t->nf[cl]] = fr;
      t->ticks[cl][t->nf[cl]] = tk;
      t->nf[cl]++;
    }
  }
  fclose(f);
}

static void set_fifo(int prio) {
  struct sched_param sp;
  if (prio <= 0) return;
  memset(&sp, 0, sizeof(sp));
  sp.sched_priority = prio;
  if (sched_setscheduler(0, SCHED_FIFO, &sp) != 0)
    fprintf(stderr, "kl_burst: SCHED_FIFO %d falhou: %s\n", prio, strerror(errno));
}

static void *stage2_main(void *arg) {
  int last = -1;
  uint64_t x = 0x1234567ULL;
  int tis_taken = 0;
  (void)arg;
  set_fifo(g_cfg.fifo);
  while (atomic_load(&g_running)) {
    int p = atomic_load(&g_posted);
    int64_t w2;
    rec_t *r;
    if (p == last) {
      futex_wait(&g_posted, last, 50000000L);
      continue;
    }
    w2 = now_ns();
    r = &g_rec[p];
    if (!tis_taken && p >= g_warm_frames) {
      read_tis(&g_tis_start[1]);
      tis_taken = 1;
    }
    r->wake2_us = (int32_t)((w2 - r->post_ns) / 1000);
    r->cpu2 = (int16_t)sched_getcpu();
    x = do_work(g_cfg.work2, g_cfg.mem_steps, x);
    r->lat_us = (int32_t)((now_ns() - r->tick_ns) / 1000);
    r->done = 1;
    last = p;
    atomic_store(&g_done, p);
  }
  read_tis(&g_tis_end[1]);
  g_sink += x;
  return NULL;
}

// Ruído de fundo: N processos no cgroup de fundo, cada um ocupado noise_duty% de cada
// janela de 10 ms. Morrem junto com o líder (PDEATHSIG), que morre junto com o kl_burst.
static void noise_main(const cfg_t *c) {
  int i;
  int n = c->noise > 64 ? 64 : c->noise;
  prctl(PR_SET_PDEATHSIG, SIGKILL);
  join_cgroup(c->noise_cgroup);
  for (i = 0; i < n; i++) {
    pid_t child = fork();
    if (child == 0) {
      uint64_t x = (uint64_t)i + 1;
      int64_t period = 10000000LL;
      int64_t busy = period * c->noise_duty / 100;
      prctl(PR_SET_PDEATHSIG, SIGKILL);
      for (;;) {
        int64_t t0 = now_ns();
        struct timespec ts;
        while (now_ns() - t0 < busy) x = do_work(2000, 0, x);
        ns_to_ts(t0 + period, &ts);
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL);
        g_sink += x;
      }
    }
  }
  for (;;) pause();
}

static int cmp_i32(const void *a, const void *b) {
  int32_t x = *(const int32_t *)a;
  int32_t y = *(const int32_t *)b;
  return (x > y) - (x < y);
}

static int32_t pct(const int32_t *sorted, long n, double q) {
  long idx;
  if (n <= 0) return -1;
  idx = (long)(q * (double)(n - 1) + 0.5);
  if (idx < 0) idx = 0;
  if (idx >= n) idx = n - 1;
  return sorted[idx];
}

static void print_dist(const char *name, int32_t *v, long n) {
  double sum = 0;
  long i;
  qsort(v, (size_t)n, sizeof(int32_t), cmp_i32);
  for (i = 0; i < n; i++) sum += v[i];
  printf("%s_p50_us=%d\n", name, pct(v, n, 0.50));
  printf("%s_p90_us=%d\n", name, pct(v, n, 0.90));
  printf("%s_p95_us=%d\n", name, pct(v, n, 0.95));
  printf("%s_p99_us=%d\n", name, pct(v, n, 0.99));
  printf("%s_p999_us=%d\n", name, pct(v, n, 0.999));
  printf("%s_max_us=%d\n", name, n > 0 ? v[n - 1] : -1);
  printf("%s_mean_us=%.1f\n", name, n > 0 ? sum / (double)n : -1.0);
}

static void print_tis(void) {
  int th;
  int first = 1;
  printf("tis=");
  for (th = 0; th < 2; th++) {
    int cl;
    for (cl = 0; cl < g_tis_end[th].ncl; cl++) {
      int f;
      for (f = 0; f < g_tis_end[th].nf[cl]; f++) {
        long d = g_tis_end[th].ticks[cl][f];
        if (cl < g_tis_start[th].ncl && f < g_tis_start[th].nf[cl]) d -= g_tis_start[th].ticks[cl][f];
        if (d <= 0) continue;
        printf("%s%d:%ld:%ld", first ? "" : ",", g_tis_end[th].cl_cpu[cl], g_tis_end[th].freq[cl][f], d);
        first = 0;
      }
    }
  }
  printf("\n");
}

static void usage(void) {
  fprintf(stderr,
          "uso: kl_burst run [--period-us 8333] [--work1 N] [--work2 N] [--mem-kb K --mem-steps S]\n"
          "                  [--duration-s 20] [--warmup-s 2] [--cgroup top-app]\n"
          "                  [--noise N --noise-cgroup background --noise-duty 50]\n"
          "                  [--fifo PRIO] [--big-first 4] [--raw arquivo.csv]\n"
          "     kl_burst calib --cpu C [--iters 200000] [--reps 40]\n");
}

static int run_main(int argc, char **argv) {
  int i;
  pid_t noise_pid = -1;
  pthread_t th2;
  int64_t t0;
  long k;
  uint64_t x = 0xABCDEFULL;
  long measured = 0;
  long dropped = 0;
  long missed = 0;
  long big1 = 0;
  long big2 = 0;
  long n1 = 0;
  int32_t *lat;
  int32_t *w1;
  int32_t *w2;
  long nl = 0;
  long nw2 = 0;
  int tis_taken = 0;

  g_cfg.period_us = 8333;
  g_cfg.work1 = 200000;
  g_cfg.work2 = 300000;
  g_cfg.duration_s = 20;
  g_cfg.warmup_s = 2;
  g_cfg.cgroup = "top-app";
  g_cfg.noise_cgroup = "background";
  g_cfg.noise_duty = 50;
  g_cfg.big_first = 4;
  for (i = 2; i < argc; i += 2) {
    const char *a = argv[i];
    const char *v = (i + 1 < argc) ? argv[i + 1] : NULL;
    if (v == NULL) { usage(); return 2; }
    if (!strcmp(a, "--period-us")) g_cfg.period_us = atol(v);
    else if (!strcmp(a, "--work1")) g_cfg.work1 = atol(v);
    else if (!strcmp(a, "--work2")) g_cfg.work2 = atol(v);
    else if (!strcmp(a, "--mem-kb")) g_cfg.mem_kb = atol(v);
    else if (!strcmp(a, "--mem-steps")) g_cfg.mem_steps = atol(v);
    else if (!strcmp(a, "--duration-s")) g_cfg.duration_s = atof(v);
    else if (!strcmp(a, "--warmup-s")) g_cfg.warmup_s = atof(v);
    else if (!strcmp(a, "--cgroup")) g_cfg.cgroup = v;
    else if (!strcmp(a, "--noise")) g_cfg.noise = atoi(v);
    else if (!strcmp(a, "--noise-cgroup")) g_cfg.noise_cgroup = v;
    else if (!strcmp(a, "--noise-duty")) g_cfg.noise_duty = atoi(v);
    else if (!strcmp(a, "--fifo")) g_cfg.fifo = atoi(v);
    else if (!strcmp(a, "--big-first")) g_cfg.big_first = atoi(v);
    else if (!strcmp(a, "--raw")) g_cfg.raw_out = v;
    else { usage(); return 2; }
  }
  if (g_cfg.period_us < 1000 || g_cfg.duration_s <= 0 || g_cfg.noise_duty < 0 || g_cfg.noise_duty > 100) {
    usage();
    return 2;
  }
  if (g_cfg.mem_kb > 0 && build_chain(g_cfg.mem_kb) != 0) {
    fprintf(stderr, "kl_burst: sem memória para --mem-kb\n");
    return 1;
  }
  g_nframes = (long)((g_cfg.duration_s + g_cfg.warmup_s) * 1e6 / (double)g_cfg.period_us);
  g_warm_frames = (long)(g_cfg.warmup_s * 1e6 / (double)g_cfg.period_us);
  g_rec = (rec_t *)calloc((size_t)g_nframes, sizeof(rec_t));
  lat = (int32_t *)calloc((size_t)g_nframes, sizeof(int32_t));
  w1 = (int32_t *)calloc((size_t)g_nframes, sizeof(int32_t));
  w2 = (int32_t *)calloc((size_t)g_nframes, sizeof(int32_t));
  if (g_rec == NULL || lat == NULL || w1 == NULL || w2 == NULL) return 1;

  if (g_cfg.noise > 0) {
    noise_pid = fork();
    if (noise_pid == 0) {
      noise_main(&g_cfg);
      _exit(0);
    }
  }
  if (join_cgroup(g_cfg.cgroup) != 0) {
    if (noise_pid > 0) {
      kill(noise_pid, SIGKILL);
      waitpid(noise_pid, NULL, 0);
    }
    return 3;
  }
  set_fifo(g_cfg.fifo);
  if (pthread_create(&th2, NULL, stage2_main, NULL) != 0) return 1;

  t0 = now_ns() + 50000000LL;
  for (k = 0; k < g_nframes; k++) {
    struct timespec ts;
    int64_t tick = t0 + k * g_cfg.period_us * 1000LL;
    int64_t wake;
    rec_t *r = &g_rec[k];
    ns_to_ts(tick, &ts);
    while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL) == EINTR) {
    }
    wake = now_ns();
    r->tick_ns = tick;
    r->wake1_us = (int32_t)((wake - tick) / 1000);
    if (!tis_taken && k >= g_warm_frames) {
      read_tis(&g_tis_start[0]);
      tis_taken = 1;
    }
    if (atomic_load(&g_done) != atomic_load(&g_posted)) {
      r->dropped = 1;
      continue;
    }
    r->cpu1 = (int16_t)sched_getcpu();
    x = do_work(g_cfg.work1, g_cfg.mem_steps, x);
    r->post_ns = now_ns();
    atomic_store(&g_posted, (int)k);
    futex_wake(&g_posted);
  }
  // espera o último frame (até 1 s) e encerra o estágio 2
  {
    int64_t deadline = now_ns() + 1000000000LL;
    while (atomic_load(&g_done) != atomic_load(&g_posted) && now_ns() < deadline) usleep(1000);
  }
  read_tis(&g_tis_end[0]);
  atomic_store(&g_running, 0);
  futex_wake(&g_posted);
  pthread_join(th2, NULL);
  if (noise_pid > 0) {
    kill(noise_pid, SIGKILL);
    waitpid(noise_pid, NULL, 0);
  }
  g_sink += x;

  for (k = g_warm_frames; k < g_nframes; k++) {
    rec_t *r = &g_rec[k];
    measured++;
    w1[n1] = r->wake1_us;
    if (r->dropped) {
      dropped++;
      missed++;
      n1++;
      continue;
    }
    if (r->cpu1 >= g_cfg.big_first) big1++;
    n1++;
    if (!r->done) {
      missed++;
      continue;
    }
    if (r->cpu2 >= g_cfg.big_first) big2++;
    w2[nw2++] = r->wake2_us;
    lat[nl++] = r->lat_us;
    if (r->lat_us > g_cfg.period_us) missed++;
  }

  if (g_cfg.raw_out != NULL) {
    FILE *f = fopen(g_cfg.raw_out, "we");
    if (f != NULL) {
      fprintf(f, "k,tick_us,wake1_us,wake2_us,lat_us,cpu1,cpu2,dropped\n");
      for (k = g_warm_frames; k < g_nframes; k++) {
        rec_t *r = &g_rec[k];
        fprintf(f, "%ld,%lld,%d,%d,%d,%d,%d,%d\n", k, (long long)((r->tick_ns - t0) / 1000), r->wake1_us,
                r->done ? r->wake2_us : -1, r->done ? r->lat_us : -1, r->cpu1, r->done ? r->cpu2 : -1,
                r->dropped);
      }
      fclose(f);
    }
  }

  printf("kl_burst=1\n");
  printf("period_us=%ld\nwork1=%ld\nwork2=%ld\nmem_kb=%ld\nmem_steps=%ld\n", g_cfg.period_us, g_cfg.work1,
         g_cfg.work2, g_cfg.mem_kb, g_cfg.mem_steps);
  printf("cgroup=%s\nnoise=%d\nfifo=%d\n", g_cfg.cgroup, g_cfg.noise, g_cfg.fifo);
  printf("frames=%ld\ndropped=%ld\ncompleted=%ld\n", measured, dropped, nl);
  printf("miss_pct=%.3f\n", measured > 0 ? 100.0 * (double)missed / (double)measured : -1.0);
  printf("drop_pct=%.3f\n", measured > 0 ? 100.0 * (double)dropped / (double)measured : -1.0);
  printf("big1_pct=%.2f\n", (n1 - dropped) > 0 ? 100.0 * (double)big1 / (double)(n1 - dropped) : -1.0);
  printf("big2_pct=%.2f\n", nl > 0 ? 100.0 * (double)big2 / (double)nl : -1.0);
  print_dist("lat", lat, nl);
  print_dist("wake1", w1, n1);
  print_dist("wake2", w2, nw2);
  print_tis();
  return 0;
}

static int calib_main(int argc, char **argv) {
  int cpu = -1;
  long iters = 200000;
  int reps = 40;
  int i;
  double best = 1e30;
  double *v;
  uint64_t x = 1;
  cpu_set_t set;
  for (i = 2; i + 1 < argc; i += 2) {
    if (!strcmp(argv[i], "--cpu")) cpu = atoi(argv[i + 1]);
    else if (!strcmp(argv[i], "--iters")) iters = atol(argv[i + 1]);
    else if (!strcmp(argv[i], "--reps")) reps = atoi(argv[i + 1]);
  }
  if (cpu < 0 || iters <= 0 || reps <= 0) {
    usage();
    return 2;
  }
  CPU_ZERO(&set);
  CPU_SET(cpu, &set);
  if (sched_setaffinity(0, sizeof(set), &set) != 0) {
    fprintf(stderr, "kl_burst: afinidade cpu%d falhou: %s\n", cpu, strerror(errno));
    return 1;
  }
  v = (double *)calloc((size_t)reps, sizeof(double));
  if (v == NULL) return 1;
  for (i = 0; i < reps; i++) {
    int64_t a = now_ns();
    x = do_work(iters, 0, x);
    v[i] = (double)(now_ns() - a) / (double)iters;
    if (v[i] < best) best = v[i];
  }
  g_sink += x;
  printf("cpu=%d\niters=%ld\nns_per_iter_min=%.4f\nns_per_iter_last=%.4f\n", cpu, iters, best, v[reps - 1]);
  free(v);
  return 0;
}

int main(int argc, char **argv) {
  if (argc >= 2 && !strcmp(argv[1], "run")) return run_main(argc, argv);
  if (argc >= 2 && !strcmp(argv[1], "calib")) return calib_main(argc, argv);
  usage();
  return 2;
}
