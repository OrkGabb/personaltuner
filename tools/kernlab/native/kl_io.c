// kl_io — micro-bench de I/O para os nós de bloco (scheduler, read_ahead_kb, nr_requests,
// rq_affinity, iosched/*, UFS clkgate/hibern8).
//
//   kl_io rand  --dev /dev/block/sda [--bs 4096] [--qd 1] [--duration-s 10] [--span-mb 0]
//        leitura aleatória O_DIRECT direto no dispositivo de bloco, SOMENTE LEITURA:
//        mede fila + escalonador + UFS sem cache de página e sem desgastar a flash.
//   kl_io seq   --file F [--bs 131072]
//        leitura sequencial bufferizada após DONTNEED do arquivo: mede o readahead.
//   kl_io prep  --file F --mb M        cria o arquivo de teste (só se faltar/estiver menor)
//   kl_io mixed --dev /dev/block/sda --wfile F [--wmb 64] [--wrate-mbs 40] [--duration-s 10]
//        leitura aleatória QD1 medida enquanto um escritor faz escrita bufferizada com
//        fdatasync: latência de leitura sob pressão de escrita (o caso jogo + download).
// Arquivos escritos ficam obrigatoriamente sob /data/local/tmp/kernlab/.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define SAFE_PREFIX "/data/local/tmp/kernlab/"

typedef struct {
  const char *dev;
  const char *file;
  const char *wfile;
  long bs;
  int qd;
  double duration_s;
  long span_mb;
  long mb;
  long wmb;
  long wrate_mbs;
} opt_t;

typedef struct {
  int fd;
  long bs;
  uint64_t nblocks;
  int64_t deadline;
  uint64_t seed;
  int32_t *lat;
  long cap;
  long n;
  long errors;
} rworker_t;

static opt_t g_o;
static _Atomic int g_stop_writer = 0;
static _Atomic long g_written_mb = 0;

static int64_t now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static uint64_t xs(uint64_t *s) {
  uint64_t x = *s;
  x ^= x << 13;
  x ^= x >> 7;
  x ^= x << 17;
  *s = x;
  return x;
}

static int safe_path(const char *p) {
  return p != NULL && strncmp(p, SAFE_PREFIX, strlen(SAFE_PREFIX)) == 0 && strstr(p, "..") == NULL;
}

static int cmp_i32(const void *a, const void *b) {
  int32_t x = *(const int32_t *)a;
  int32_t y = *(const int32_t *)b;
  return (x > y) - (x < y);
}

static int32_t pct(const int32_t *s, long n, double q) {
  long i;
  if (n <= 0) return -1;
  i = (long)(q * (double)(n - 1) + 0.5);
  return s[i < 0 ? 0 : (i >= n ? n - 1 : i)];
}

static void print_lat(const char *name, int32_t *v, long n) {
  double sum = 0;
  long i;
  qsort(v, (size_t)n, sizeof(int32_t), cmp_i32);
  for (i = 0; i < n; i++) sum += v[i];
  printf("%s_p50_us=%d\n%s_p90_us=%d\n%s_p99_us=%d\n%s_p999_us=%d\n%s_max_us=%d\n%s_mean_us=%.1f\n", name,
         pct(v, n, 0.5), name, pct(v, n, 0.9), name, pct(v, n, 0.99), name, pct(v, n, 0.999), name,
         n > 0 ? v[n - 1] : -1, name, n > 0 ? sum / (double)n : -1.0);
}

static uint64_t dev_size(int fd) {
  struct stat st;
  uint64_t sz = 0;
  if (fstat(fd, &st) != 0) return 0;
  if (S_ISBLK(st.st_mode)) {
    if (ioctl(fd, BLKGETSIZE64, &sz) != 0) return 0;
    return sz;
  }
  return (uint64_t)st.st_size;
}

