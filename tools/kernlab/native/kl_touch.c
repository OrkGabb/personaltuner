// kl_touch — touchscreen virtual (uinput) que toca um roteiro de gestos com tempo exato.
// Substitui `input swipe`: cada `input` sobe um app_process (JVM) que disputa CPU com o
// próprio app medido, no meio do fling. Aqui é um único processo nativo, gesto
// determinístico (mesma velocidade, mesmos pontos), então o scroll é reprodutível.
//
//   kl_touch [-w 1080] [-h 2400] [-r 8] [roteiro|-]
// Roteiro, um comando por linha:
//   swipe x1 y1 x2 y2 ms     toque em (x1,y1), movimentos a cada -r ms, solta em (x2,y2)
//   tap x y
//   sleep ms
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

static int g_fd = -1;
static volatile sig_atomic_t g_stop = 0;
static int g_down = 0;
static int g_track = 1;

static int64_t now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void sleep_until(int64_t t) {
  struct timespec ts;
  ts.tv_sec = (time_t)(t / 1000000000LL);
  ts.tv_nsec = (long)(t % 1000000000LL);
  while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL) == EINTR && !g_stop) {
  }
}

static void emit(int type, int code, int value) {
  struct input_event ev;
  memset(&ev, 0, sizeof(ev));
  ev.type = (unsigned short)type;
  ev.code = (unsigned short)code;
  ev.value = value;
  if (write(g_fd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) fprintf(stderr, "kl_touch: write: %s\n", strerror(errno));
}

static void syn(void) { emit(EV_SYN, SYN_REPORT, 0); }

static void touch_down(int x, int y) {
  emit(EV_ABS, ABS_MT_SLOT, 0);
  emit(EV_ABS, ABS_MT_TRACKING_ID, g_track++ & 0xFFFF);
  emit(EV_ABS, ABS_MT_POSITION_X, x);
  emit(EV_ABS, ABS_MT_POSITION_Y, y);
  emit(EV_ABS, ABS_MT_PRESSURE, 60);
  emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 8);
  emit(EV_KEY, BTN_TOUCH, 1);
  syn();
  g_down = 1;
}

static void touch_move(int x, int y) {
  emit(EV_ABS, ABS_MT_POSITION_X, x);
  emit(EV_ABS, ABS_MT_POSITION_Y, y);
  syn();
}

static void touch_up(void) {
  emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
  emit(EV_KEY, BTN_TOUCH, 0);
  syn();
  g_down = 0;
}

static void on_signal(int sig) {
  (void)sig;
  g_stop = 1;
}

static void abs_setup(int code, int min, int max) {
  struct uinput_abs_setup a;
  memset(&a, 0, sizeof(a));
  a.code = (unsigned short)code;
  a.absinfo.minimum = min;
  a.absinfo.maximum = max;
  if (ioctl(g_fd, UI_ABS_SETUP, &a) != 0) fprintf(stderr, "kl_touch: UI_ABS_SETUP %d: %s\n", code, strerror(errno));
}

