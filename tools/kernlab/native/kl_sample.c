// kl_sample — amostrador de telemetria sem fork: mantém os nós abertos e relê com pread.
// Cada argumento é nome=caminho; o primeiro inteiro do conteúdo é a amostra.
//   kl_sample [-i 250] [-d 30] [--power ibat vbat] nome=caminho ...
// Termina no fim de -d ou em SIGTERM/SIGINT e imprime, por nome, mean/min/max/n.
// Com --power, calcula também pbat_mw = -(ibat mA) * (vbat uV) / 1e6 por amostra
// (no M54 a corrente de descarga é negativa e vem em mA; a tensão vem em uV).
// Com --curset ARQ DIR, a cada amostra relê o cabeçalho do cur_set do emstune e, na primeira
// vez que um conjunto (modo, nível) aparece, grava o texto inteiro em DIR/curset_seen_mMlL.txt:
// é a única prova possível dos parâmetros de um nível que só fica ativo durante o toque.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define MAX_SERIES 48

typedef struct {
  char name[48];
  int fd;
  double sum;
  double min;
  double max;
  double last;
  long n;
} series_t;

static volatile sig_atomic_t g_stop = 0;

static void on_signal(int s) {
  (void)s;
  g_stop = 1;
}

static int64_t now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int read_int(int fd, double *out) {
  char buf[96];
  ssize_t r = pread(fd, buf, sizeof(buf) - 1, 0);
  char *p;
  char *end;
  long long v;
  if (r <= 0) return -1;
  buf[r] = '\0';
  p = buf;
  while (*p != '\0' && *p != '-' && (*p < '0' || *p > '9')) p++;
  if (*p == '\0') return -1;
  v = strtoll(p, &end, 10);
  if (end == p) return -1;
  *out = (double)v;
  return 0;
}

static char g_cs_buf[16384];

static void curset_watch(int fd, const char *dir, unsigned char seen[8][8]) {
  ssize_t r = pread(fd, g_cs_buf, sizeof(g_cs_buf) - 1, 0);
  char *p;
  int mode;
  int level;
  char path[512];
  int out;
  if (r <= 0) return;
  g_cs_buf[r] = '\0';
  p = strstr(g_cs_buf, "[mode=");
  if (p == NULL || sscanf(p, "[mode=%d level=%d]", &mode, &level) != 2) return;
  if (mode < 0 || mode > 7 || level < 0 || level > 7 || seen[mode][level]) return;
  seen[mode][level] = 1;
  snprintf(path, sizeof(path), "%s/curset_seen_m%dl%d.txt", dir, mode, level);
  out = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
  if (out < 0) return;
  if (write(out, g_cs_buf, (size_t)r) != r) fprintf(stderr, "kl_sample: escrita parcial em %s\n", path);
  close(out);
}

static void add(series_t *s, double v) {
  if (s->n == 0 || v < s->min) s->min = v;
  if (s->n == 0 || v > s->max) s->max = v;
  s->sum += v;
  s->last = v;
  s->n++;
}

static void print_series(const series_t *s) {
  if (s->n == 0) {
    printf("%s_n=0\n", s->name);
    return;
  }
  printf("%s_mean=%.3f\n%s_min=%.0f\n%s_max=%.0f\n%s_last=%.0f\n%s_n=%ld\n", s->name, s->sum / (double)s->n, s->name,
         s->min, s->name, s->max, s->name, s->last, s->name, s->n);
}

int main(int argc, char **argv) {
  series_t s[MAX_SERIES];
  series_t power;
  int ns = 0;
  long interval_ms = 250;
  double duration_s = 30;
  const char *pi = NULL;
  const char *pv = NULL;
  int ii = -1;
  int iv = -1;
  int i;
  int64_t t0;
  int64_t next;
  struct sigaction sa;
  int cs_fd = -1;
  const char *cs_dir = NULL;
  unsigned char cs_seen[8][8];
  memset(cs_seen, 0, sizeof(cs_seen));
  memset(&power, 0, sizeof(power));
  snprintf(power.name, sizeof(power.name), "pbat_mw");
  for (i = 1; i < argc; i++) {
    char *eq;
    if (!strcmp(argv[i], "-i") && i + 1 < argc) {
      interval_ms = atol(argv[++i]);
      continue;
    }
    if (!strcmp(argv[i], "-d") && i + 1 < argc) {
      duration_s = atof(argv[++i]);
      continue;
    }
    if (!strcmp(argv[i], "--power") && i + 2 < argc) {
      pi = argv[++i];
      pv = argv[++i];
      continue;
    }
    if (!strcmp(argv[i], "--curset") && i + 2 < argc) {
      cs_fd = open(argv[i + 1], O_RDONLY | O_CLOEXEC);
      cs_dir = argv[i + 2];
      i += 2;
      continue;
    }
    eq = strchr(argv[i], '=');
    if (eq == NULL || ns >= MAX_SERIES || (size_t)(eq - argv[i]) >= sizeof(s[0].name)) {
      fprintf(stderr, "kl_sample: argumento inválido: %s\n", argv[i]);
      return 2;
    }
    memset(&s[ns], 0, sizeof(s[ns]));
    memcpy(s[ns].name, argv[i], (size_t)(eq - argv[i]));
    s[ns].fd = open(eq + 1, O_RDONLY | O_CLOEXEC);
    if (s[ns].fd < 0) {
      fprintf(stderr, "kl_sample: %s: %s\n", eq + 1, strerror(errno));
      continue;
    }
    if (pi != NULL && !strcmp(s[ns].name, pi)) ii = ns;
    if (pv != NULL && !strcmp(s[ns].name, pv)) iv = ns;
    ns++;
  }
  if (pi != NULL) {
    for (i = 0; i < ns; i++) {
      if (!strcmp(s[i].name, pi)) ii = i;
      if (!strcmp(s[i].name, pv)) iv = i;
    }
  }
  if (interval_ms < 20 || duration_s <= 0) {
    fprintf(stderr, "uso: kl_sample [-i ms>=20] [-d s] [--power ibat vbat] nome=caminho ...\n");
    return 2;
  }
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = on_signal;
  sigaction(SIGTERM, &sa, NULL);
  sigaction(SIGINT, &sa, NULL);
  t0 = now_ns();
  next = t0;
  while (!g_stop && now_ns() - t0 < (int64_t)(duration_s * 1e9)) {
    double vi = 0;
    double vv = 0;
    int got_i = 0;
    int got_v = 0;
    struct timespec ts;
    for (i = 0; i < ns; i++) {
      double v;
      if (read_int(s[i].fd, &v) != 0) continue;
      add(&s[i], v);
      if (i == ii) {
        vi = v;
        got_i = 1;
      }
      if (i == iv) {
        vv = v;
        got_v = 1;
      }
    }
    if (got_i && got_v) add(&power, -vi * vv / 1e6);
    if (cs_fd >= 0) curset_watch(cs_fd, cs_dir, cs_seen);
    next += (int64_t)interval_ms * 1000000LL;
    ts.tv_sec = (time_t)(next / 1000000000LL);
    ts.tv_nsec = (long)(next % 1000000000LL);
    clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL);
  }
  printf("kl_sample=1\nsample_s=%.2f\n", (double)(now_ns() - t0) / 1e9);
  for (i = 0; i < ns; i++) print_series(&s[i]);
  if (ii >= 0 && iv >= 0) print_series(&power);
  return 0;
}