static void *rworker_main(void *arg) {
  rworker_t *w = (rworker_t *)arg;
  void *buf = NULL;
  if (posix_memalign(&buf, 4096, (size_t)w->bs) != 0) return NULL;
  while (now_ns() < w->deadline) {
    off_t off = (off_t)((xs(&w->seed) % w->nblocks) * (uint64_t)w->bs);
    int64_t t = now_ns();
    ssize_t r = pread(w->fd, buf, (size_t)w->bs, off);
    int64_t d = now_ns() - t;
    if (r != (ssize_t)w->bs) {
      w->errors++;
      continue;
    }
    if (w->n < w->cap) w->lat[w->n++] = (int32_t)(d / 1000);
  }
  free(buf);
  return NULL;
}

// Leitura aleatória com qd threads síncronas; devolve a soma de operações.
static int run_rand(int qd, const char *label) {
  int fd = open(g_o.dev, O_RDONLY | O_DIRECT | O_CLOEXEC);
  uint64_t size;
  uint64_t span;
  rworker_t *w;
  pthread_t *th;
  int i;
  long total = 0;
  long errors = 0;
  int32_t *all;
  long k = 0;
  int64_t t0;
  int64_t t1;
  if (fd < 0) {
    fprintf(stderr, "kl_io: abrir %s: %s\n", g_o.dev, strerror(errno));
    return -1;
  }
  size = dev_size(fd);
  span = g_o.span_mb > 0 ? (uint64_t)g_o.span_mb << 20 : size;
  if (span > size) span = size;
  if (size == 0 || span < (uint64_t)g_o.bs) {
    fprintf(stderr, "kl_io: tamanho inválido de %s\n", g_o.dev);
    close(fd);
    return -1;
  }
  w = (rworker_t *)calloc((size_t)qd, sizeof(rworker_t));
  th = (pthread_t *)calloc((size_t)qd, sizeof(pthread_t));
  if (w == NULL || th == NULL) return -1;
  t0 = now_ns();
  for (i = 0; i < qd; i++) {
    w[i].fd = fd;
    w[i].bs = g_o.bs;
    w[i].nblocks = span / (uint64_t)g_o.bs;
    w[i].deadline = t0 + (int64_t)(g_o.duration_s * 1e9);
    w[i].seed = 0x9E3779B97F4A7C15ULL ^ ((uint64_t)(i + 1) * 0x2545F4914F6CDD1DULL);
    w[i].cap = (long)(g_o.duration_s * 40000.0) + 1024;
    w[i].lat = (int32_t *)malloc((size_t)w[i].cap * sizeof(int32_t));
    if (w[i].lat == NULL) return -1;
    pthread_create(&th[i], NULL, rworker_main, &w[i]);
  }
  for (i = 0; i < qd; i++) pthread_join(th[i], NULL);
  t1 = now_ns();
  for (i = 0; i < qd; i++) {
    total += w[i].n;
    errors += w[i].errors;
  }
  all = (int32_t *)malloc((size_t)(total > 0 ? total : 1) * sizeof(int32_t));
  if (all == NULL) return -1;
  for (i = 0; i < qd; i++) {
    memcpy(all + k, w[i].lat, (size_t)w[i].n * sizeof(int32_t));
    k += w[i].n;
    free(w[i].lat);
  }
  printf("%s_ops=%ld\n%s_errors=%ld\n%s_iops=%.1f\n%s_mbps=%.2f\n", label, total, label, errors, label,
         (double)total / ((double)(t1 - t0) / 1e9), label,
         (double)total * (double)g_o.bs / 1048576.0 / ((double)(t1 - t0) / 1e9));
  print_lat(label, all, total);
  free(all);
  free(w);
  free(th);
  close(fd);
  return 0;
}