static int create_device(int w, int h) {
  struct uinput_setup us;
  g_fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
  if (g_fd < 0) {
    fprintf(stderr, "kl_touch: /dev/uinput: %s\n", strerror(errno));
    return -1;
  }
  ioctl(g_fd, UI_SET_EVBIT, EV_SYN);
  ioctl(g_fd, UI_SET_EVBIT, EV_KEY);
  ioctl(g_fd, UI_SET_KEYBIT, BTN_TOUCH);
  ioctl(g_fd, UI_SET_EVBIT, EV_ABS);
  ioctl(g_fd, UI_SET_ABSBIT, ABS_MT_SLOT);
  ioctl(g_fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
  ioctl(g_fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
  ioctl(g_fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
  ioctl(g_fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
  ioctl(g_fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
  ioctl(g_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
  abs_setup(ABS_MT_SLOT, 0, 9);
  abs_setup(ABS_MT_TRACKING_ID, 0, 65535);
  abs_setup(ABS_MT_POSITION_X, 0, w - 1);
  abs_setup(ABS_MT_POSITION_Y, 0, h - 1);
  abs_setup(ABS_MT_PRESSURE, 0, 255);
  abs_setup(ABS_MT_TOUCH_MAJOR, 0, 255);
  memset(&us, 0, sizeof(us));
  us.id.bustype = BUS_VIRTUAL;
  us.id.vendor = 0x4b4c;
  us.id.product = 0x0001;
  us.id.version = 1;
  snprintf(us.name, sizeof(us.name), "kernlab-touch");
  if (ioctl(g_fd, UI_DEV_SETUP, &us) != 0 || ioctl(g_fd, UI_DEV_CREATE) != 0) {
    fprintf(stderr, "kl_touch: criar dispositivo: %s\n", strerror(errno));
    close(g_fd);
    g_fd = -1;
    return -1;
  }
  return 0;
}

static void destroy_device(void) {
  if (g_fd < 0) return;
  if (g_down) touch_up();
  ioctl(g_fd, UI_DEV_DESTROY);
  close(g_fd);
  g_fd = -1;
}

static void do_swipe(int x1, int y1, int x2, int y2, int ms, int rate_ms) {
  int64_t t0 = now_ns();
  int64_t dur = (int64_t)ms * 1000000LL;
  int64_t step = (int64_t)rate_ms * 1000000LL;
  int64_t t;
  touch_down(x1, y1);
  for (t = step; t < dur && !g_stop; t += step) {
    double f = (double)t / (double)dur;
    sleep_until(t0 + t);
    touch_move(x1 + (int)((x2 - x1) * f), y1 + (int)((y2 - y1) * f));
  }
  sleep_until(t0 + dur);
  touch_move(x2, y2);
  touch_up();
}

int main(int argc, char **argv) {
  int w = 1080;
  int h = 2400;
  int rate = 8;
  const char *script = "-";
  FILE *in;
  char line[256];
  long gestures = 0;
  int i;
  struct sigaction sa;
  for (i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "-w") && i + 1 < argc) w = atoi(argv[++i]);
    else if (!strcmp(argv[i], "-h") && i + 1 < argc) h = atoi(argv[++i]);
    else if (!strcmp(argv[i], "-r") && i + 1 < argc) rate = atoi(argv[++i]);
    else script = argv[i];
  }
  if (w < 16 || h < 16 || rate < 1) {
    fprintf(stderr, "uso: kl_touch [-w 1080] [-h 2400] [-r 8] [roteiro|-]\n");
    return 2;
  }
  in = strcmp(script, "-") == 0 ? stdin : fopen(script, "re");
  if (in == NULL) {
    fprintf(stderr, "kl_touch: roteiro %s: %s\n", script, strerror(errno));
    return 2;
  }
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = on_signal;
  sigaction(SIGTERM, &sa, NULL);
  sigaction(SIGINT, &sa, NULL);
  sigaction(SIGHUP, &sa, NULL);
  if (create_device(w, h) != 0) return 1;
  // o InputReader precisa enxergar o dispositivo novo antes do primeiro toque
  usleep(800000);
  while (!g_stop && fgets(line, sizeof(line), in) != NULL) {
    int a, b, c, d, e;
    if (sscanf(line, "swipe %d %d %d %d %d", &a, &b, &c, &d, &e) == 5) {
      do_swipe(a, b, c, d, e, rate);
      gestures++;
    } else if (sscanf(line, "tap %d %d", &a, &b) == 2) {
      touch_down(a, b);
      usleep(40000);
      touch_up();
      gestures++;
    } else if (sscanf(line, "sleep %d", &a) == 1) {
      sleep_until(now_ns() + (int64_t)a * 1000000LL);
    }
  }
  destroy_device();
  if (in != stdin) fclose(in);
  printf("kl_touch=1\ngestures=%ld\nstopped=%d\n", gestures, (int)g_stop);
  return 0;
}