static int cmd_seq(void) {
  int fd = open(g_o.file, O_RDONLY | O_CLOEXEC);
  char *buf;
  long cap = 1 << 20;
  int32_t *lat;
  long n = 0;
  uint64_t bytes = 0;
  int64_t t0;
  int64_t t1;
  if (fd < 0) {
    fprintf(stderr, "kl_io: abrir %s: %s\n", g_o.file, strerror(errno));
    return 1;
  }
  posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
  buf = (char *)malloc((size_t)g_o.bs);
  lat = (int32_t *)malloc((size_t)cap * sizeof(int32_t));
  if (buf == NULL || lat == NULL) return 1;
  t0 = now_ns();
  for (;;) {
    int64_t t = now_ns();
    ssize_t r = read(fd, buf, (size_t)g_o.bs);
    if (r <= 0) break;
    if (n < cap) lat[n++] = (int32_t)((now_ns() - t) / 1000);
    bytes += (uint64_t)r;
  }
  t1 = now_ns();
  posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
  printf("kl_io=1\nmode=seq\nbs=%ld\nseq_bytes=%llu\nseq_ms=%.1f\nseq_mbps=%.2f\n", g_o.bs, (unsigned long long)bytes,
         (double)(t1 - t0) / 1e6, (double)bytes / 1048576.0 / ((double)(t1 - t0) / 1e9));
  print_lat("seq", lat, n);
  free(buf);
  free(lat);
  close(fd);
  return 0;
}

static int cmd_prep(void) {
  struct stat st;
  int fd;
  char *buf;
  long i;
  uint64_t s = 0x1234567890ABCDEFULL;
  if (!safe_path(g_o.file) || g_o.mb <= 0 || g_o.mb > 2048) {
    fprintf(stderr, "kl_io: prep exige --file sob %s e 0 < --mb <= 2048\n", SAFE_PREFIX);
    return 2;
  }
  if (stat(g_o.file, &st) == 0 && (uint64_t)st.st_size >= ((uint64_t)g_o.mb << 20)) {
    printf("kl_io=1\nmode=prep\nprep=cached\n");
    return 0;
  }
  fd = open(g_o.file, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
  buf = (char *)malloc(1 << 20);
  if (fd < 0 || buf == NULL) {
    fprintf(stderr, "kl_io: criar %s: %s\n", g_o.file, strerror(errno));
    return 1;
  }
  for (i = 0; i < g_o.mb; i++) {
    size_t j;
    for (j = 0; j < (1 << 20) / 8; j++) ((uint64_t *)buf)[j] = xs(&s);
    if (write(fd, buf, 1 << 20) != (1 << 20)) {
      fprintf(stderr, "kl_io: escrever %s: %s\n", g_o.file, strerror(errno));
      close(fd);
      return 1;
    }
  }
  fsync(fd);
  close(fd);
  free(buf);
  printf("kl_io=1\nmode=prep\nprep=written\nmb=%ld\n", g_o.mb);
  return 0;
}

static void *writer_main(void *arg) {
  int fd = open(g_o.wfile, O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
  const long chunk = 256 * 1024;
  char *buf = (char *)malloc((size_t)chunk);
  uint64_t s = 0xFEEDFACECAFEBEEFULL;
  uint64_t limit = (uint64_t)g_o.wmb << 20;
  uint64_t pos = 0;
  uint64_t done = 0;
  uint64_t since_sync = 0;
  int64_t t0 = now_ns();
  size_t j;
  (void)arg;
  if (fd < 0 || buf == NULL) return NULL;
  for (j = 0; j < (size_t)chunk / 8; j++) ((uint64_t *)buf)[j] = xs(&s);
  while (!atomic_load(&g_stop_writer)) {
    int64_t due = t0 + (int64_t)((double)done / ((double)g_o.wrate_mbs * 1048576.0) * 1e9);
    if (now_ns() < due) {
      usleep(1000);
      continue;
    }
    if (pwrite(fd, buf, (size_t)chunk, (off_t)pos) != chunk) break;
    pos = (pos + (uint64_t)chunk) % limit;
    done += (uint64_t)chunk;
    since_sync += (uint64_t)chunk;
    if (since_sync >= (8u << 20)) {
      fdatasync(fd);
      since_sync = 0;
    }
  }
  fdatasync(fd);
  close(fd);
  free(buf);
  atomic_store(&g_written_mb, (long)(done >> 20));
  return NULL;
}

static int cmd_mixed(void) {
  pthread_t wt;
  int rc;
  if (!safe_path(g_o.wfile) || g_o.wmb <= 0 || g_o.wmb > 512 || g_o.wrate_mbs <= 0) {
    fprintf(stderr, "kl_io: mixed exige --wfile sob %s, 0 < --wmb <= 512, --wrate-mbs > 0\n", SAFE_PREFIX);
    return 2;
  }
  pthread_create(&wt, NULL, writer_main, NULL);
  usleep(500000);
  printf("kl_io=1\nmode=mixed\nbs=%ld\nwrate_mbs=%ld\n", g_o.bs, g_o.wrate_mbs);
  rc = run_rand(1, "rd");
  atomic_store(&g_stop_writer, 1);
  pthread_join(wt, NULL);
  printf("written_mb=%ld\n", atomic_load(&g_written_mb));
  return rc == 0 ? 0 : 1;
}

static void usage(void) {
  fprintf(stderr,
          "uso: kl_io rand --dev D [--bs 4096] [--qd 1] [--duration-s 10] [--span-mb 0]\n"
          "     kl_io seq --file F [--bs 131072]\n"
          "     kl_io prep --file F --mb M\n"
          "     kl_io mixed --dev D --wfile F [--wmb 64] [--wrate-mbs 40] [--duration-s 10]\n");
}

int main(int argc, char **argv) {
  int i;
  const char *cmd;
  if (argc < 2) {
    usage();
    return 2;
  }
  cmd = argv[1];
  g_o.bs = 4096;
  g_o.qd = 1;
  g_o.duration_s = 10;
  g_o.wmb = 64;
  g_o.wrate_mbs = 40;
  for (i = 2; i + 1 < argc; i += 2) {
    const char *a = argv[i];
    const char *v = argv[i + 1];
    if (!strcmp(a, "--dev")) g_o.dev = v;
    else if (!strcmp(a, "--file")) g_o.file = v;
    else if (!strcmp(a, "--wfile")) g_o.wfile = v;
    else if (!strcmp(a, "--bs")) g_o.bs = atol(v);
    else if (!strcmp(a, "--qd")) g_o.qd = atoi(v);
    else if (!strcmp(a, "--duration-s")) g_o.duration_s = atof(v);
    else if (!strcmp(a, "--span-mb")) g_o.span_mb = atol(v);
    else if (!strcmp(a, "--mb")) g_o.mb = atol(v);
    else if (!strcmp(a, "--wmb")) g_o.wmb = atol(v);
    else if (!strcmp(a, "--wrate-mbs")) g_o.wrate_mbs = atol(v);
    else {
      usage();
      return 2;
    }
  }
  if (g_o.bs < 512 || (g_o.bs % 512) != 0 || g_o.qd < 1 || g_o.qd > 32 || g_o.duration_s <= 0) {
    usage();
    return 2;
  }
  if (!strcmp(cmd, "rand")) {
    if (g_o.dev == NULL) {
      usage();
      return 2;
    }
    printf("kl_io=1\nmode=rand\nbs=%ld\nqd=%d\n", g_o.bs, g_o.qd);
    return run_rand(g_o.qd, "rd") == 0 ? 0 : 1;
  }
  if (!strcmp(cmd, "seq")) return g_o.file != NULL ? cmd_seq() : (usage(), 2);
  if (!strcmp(cmd, "prep")) return cmd_prep();
  if (!strcmp(cmd, "mixed")) return g_o.dev != NULL ? cmd_mixed() : (usage(), 2);
  usage();
  return 2;
}
