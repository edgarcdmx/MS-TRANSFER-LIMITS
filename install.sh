#!/usr/bin/env bash
# =============================================================================================
# MS-TRANSFER-LIMITS - instalador automatico + demo end-to-end + reporte de instalacion.
#
#   ./install.sh                      # modo auto: docker si `docker info` funciona, si no local
#   ./install.sh --mode local         # sin Docker: JDK 27 portable + PostgreSQL embebido (zonky)
#   ./install.sh --mode docker        # docker compose (PostgreSQL 18 + Jaeger + app)
#   ./install.sh --keep-running       # deja el servicio arriba al terminar
#   ./install.sh --stop               # detiene lo que dejo arriba --keep-running
#   ./install.sh --help
#
# Funciona en Linux, macOS (bash 3.2+) y Windows (Git Bash). Sin jq: JSON con grep/sed.
# Todo el estado vive en target/install/ (git-ignored). Nunca borra target/review/.
# Fases: 0 preflight, 1 JDK 27, 2 build + gate, 3 runtime, 4 demo R1-R14, 5 reporte, 6 limpieza.
# Codigos de salida: 0 todo OK, 1 algun requisito FAIL, 2 fallo irrecuperable, 64 uso incorrecto.
# =============================================================================================
set -u
set -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT" || exit 2
export LC_ALL=C

# ------------------------------------------------------------------------------ opciones ----
MODE="auto"
SKIP_TESTS=0
KEEP_RUNNING=0
REQ_APP_PORT=8080
RETRIES=3
REPORT=""
DO_STOP=0
AI_FIX=0
RESET_DB=0
INSECURE_DOWNLOAD=0
CONC_N=40

usage() {
  cat <<'EOF'
Uso: ./install.sh [opciones]

  --mode auto|docker|local  auto (defecto): docker si `docker info` responde, si no local.
                            local: JDK 27 portable + PostgreSQL embebido descargado de Maven
                            Central (sin Docker ni permisos de administrador).
  --skip-tests              compila sin pruebas ni JaCoCo (R7 queda SKIPPED); Spotless y
                            Checkstyle se siguen verificando.
  --keep-running            deja la app (y PostgreSQL/Jaeger) arriba al terminar.
  --stop                    detiene lo que dejo arriba una ejecucion con --keep-running.
  --port N                  puerto HTTP de la app (defecto 8080; si esta ocupado se usa el
                            siguiente libre).
  --retries N               reintentos por fase con auto-reparacion (defecto 3).
  --concurrency N           peticiones paralelas en la prueba de concurrencia (defecto 40).
  --report PATH             ruta del reporte Markdown
                            (defecto target/install-report/REPORTE_INSTALACION.md).
  --ai-fix                  ante un fallo irrecuperable, ofrece ejecutar `claude -p` con el
                            diagnostico (solo si el CLI `claude` esta en el PATH).
  --reset-db                modo docker: en los reintentos borra tambien el volumen de
                            PostgreSQL (docker compose down -v). Sin ella los datos se conservan.
  --insecure-download       continua aunque el proveedor no publique checksum (NO recomendado).
  -h, --help                esta ayuda.

Salida: 0 = todos los requisitos no omitidos en PASS; 1 = algun requisito FAIL;
        2 = fallo irrecuperable de instalacion; 64 = opciones invalidas.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE="${2:-}"; shift 2 ;;
    --mode=*) MODE="${1#*=}"; shift ;;
    --skip-tests) SKIP_TESTS=1; shift ;;
    --keep-running) KEEP_RUNNING=1; shift ;;
    --stop) DO_STOP=1; shift ;;
    --port) REQ_APP_PORT="${2:-}"; shift 2 ;;
    --port=*) REQ_APP_PORT="${1#*=}"; shift ;;
    --retries) RETRIES="${2:-}"; shift 2 ;;
    --retries=*) RETRIES="${1#*=}"; shift ;;
    --concurrency) CONC_N="${2:-}"; shift 2 ;;
    --concurrency=*) CONC_N="${1#*=}"; shift ;;
    --report) REPORT="${2:-}"; shift 2 ;;
    --report=*) REPORT="${1#*=}"; shift ;;
    --ai-fix) AI_FIX=1; shift ;;
    --reset-db) RESET_DB=1; shift ;;
    --insecure-download) INSECURE_DOWNLOAD=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "Opcion desconocida: $1" >&2; usage >&2; exit 64 ;;
  esac
done
case "$MODE" in auto | docker | local) ;; *) echo "--mode debe ser auto|docker|local" >&2; exit 64 ;; esac
for n in "$REQ_APP_PORT" "$RETRIES" "$CONC_N"; do
  case "$n" in '' | *[!0-9]*) echo "Valor numerico invalido: '$n'" >&2; exit 64 ;; esac
done
[ "$RETRIES" -ge 1 ] || RETRIES=1

# ------------------------------------------------------------------------------ rutas -------
STATE_DIR="$ROOT/target/install"
LOG_DIR="$STATE_DIR/logs"
WORK="$STATE_DIR/work"
STATE_FILE="$STATE_DIR/state.env"
[ -n "$REPORT" ] || REPORT="$ROOT/target/install-report/REPORTE_INSTALACION.md"
case "$REPORT" in /* | [A-Za-z]:*) ;; *) REPORT="$ROOT/$REPORT" ;; esac
DEVTOOLS="$HOME/.local/devtools"
mkdir -p "$LOG_DIR" "$WORK" "$(dirname "$REPORT")" || exit 2
CONSOLE_LOG="$LOG_DIR/install.log"

# ------------------------------------------------------------------------------ salida ------
if [ -t 1 ]; then
  C_R=$'\033[31m'; C_G=$'\033[32m'; C_Y=$'\033[33m'; C_B=$'\033[1;36m'; C_0=$'\033[0m'
else
  C_R=""; C_G=""; C_Y=""; C_B=""; C_0=""
fi
ts() { date '+%H:%M:%S'; }
now_s() { date +%s; }
now_ms() {
  local n
  n="$(date +%s%N 2>/dev/null)"
  case "$n" in *N | '') echo $(($(date +%s) * 1000)) ;; *) echo $((n / 1000000)) ;; esac
}
_log() { printf '%s\n' "$*" >>"$CONSOLE_LOG"; }
say() { printf '%s\n' "$*"; _log "$*"; }
info() { say "[$(ts)]   $*"; }
ok() { printf '%s\n' "[$(ts)] ${C_G}OK${C_0} $*"; _log "[$(ts)] OK $*"; }
warn() { printf '%s\n' "[$(ts)] ${C_Y}WARN${C_0} $*"; _log "[$(ts)] WARN $*"; }
err() { printf '%s\n' "[$(ts)] ${C_R}ERROR${C_0} $*" >&2; _log "[$(ts)] ERROR $*"; }
have() { command -v "$1" >/dev/null 2>&1; }
# Paths shown to humans / in the report: $HOME -> ~ (no machine-specific user paths).
HOME_NATIVE=""
command -v cygpath >/dev/null 2>&1 && HOME_NATIVE="$(cygpath -m "$HOME" 2>/dev/null)"
tilde() {
  local s="$*" h="$HOME" hn="$HOME_NATIVE"
  s="${s//"$h"/\~}"
  [ -n "$hn" ] && s="${s//"$hn"/\~}"
  printf '%s' "$s"
}
rel() { local p="$1"; p="${p#"$ROOT"/}"; tilde "$p"; }

OS_RAW="$(uname -s 2>/dev/null || echo unknown)"
ARCH_RAW="$(uname -m 2>/dev/null || echo unknown)"
case "$OS_RAW" in
  MINGW* | MSYS* | CYGWIN*) OS=windows ;;
  Linux) OS=linux ;;
  Darwin) OS=mac ;;
  *) OS=unknown ;;
esac
case "$ARCH_RAW" in
  x86_64 | amd64) ARCH=x64 ;;
  aarch64 | arm64) ARCH=aarch64 ;;
  *) ARCH="$ARCH_RAW" ;;
esac
EXE=""; [ "$OS" = windows ] && EXE=".exe"
# Path form understood by native (non-MSYS) programs.
native_path() { if [ "$OS" = windows ] && have cygpath; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

sha_file() { # <algo 1|256> <file>
  if have "sha${1}sum"; then "sha${1}sum" "$2" | awk '{print $1}'; else shasum -a "$1" "$2" | awk '{print $1}'; fi
}
num_le() { awk -v a="$1" -v b="$2" 'BEGIN{exit !((a+0)<=(b+0))}'; }
num_eq() { awk -v a="$1" -v b="$2" 'BEGIN{exit !((a+0)==(b+0))}'; }

# ------------------------------------------------------------------------------ estado ------
state_set() { # key value
  touch "$STATE_FILE"
  grep -v "^$1=" "$STATE_FILE" >"$STATE_FILE.tmp" 2>/dev/null
  printf '%s=%s\n' "$1" "$2" >>"$STATE_FILE.tmp"
  mv -f "$STATE_FILE.tmp" "$STATE_FILE"
}
state_get() { [ -f "$STATE_FILE" ] && grep "^$1=" "$STATE_FILE" | tail -1 | cut -d= -f2-; }

kill_pid() { # native pid (Windows pid on Windows)
  local pid="$1"
  [ -n "$pid" ] || return 0
  if [ "$OS" = windows ]; then
    taskkill //PID "$pid" //T //F >/dev/null 2>&1
  else
    kill "$pid" 2>/dev/null
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || return 0; sleep 1; done
    kill -9 "$pid" 2>/dev/null
  fi
  return 0
}

# ------------------------------------------------------------------------------ puertos -----
port_busy() {
  local p="$1"
  if [ "$OS" = windows ]; then
    netstat -an 2>/dev/null | tr -d '\r' | awk -v p=":$p" '$NF=="LISTENING" && substr($2, length($2)-length(p)+1)==p {f=1} END{exit !f}'
    return
  fi
  if have ss; then
    ss -ltnH 2>/dev/null | awk -v p=":$p" '{a=$4; if (substr(a, length(a)-length(p)+1)==p) f=1} END{exit !f}'
    return
  fi
  if have lsof; then lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; return; fi
  (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null
}
free_port() { # first free port >= $1
  local p="$1" n=0
  while port_busy "$p" && [ $n -lt 200 ]; do p=$((p + 1)); n=$((n + 1)); done
  echo "$p"
}
pid_on_port() {
  local p="$1"
  if [ "$OS" = windows ]; then
    netstat -ano 2>/dev/null | tr -d '\r' | awk -v p=":$p" '$4=="LISTENING" && substr($2, length($2)-length(p)+1)==p {print $5; exit}'
  elif have lsof; then
    lsof -nP -t -iTCP:"$p" -sTCP:LISTEN 2>/dev/null | head -1
  fi
}

# ------------------------------------------------------------------------------ fases -------
PH_NAMES=(); PH_STATUS=(); PH_DUR=(); PH_TRIES=(); PH_NOTE=()
CUR_PH=-1; CUR_PH_T0=0
phase_begin() { # number title
  CUR_PH=${#PH_NAMES[@]}
  PH_NAMES+=("$1. $2"); PH_STATUS+=("EN CURSO"); PH_DUR+=(0); PH_TRIES+=(1); PH_NOTE+=("")
  CUR_PH_T0=$(now_s)
  say ""
  printf '%s\n' "${C_B}=== [$(ts)] FASE $1: $2 ===${C_0}"
  _log "=== [$(ts)] FASE $1: $2 ==="
}
phase_note() { if [ -n "${PH_NOTE[$CUR_PH]}" ]; then PH_NOTE[$CUR_PH]="${PH_NOTE[$CUR_PH]}; $*"; else PH_NOTE[$CUR_PH]="$*"; fi; }
phase_try() { PH_TRIES[$CUR_PH]=$1; }
phase_end() { # status
  local d=$(($(now_s) - CUR_PH_T0))
  PH_STATUS[$CUR_PH]="$1"; PH_DUR[$CUR_PH]=$d
  case "$1" in
    OK) ok "fase '${PH_NAMES[$CUR_PH]}' completada en ${d}s" ;;
    SKIPPED) warn "fase '${PH_NAMES[$CUR_PH]}' omitida (${d}s)" ;;
    *) err "fase '${PH_NAMES[$CUR_PH]}' termino con estado $1 (${d}s)" ;;
  esac
}

# ------------------------------------------------------------------------------ requisitos --
REQ_IDS=(R1 R2 R3 R4 R5 R6 R7 R8 R9 R10 R11 R12 R13 R14)
REQ_TITLES=(
  "Java 27 + Spring Boot 4.2.0"
  "Build Maven con wrapper"
  "Google Java Style (Spotless + Checkstyle)"
  "Errores RFC 9457 (Problem Details)"
  "Trazas OpenTelemetry"
  "Imagen Docker lista"
  "Cobertura JaCoCo >= 90 %"
  "Concurrencia (limite diario atomico + idempotencia)"
  "Contrato OpenAPI 3 publicado"
  "PostgreSQL + Flyway (persistencia)"
  "Reglas de negocio (PUT/GET/POST, 201/200/409/400/404/422)"
  "Mensajes con Apache Commons Text"
  "Harness Claude Code + bloqueo de push"
  "Health /actuator/health UP"
)
REQ_STATUS=(); REQ_EVID=(); REQ_CHECKS=(); REQ_FAILS=()
for _i in "${!REQ_IDS[@]}"; do REQ_STATUS+=("PENDIENTE"); REQ_EVID+=(""); REQ_CHECKS+=(0); REQ_FAILS+=(0); done
req_idx() { local i; for i in "${!REQ_IDS[@]}"; do [ "${REQ_IDS[$i]}" = "$1" ] && { echo "$i"; return; }; done; echo 0; }
req_evid() { # id text
  local i; i=$(req_idx "$1")
  if [ -n "${REQ_EVID[$i]}" ]; then REQ_EVID[$i]="${REQ_EVID[$i]}; $2"; else REQ_EVID[$i]="$2"; fi
}
# check <req> <PASS|FAIL> <description> : one verification line, sticky FAIL per requirement.
check() {
  local i; i=$(req_idx "$1")
  REQ_CHECKS[$i]=$((REQ_CHECKS[$i] + 1))
  if [ "$2" = PASS ]; then
    printf '%s\n' "         ${C_G}PASS${C_0} [$1] $3"; _log "         PASS [$1] $3"
  else
    REQ_FAILS[$i]=$((REQ_FAILS[$i] + 1))
    printf '%s\n' "         ${C_R}FAIL${C_0} [$1] $3"; _log "         FAIL [$1] $3"
    req_evid "$1" "FALLO: $3"
  fi
}
# expect <req> <description> <condition-exit-code>
expect() { if [ "$3" -eq 0 ]; then check "$1" PASS "$2"; else check "$1" FAIL "$2"; fi; }
req_skip() { local i; i=$(req_idx "$1"); REQ_STATUS[$i]="SKIPPED"; req_evid "$1" "$2"; }
req_finalize() {
  local i
  for i in "${!REQ_IDS[@]}"; do
    [ "${REQ_STATUS[$i]}" = SKIPPED ] && continue
    if [ "${REQ_CHECKS[$i]}" -eq 0 ]; then
      REQ_STATUS[$i]="FAIL"; [ -n "${REQ_EVID[$i]}" ] || REQ_EVID[$i]="no se pudo verificar (fase previa fallida)"
    elif [ "${REQ_FAILS[$i]}" -gt 0 ]; then
      REQ_STATUS[$i]="FAIL"
    else
      REQ_STATUS[$i]="PASS"
    fi
  done
}

# ------------------------------------------------------------------------------ JSON / HTTP -
json_str() { # key json
  printf '%s' "$2" | grep -oE "\"$1\"[[:space:]]*:[[:space:]]*\"([^\"\\\\]|\\\\.)*\"" | head -1 \
    | sed -E "s/^\"$1\"[[:space:]]*:[[:space:]]*\"//; s/\"\$//"
}
json_num() { printf '%s' "$2" | grep -oE "\"$1\"[[:space:]]*:[[:space:]]*-?[0-9.]+" | head -1 | sed -E 's/.*:[[:space:]]*//'; }
count_of() { printf '%s' "$2" | grep -oF "$1" | wc -l | tr -d ' '; }
compact() { local s; s="$(printf '%s' "$1" | tr -d '\r\n')"; [ ${#s} -gt 260 ] && s="${s:0:257}..."; printf '%s' "$s"; }

BASE_URL=""
HTTP_N=0
HTTP_CODE=""; HTTP_BODY=""; HTTP_CTYPE=""; HTTP_LOCATION=""
http() { # method url-or-path [json body]
  local m="$1" u="$2" b="${3:-}" hf bf
  case "$u" in http*) ;; *) u="$BASE_URL$u" ;; esac
  HTTP_N=$((HTTP_N + 1)); hf="$WORK/h.$HTTP_N"; bf="$WORK/b.$HTTP_N"
  local args=(-s -S --noproxy '*' -m 30 -o "$bf" -D "$hf" -w '%{http_code}' -X "$m")
  [ -n "$b" ] && args+=(-H 'Content-Type: application/json' --data-binary "$b")
  HTTP_CODE="$(curl "${args[@]}" "$u" 2>>"$CONSOLE_LOG")" || HTTP_CODE="${HTTP_CODE:-000}"
  [ -n "$HTTP_CODE" ] || HTTP_CODE=000
  HTTP_BODY="$(cat "$bf" 2>/dev/null)"
  HTTP_CTYPE="$(tr -d '\r' <"$hf" 2>/dev/null | grep -i '^content-type:' | tail -1 | sed 's/^[^:]*:[[:space:]]*//')"
  HTTP_LOCATION="$(tr -d '\r' <"$hf" 2>/dev/null | grep -i '^location:' | tail -1 | sed 's/^[^:]*:[[:space:]]*//')"
  say "       -> $m ${u#"$BASE_URL"}${b:+  $b}"
  say "       <- $HTTP_CODE ${HTTP_CTYPE:-}  $(compact "$HTTP_BODY")"
}

# ------------------------------------------------------------------------------ diagnostico -
FATAL_DIAG=""; FATAL_NEXT=""
fatal() { # diagnosis next-step
  FATAL_DIAG="$1"; FATAL_NEXT="$2"
  err "$1"
  say "  Siguiente paso manual: $2"
}
offer_ai_fix() {
  [ "$AI_FIX" -eq 1 ] || { have claude && say "  (Sugerencia: vuelva a ejecutar con --ai-fix para que Claude Code diagnostique y corrija.)"; return 0; }
  if ! have claude; then warn "--ai-fix: el CLI 'claude' no esta en el PATH"; return 0; fi
  local prompt="El instalador ./install.sh de MS-TRANSFER-LIMITS fallo. Diagnostico: ${FATAL_DIAG}. Siguiente paso sugerido: ${FATAL_NEXT}. Reporte: $(rel "$REPORT"), logs en $(rel "$LOG_DIR"). Diagnostica y corrige la causa respetando CLAUDE.md, luego vuelve a ejecutar ./install.sh."
  local ans="n"
  if [ -t 0 ]; then
    printf '%s' "Ejecutar ahora: claude -p \"<diagnostico + reporte>\"? [s/N] "
    read -r ans || ans="n"
  fi
  case "$ans" in s | S | y | Y | si | yes) say "Ejecutando claude -p ..."; claude -p "$prompt" ;; *) say "Omitido (sin terminal interactiva o respuesta negativa)." ;; esac
}

# ==================================================================================== STOP ===
stop_local() {
  local pid pgdata pgbin
  pid="$(state_get APP_PID)"
  if [ -n "$pid" ]; then info "deteniendo app (PID $pid)"; kill_pid "$pid"; fi
  pid="$(state_get SINK_PID)"
  if [ -n "$pid" ]; then info "deteniendo receptor OTLP (PID $pid)"; kill_pid "$pid"; fi
  pgdata="$(state_get PGDATA)"; pgbin="$(state_get PGBIN)"
  if [ -n "$pgdata" ] && [ -n "$pgbin" ] && [ -f "$pgdata/postmaster.pid" ]; then
    info "deteniendo PostgreSQL ($(rel "$pgdata"))"
    "$pgbin/pg_ctl$EXE" -D "$(native_path "$pgdata")" -m fast -w -t 60 stop >>"$LOG_DIR/postgres-ctl.log" 2>&1 \
      || "$pgbin/pg_ctl$EXE" -D "$(native_path "$pgdata")" -m immediate -w stop >>"$LOG_DIR/postgres-ctl.log" 2>&1
  fi
  state_set APP_PID ""; state_set SINK_PID ""; state_set RUNNING 0
}
DC=()
detect_compose() {
  if docker compose version >/dev/null 2>&1; then DC=(docker compose)
  elif have docker-compose; then DC=(docker-compose)
  else DC=(); return 1; fi
}
stop_docker() {
  detect_compose || return 0
  info "docker compose down"
  "${DC[@]}" down >>"$LOG_DIR/docker-down.log" 2>&1
  state_set RUNNING 0
}
if [ "$DO_STOP" -eq 1 ]; then
  if [ ! -f "$STATE_FILE" ]; then say "No hay estado en $(rel "$STATE_FILE"): nada que detener."; exit 0; fi
  case "$(state_get MODE)" in
    docker) stop_docker ;;
    *) stop_local ;;
  esac
  say "Detenido."
  exit 0
fi

# ==================================================================================== MAIN ===
RUN_T0=$(now_s)
RUN_DATE="$(date '+%Y-%m-%d %H:%M:%S %z')"
: >"$CONSOLE_LOG"
say "MS-TRANSFER-LIMITS - instalador y demo end-to-end ($RUN_DATE)"
say "Repositorio: $(tilde "$ROOT")"
RUNTIME_UP=0
EXIT_CODE=0

cleanup_runtime() {
  [ "$RUNTIME_UP" -eq 1 ] || return 0
  if [ "$KEEP_RUNNING" -eq 1 ]; then return 0; fi
  if [ "$EFFECTIVE_MODE" = docker ]; then stop_docker; else stop_local; fi
  RUNTIME_UP=0
}
on_signal() { err "interrumpido"; KEEP_RUNNING=0; cleanup_runtime; exit 130; }
trap on_signal INT TERM

# ---------------------------------------------------------------------- FASE 0: preflight ----
EFFECTIVE_MODE="$MODE"
APP_PORT=""; PG_PORT=""; SINK_PORT=""; JAEGER_UI_PORT=""; OTLP_GRPC_PORT=""; OTLP_HTTP_PORT=""
ENV_SUMMARY=()
phase_begin 0 "Preflight (sistema, herramientas, puertos, proxy)"
pf_fail=0
info "SO: $OS_RAW ($OS) / arquitectura: $ARCH_RAW ($ARCH)"
ENV_SUMMARY+=("Sistema operativo|$OS_RAW ($OS), arquitectura $ARCH_RAW")
info "bash: $BASH_VERSION"
ENV_SUMMARY+=("bash|$BASH_VERSION")
if [ "$OS" = unknown ]; then warn "SO no reconocido; se intentara como Linux"; OS=linux; fi
for t in git curl tar unzip; do
  if have "$t"; then
    if [ "$t" = unzip ]; then v="$(unzip -v 2>&1 | head -1 | sed "s/, by .*//")"; else v="$("$t" --version 2>&1 | head -1)"; fi
    info "$t: $v"; ENV_SUMMARY+=("$t|$v")
  else
    if [ "$t" = unzip ] && [ "$OS" != windows ]; then warn "unzip no encontrado (se usara solo si hace falta)"; continue; fi
    err "herramienta requerida no encontrada: $t"; pf_fail=1
  fi
done
if have python3 && python3 -c 'import sys' >/dev/null 2>&1; then PY=python3
elif have python && python -c 'import sys; assert sys.version_info[0]==3' >/dev/null 2>&1; then PY=python
else PY=""; fi
info "python3 (opcional, receptor OTLP en modo local): ${PY:-no disponible}"
ENV_SUMMARY+=("python3 (opcional)|${PY:-no disponible}")
if [ -d .git ] || git rev-parse --git-dir >/dev/null 2>&1; then
  GIT_HEAD="$(git rev-parse --short HEAD 2>/dev/null)"; GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
  info "git: rama $GIT_BRANCH, HEAD $GIT_HEAD"
  ENV_SUMMARY+=("Commit|$GIT_BRANCH @ $GIT_HEAD")
else
  GIT_HEAD="n/a"; warn "no es un repositorio git (R13 no podra ejecutar test-hooks.sh)"
fi
avail_kb="$(df -Pk "$ROOT" 2>/dev/null | awk 'NR==2{print $4}')"
if [ -n "$avail_kb" ]; then
  info "espacio libre en el disco del repo: $((avail_kb / 1024)) MB"
  ENV_SUMMARY+=("Espacio libre (repo)|$((avail_kb / 1024)) MB")
  if [ "$avail_kb" -lt 1572864 ]; then warn "menos de 1.5 GB libres: la descarga de JDK/PostgreSQL/dependencias puede fallar"; fi
fi
proxy_desc=""
for v in HTTPS_PROXY https_proxy HTTP_PROXY http_proxy NO_PROXY no_proxy; do
  val="$(eval "printf '%s' \"\${$v:-}\"")"
  [ -n "$val" ] && proxy_desc="$proxy_desc $v=$(printf '%s' "$val" | sed -E 's#//[^@/]*@#//***@#')"
done
info "proxy: ${proxy_desc:- ninguno}"
ENV_SUMMARY+=("Proxy|${proxy_desc:-ninguno}")
if [ -n "$proxy_desc" ]; then
  warn "con proxy, Maven necesita <proxies> en ~/.m2/settings.xml (curl usa las variables de entorno)"
fi
# Mode
DOCKER_OK=0
if have docker && docker info >/dev/null 2>&1; then DOCKER_OK=1; fi
if [ "$MODE" = auto ]; then
  if [ "$DOCKER_OK" -eq 1 ]; then EFFECTIVE_MODE=docker; else EFFECTIVE_MODE=local; fi
  info "modo auto -> $EFFECTIVE_MODE (docker info: $([ $DOCKER_OK -eq 1 ] && echo responde || echo 'no disponible'))"
elif [ "$MODE" = docker ] && [ "$DOCKER_OK" -eq 0 ]; then
  fatal "se pidio --mode docker pero 'docker info' no responde (Docker no instalado o el daemon no corre)" \
    "inicie Docker Desktop/daemon o ejecute ./install.sh --mode local"
  pf_fail=1
fi
ENV_SUMMARY+=("Modo|$EFFECTIVE_MODE (solicitado: $MODE)")
# Previous run left running? stop it first (idempotent re-runs).
if [ "$(state_get RUNNING)" = "1" ]; then
  warn "una ejecucion previa quedo corriendo (--keep-running); se detiene antes de continuar"
  case "$(state_get MODE)" in docker) [ "$DOCKER_OK" -eq 1 ] && stop_docker ;; *) stop_local ;; esac
fi
# Ports
pick_port() { # var-name preferred label
  local p; p="$(free_port "$2")"
  if [ "$p" != "$2" ]; then warn "puerto $2 ($3) ocupado -> se usa $p"; phase_note "puerto $3 $2 ocupado, se usa $p"; else info "puerto $p ($3) libre"; fi
  eval "$1=$p"
}
pick_port APP_PORT "$REQ_APP_PORT" "app HTTP"
if [ "$EFFECTIVE_MODE" = docker ]; then
  pick_port PG_PORT 5432 "PostgreSQL"
  pick_port JAEGER_UI_PORT 16686 "Jaeger UI"
  pick_port OTLP_GRPC_PORT 4317 "OTLP gRPC"
  pick_port OTLP_HTTP_PORT 4318 "OTLP HTTP"
else
  pick_port PG_PORT 5432 "PostgreSQL"
  [ "$PG_PORT" = "$APP_PORT" ] && PG_PORT="$(free_port $((PG_PORT + 1)))"
  pick_port SINK_PORT 4318 "receptor OTLP local"
fi
BASE_URL="http://127.0.0.1:$APP_PORT"
if [ "$pf_fail" -eq 1 ]; then
  phase_end FAIL
  [ -n "$FATAL_DIAG" ] || fatal "faltan prerrequisitos del sistema" "instale las herramientas indicadas arriba y vuelva a ejecutar"
  EXIT_CODE=2
else
  phase_end OK
fi

# ---------------------------------------------------------------------- FASE 1: JDK 27 -------
JAVA_VERSION_LINE=""
java_major() { "$1" -version 2>&1 | grep -m1 -E 'version' | sed -E 's/.*version "([0-9]+).*/\1/'; }
jdk_ok() { [ -x "$1/bin/java$EXE" ] && [ "$(java_major "$1/bin/java$EXE")" = "27" ]; }
find_local_jdk() {
  local base c found=""
  for base in "$DEVTOOLS/jdk" "$(to_unix "${USERPROFILE:-}")/.local/devtools/jdk" /Library/Java/JavaVirtualMachines /usr/lib/jvm; do
    [ -d "$base" ] || continue
    for c in "$base"/*; do
      for cand in "$c" "$c/Contents/Home"; do jdk_ok "$cand" && found="$cand"; done
    done
  done
  [ -n "$found" ] && printf '%s' "$found"
}
to_unix() { if [ -n "$1" ] && have cygpath; then cygpath -u "$1"; else printf '%s' "$1"; fi; }
download_jdk() {
  local os_api="$OS"; [ "$OS" = mac ] && os_api=mac
  local api="https://api.adoptium.net/v3/binary/latest/27/ga/$os_api/$ARCH/jdk/hotspot/normal/eclipse"
  local loc file dl="$DEVTOOLS/downloads" sum expect
  mkdir -p "$dl" "$DEVTOOLS/jdk"
  info "consultando Adoptium: $api"
  loc="$(curl -sSI --retry 3 --retry-delay 3 "$api" | tr -d '\r' | grep -i '^location:' | tail -1 | sed 's/^[^:]*:[[:space:]]*//')"
  [ -n "$loc" ] || { err "Adoptium no devolvio un enlace de descarga"; return 1; }
  file="$(basename "$loc")"; file="${file//%2B/+}"
  info "descargando $file"
  curl -fsSL --retry 3 --retry-delay 5 -o "$dl/$file.part" "$loc" || return 1
  mv -f "$dl/$file.part" "$dl/$file"
  expect="$(curl -fsSL --retry 3 "$loc.sha256.txt" 2>/dev/null | awk '{print $1}')"
  if [ -n "$expect" ]; then
    sum="$(sha_file 256 "$dl/$file")"
    if [ "$sum" != "$expect" ]; then err "checksum SHA-256 no coincide ($sum != $expect)"; rm -f "$dl/$file"; return 1; fi
    ok "checksum SHA-256 verificado"
    phase_note "JDK descargado y SHA-256 verificado"
  elif [ "$INSECURE_DOWNLOAD" -eq 1 ]; then
    warn "Adoptium no publico checksum; se continua SIN verificar (--insecure-download)"
    phase_note "JDK sin checksum (--insecure-download)"
  else
    err "Adoptium no publico checksum SHA-256: se aborta (use --insecure-download para forzar)"
    rm -f "$dl/$file"; return 1
  fi
  local tmp="$DEVTOOLS/jdk/.extract.$$"
  rm -rf "$tmp"; mkdir -p "$tmp"
  case "$file" in
    *.zip) unzip -q "$dl/$file" -d "$tmp" || return 1 ;;
    *) tar -xzf "$dl/$file" -C "$tmp" || return 1 ;;
  esac
  local d; for d in "$tmp"/*; do [ -d "$d" ] && mv -f "$d" "$DEVTOOLS/jdk/" ; done
  rm -rf "$tmp"
}
if [ "$EXIT_CODE" -eq 0 ]; then
  phase_begin 1 "JDK 27"
  JH=""
  if [ -n "${JAVA_HOME:-}" ] && jdk_ok "$(to_unix "$JAVA_HOME")"; then
    JH="$(to_unix "$JAVA_HOME")"; info "JAVA_HOME ya apunta a un JDK 27"
    phase_note "JAVA_HOME existente"
  elif have java && [ "$(java_major java)" = "27" ]     && jdk_ok "$(cd "$(dirname "$(command -v java)")/.." && pwd -P)"; then
    JH="$(cd "$(dirname "$(command -v java)")/.." && pwd -P)"
    info "java 27 en el PATH"
    phase_note "java del PATH"
  else
    [ -n "${JAVA_HOME:-}" ] && info "JAVA_HOME=$(tilde "$JAVA_HOME") no es JDK 27; se busca otro"
    JH="$(find_local_jdk)"
    if [ -n "$JH" ]; then
      info "JDK 27 portable encontrado: $(tilde "$JH") (reutilizado)"
      phase_note "JDK portable reutilizado"
    else
      try=1
      while [ $try -le "$RETRIES" ]; do
        phase_try $try
        info "descargando Temurin 27 (intento $try/$RETRIES)"
        if download_jdk; then JH="$(find_local_jdk)"; [ -n "$JH" ] && break; fi
        warn "descarga del JDK fallida; reintento en $((try * 5))s"
        sleep $((try * 5)); try=$((try + 1))
      done
    fi
  fi
  if [ -n "$JH" ] && jdk_ok "$JH"; then
    export JAVA_HOME="$JH"; export PATH="$JAVA_HOME/bin:$PATH"
    JAVA_VERSION_LINE="$("$JAVA_HOME/bin/java$EXE" -version 2>&1 | tr -d '\r' | sed -n '1p;2p' | tr '\n' ' ')"
    ok "JAVA_HOME=$(tilde "$JAVA_HOME")"
    info "$JAVA_VERSION_LINE"
    ENV_SUMMARY+=("JDK|$JAVA_VERSION_LINE")
    phase_end OK
  else
    phase_end FAIL
    fatal "no hay JDK 27 disponible y la descarga desde Adoptium fallo" \
      "descargue Temurin 27 de https://adoptium.net, descomprimalo en ~/.local/devtools/jdk/ (o exporte JAVA_HOME) y reintente"
    EXIT_CODE=2
  fi
fi

# ---------------------------------------------------------------------- FASE 2: build --------
HOOKS_PID=""
HOOKS_LOG="$LOG_DIR/test-hooks.log"
BUILD_LOG=""
APP_JAR=""
MAVEN_VERSION=""
TESTS_SUMMARY=""
BUILD_OK=0
start_hook_tests() {
  if [ -f scripts/hooks/test-hooks.sh ] && git rev-parse --git-dir >/dev/null 2>&1; then
    (bash scripts/hooks/test-hooks.sh </dev/null >"$HOOKS_LOG" 2>&1; echo "exit=$?" >>"$HOOKS_LOG") &
    HOOKS_PID=$!
  fi
}
selective_clean() { # keeps target/review (REVIEW_OK) and target/install
  rm -rf target/classes target/test-classes target/jacoco.exec target/surefire-reports \
    target/site/jacoco target/maven-status target/generated-sources target/generated-test-sources \
    target/ms-transfer-limits-*.jar target/ms-transfer-limits-*.jar.original 2>/dev/null
}
classify_build_failure() { # log -> category
  local l="$1"
  if grep -qE 'The following files had format violations|spotless[^ ]*:check .*FAILURE|Run .*spotless:apply' "$l"; then echo spotless
  elif grep -qE 'You have [1-9][0-9]* Checkstyle violation|checkstyle[^ ]*:check .*(FAILURE|failed)' "$l"; then echo checkstyle
  elif grep -qiE 'has different root|different roots|Error occurred in starting fork|forked VM terminated|java\.io\.tmpdir|Cannot create (temp|temporary)|AccessDeniedException.*(Temp|tmp)' "$l"; then echo tmpdir
  elif grep -qE 'Could not transfer|Could not resolve|Failed to read artifact descriptor|UnknownHostException|Connection (reset|refused|timed out)|Connect to .* failed|Read timed out|Remote host terminated|Temporary failure in name resolution|Network is unreachable|Could not download|Failed to fetch|curl: \([0-9]+\)' "$l"; then
    if grep -q 'PKIX' "$l"; then echo pkix; else echo network; fi
  elif grep -qE 'Coverage checks have not been met' "$l"; then echo coverage
  elif grep -qE 'There are test failures|Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$l"; then echo tests
  elif grep -qE 'COMPILATION ERROR|cannot access|class file .* (has wrong version|is broken)|bad class file|NoSuchFileException|ZipException|invalid LOC header|error reading' "$l"; then echo stale
  else echo unknown; fi
}
if [ "$EXIT_CODE" -eq 0 ]; then
  phase_begin 2 "Build Maven Wrapper + gate de calidad"
  start_hook_tests
  chmod +x mvnw 2>/dev/null
  export MAVEN_OPTS="${MAVEN_OPTS:-} -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8"
  MAVEN_VERSION="$(./mvnw -v 2>&1 | tr -d '\r' | grep -m1 'Apache Maven' | sed -E 's/ \(.*//')"
  if [ -z "$MAVEN_VERSION" ]; then
    warn "./mvnw -v fallo (descarga de Maven?); se reintenta durante el build"
  else
    info "Maven Wrapper -> $MAVEN_VERSION"
  fi
  EXTRA=()
  if [ "$SKIP_TESTS" -eq 1 ]; then GOAL_ARGS=(verify -DskipTests -Djacoco.skip=true); phase_note "--skip-tests: sin pruebas ni JaCoCo"
  else GOAL_ARGS=(verify); fi
  attempt=1
  while [ $attempt -le "$RETRIES" ]; do
    phase_try $attempt
    selective_clean
    BUILD_LOG="$LOG_DIR/build-attempt$attempt.log"
    info "./mvnw -B -ntp ${GOAL_ARGS[*]} ${EXTRA[*]+${EXTRA[*]}}  (intento $attempt/$RETRIES, log: $(rel "$BUILD_LOG"))"
    t0=$(now_s)
    ./mvnw -B -ntp "${GOAL_ARGS[@]}" ${EXTRA[@]+"${EXTRA[@]}"} </dev/null >"$BUILD_LOG" 2>&1 &
    mpid=$!
    while kill -0 $mpid 2>/dev/null; do
      sleep 5
      el=$(($(now_s) - t0))
      if [ $((el % 30)) -lt 5 ] && [ $el -ge 5 ]; then
        last="$(grep -E '^\[INFO\] --- |Tests run:' "$BUILD_LOG" 2>/dev/null | tail -1 | cut -c1-110)"
        info "  ... ${el}s  ${last}"
      fi
    done
    wait $mpid; rc=$?
    TESTS_SUMMARY="$(grep -E '^\[(INFO|WARNING|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$BUILD_LOG" | tail -1 | sed -E 's/^\[[A-Z]+\] //')"
    if [ $rc -eq 0 ] && grep -q 'BUILD SUCCESS' "$BUILD_LOG"; then
      ok "BUILD SUCCESS en $(($(now_s) - t0))s ${TESTS_SUMMARY:+($TESTS_SUMMARY)}"
      BUILD_OK=1; break
    fi
    cat_="$(classify_build_failure "$BUILD_LOG")"
    err "build fallido (categoria: $cat_). Ultimas lineas de error:"
    grep -E '^\[ERROR\]' "$BUILD_LOG" | grep -vE '\[Help 1\]|^\[ERROR\] *$|Re-run Maven|full stack trace|For more information' | head -15 | sed 's/^/           /' | tee -a "$CONSOLE_LOG"
    case "$cat_" in
      spotless)
        phase_note "violaciones de formato (Spotless): no se reformatea codigo versionado automaticamente"
        fatal "el codigo no cumple Google Java Format (spotless:check)" \
          "ejecute './mvnw spotless:apply', revise el diff, haga commit y vuelva a ejecutar ./install.sh"
        break ;;
      checkstyle)
        phase_note "violaciones de Checkstyle"
        fatal "Checkstyle (google_checks.xml) reporto violaciones" "corrija cada archivo:linea reportado en $(rel "$BUILD_LOG") y reintente"
        break ;;
      coverage)
        phase_note "cobertura < 90 %"
        fatal "jacoco:check fallo: cobertura LINE/BRANCH < 90 %" "agregue pruebas unitarias (ver target/site/jacoco/index.html); no baje umbrales"
        break ;;
      pkix)
        phase_note "error PKIX (certificado de proxy corporativo)"
        fatal "error de certificados TLS (PKIX): probablemente un proxy corporativo inspecciona HTTPS" \
          "importe el certificado raiz del proxy en el cacerts del JDK ($(tilde "$JAVA_HOME")/lib/security/cacerts) y reintente"
        break ;;
      network)
        phase_note "intento $attempt: error de red -> reintento con backoff y -U"
        EXTRA=(-U)
        warn "auto-reparacion: error de red/descarga; reintento con -U en $((attempt * 10))s" ;;
      tmpdir)
        TMPFIX="$HOME/.m2/.ms-transfer-limits-tmp"
        mkdir -p "$TMPFIX"
        export TMP="$(native_path "$TMPFIX")" TEMP="$(native_path "$TMPFIX")" TMPDIR="$TMPFIX"
        export MAVEN_OPTS="$MAVEN_OPTS -Djava.io.tmpdir=$(native_path "$TMPFIX")"
        EXTRA=("-Djava.io.tmpdir=$(native_path "$TMPFIX")")
        phase_note "intento $attempt: error de directorio temporal de Surefire -> TMP/TEMP en el disco de ~/.m2"
        warn "auto-reparacion: TMP/TEMP/java.io.tmpdir -> $(tilde "$TMPFIX") (mismo disco que ~/.m2)" ;;
      stale)
        phase_note "intento $attempt: compilacion/artefactos inconsistentes -> limpieza de target (sin target/review)"
        warn "auto-reparacion: se limpia target/ (conservando target/review e target/install) y se reintenta" ;;
      tests)
        phase_note "intento $attempt: pruebas fallidas -> un reintento por posible prueba intermitente"
        warn "pruebas unitarias fallidas: $TESTS_SUMMARY (ver target/surefire-reports/)"
        if [ $attempt -ge 2 ]; then
          fatal "pruebas unitarias fallidas de forma repetida" "revise target/surefire-reports/ y ejecute ./mvnw -B test -Dtest=Clase#metodo"
          break
        fi ;;
      *)
        phase_note "intento $attempt: fallo no clasificado -> reintento"
        warn "fallo no clasificado; se reintenta" ;;
    esac
    attempt=$((attempt + 1))
    [ $attempt -le "$RETRIES" ] && sleep $(((attempt - 1) * 10))
  done
  if [ "$BUILD_OK" -eq 1 ]; then
    [ -n "$MAVEN_VERSION" ] || MAVEN_VERSION="$(./mvnw -v 2>&1 | tr -d '\r' | grep -m1 'Apache Maven' | sed -E 's/ \(.*//')"
    for j in target/ms-transfer-limits-*.jar; do
      case "$j" in *-plain.jar | *sources.jar | *javadoc.jar) ;; *) [ -f "$j" ] && APP_JAR="$ROOT/$j" ;; esac
    done
    info "artefacto: $(rel "$APP_JAR")"
    phase_end OK
  else
    phase_end FAIL
    [ -n "$FATAL_DIAG" ] || fatal "el build Maven fallo tras $RETRIES intentos" "revise $(rel "$BUILD_LOG") y ejecute ./mvnw -B verify manualmente"
    EXIT_CODE=2
  fi
fi

# ---------------------------------------------------------------------- FASE 3: runtime ------
PG_VERSION=""; PGBIN=""; PGDATA="$STATE_DIR/pgdata"; PG_LOG="$LOG_DIR/postgres.log"
APP_LOG="$LOG_DIR/app.log"
APP_PID=""; SINK_PID=""; SINK_DIR="$STATE_DIR/otlp-sink"
DB_PASSWORD_FILE="$STATE_DIR/secrets/db-password"
rand_secret() {
  local s
  s="$(head -c 48 /dev/urandom 2>/dev/null | od -An -tx1 | tr -d ' \n' | cut -c1-32)"
  [ -n "$s" ] || s="$(date +%s)$RANDOM$RANDOM$RANDOM"
  printf '%s' "$s"
}
zonky_platform() {
  case "$OS-$ARCH" in
    windows-x64) echo windows-amd64 ;;
    linux-x64) echo linux-amd64 ;;
    linux-aarch64) echo linux-arm64v8 ;;
    mac-x64) echo darwin-amd64 ;;
    mac-aarch64) echo darwin-arm64v8 ;;
    *) echo "" ;;
  esac
}
find_local_pg() {
  local d found=""
  for d in "$DEVTOOLS"/pgsql-18.*; do [ -x "$d/bin/pg_ctl$EXE" ] && found="$d"; done
  [ -n "$found" ] && printf '%s' "$found"
}
install_pg() {
  local plat ver meta base jar dl="$DEVTOOLS/downloads" expect sum
  plat="$(zonky_platform)"
  [ -n "$plat" ] || { err "plataforma sin binarios zonky: $OS-$ARCH"; return 1; }
  base="https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-$plat"
  meta="$(curl -fsSL --retry 3 --retry-delay 3 "$base/maven-metadata.xml")" || { err "no se pudo leer $base/maven-metadata.xml"; return 1; }
  ver="$(printf '%s' "$meta" | grep -oE '<version>18\.[0-9]+\.[0-9]+</version>' | sed -E 's/<\/?version>//g' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
  [ -n "$ver" ] || { err "no hay versiones 18.x de embedded-postgres-binaries-$plat"; return 1; }
  jar="embedded-postgres-binaries-$plat-$ver.jar"
  mkdir -p "$dl"
  info "descargando PostgreSQL $ver ($jar) desde Maven Central"
  curl -fsSL --retry 3 --retry-delay 5 -o "$dl/$jar.part" "$base/$ver/$jar" || return 1
  mv -f "$dl/$jar.part" "$dl/$jar"
  local algo verified=0
  for algo in 512 256 1; do
    expect="$(curl -fsSL --retry 3 "$base/$ver/$jar.sha$algo" 2>/dev/null | awk '{print $1}')"
    [ -n "$expect" ] || continue
    sum="$(sha_file "$algo" "$dl/$jar")"
    [ "$sum" = "$expect" ] || { err "SHA-$algo del jar no coincide ($sum != $expect)"; rm -f "$dl/$jar"; return 1; }
    ok "checksum SHA-$algo de Maven Central verificado"
    phase_note "PostgreSQL $ver descargado, SHA-$algo verificado"
    verified=1; break
  done
  if [ "$verified" -eq 0 ]; then
    if [ "$INSECURE_DOWNLOAD" -eq 1 ]; then
      warn "Maven Central no publico checksum; se continua SIN verificar (--insecure-download)"
    else
      err "Maven Central no publico checksum para $jar: se aborta (use --insecure-download para forzar)"
      rm -f "$dl/$jar"; return 1
    fi
  fi
  local dest="$DEVTOOLS/pgsql-$ver" tmp="$DEVTOOLS/.pgsql-extract.$$" txz
  rm -rf "$tmp"; mkdir -p "$tmp"
  txz="$(unzip -Z1 "$dl/$jar" 2>/dev/null | grep -m1 '\.txz$')"
  [ -n "$txz" ] || { err "el jar no contiene un .txz"; rm -rf "$tmp"; return 1; }
  if ! unzip -p "$dl/$jar" "$txz" | tar -xJf - -C "$tmp"; then
    err "no se pudo extraer $txz (se requiere tar con soporte xz)"; rm -rf "$tmp"; return 1
  fi
  [ -x "$tmp/bin/pg_ctl$EXE" ] || { err "binarios de PostgreSQL incompletos"; rm -rf "$tmp"; return 1; }
  rm -rf "$dest"; mv -f "$tmp" "$dest"
}
pg_ctl_() { "$PGBIN/pg_ctl$EXE" "$@"; }
start_pg() { # fresh cluster each run (idempotent): initdb + create db + start
  if [ -f "$PGDATA/postmaster.pid" ]; then
    pg_ctl_ -D "$(native_path "$PGDATA")" -m immediate -w stop >>"$LOG_DIR/postgres-ctl.log" 2>&1
  fi
  rm -rf "$PGDATA"; mkdir -p "$(dirname "$DB_PASSWORD_FILE")"
  [ -s "$DB_PASSWORD_FILE" ] || { rand_secret >"$DB_PASSWORD_FILE"; chmod 600 "$DB_PASSWORD_FILE" 2>/dev/null; }
  info "initdb (usuario transfer, scram-sha-256, UTF8) en $(rel "$PGDATA")"
  "$PGBIN/initdb$EXE" -D "$(native_path "$PGDATA")" -U transfer --pwfile="$(native_path "$DB_PASSWORD_FILE")" \
    -A scram-sha-256 -E UTF8 --locale=C >"$LOG_DIR/initdb.log" 2>&1 || { err "initdb fallo (ver $(rel "$LOG_DIR/initdb.log"))"; tail -5 "$LOG_DIR/initdb.log"; return 1; }
  info "CREATE DATABASE transfer_limits (modo single-user)"
  echo "CREATE DATABASE transfer_limits;" | "$PGBIN/postgres$EXE" --single -D "$(native_path "$PGDATA")" postgres >"$LOG_DIR/createdb.log" 2>&1 \
    || { err "no se pudo crear la base (ver $(rel "$LOG_DIR/createdb.log"))"; return 1; }
  local opts="-p $PG_PORT -h 127.0.0.1"
  if [ "$OS" != windows ]; then mkdir -p "$STATE_DIR/pgsock"; opts="$opts -k $STATE_DIR/pgsock"; fi
  info "pg_ctl start (127.0.0.1:$PG_PORT)"
  if ! pg_ctl_ -D "$(native_path "$PGDATA")" -l "$(native_path "$PG_LOG")" -w -t 60 -o "$opts" start >>"$LOG_DIR/postgres-ctl.log" 2>&1; then
    err "PostgreSQL no arranco. Ultimas lineas de $(rel "$PG_LOG"):"; tail -8 "$PG_LOG" 2>/dev/null | sed 's/^/           /'
    if [ "$OS" != windows ] && [ "$(id -u 2>/dev/null)" = "0" ]; then
      err "PostgreSQL no se ejecuta como root: ejecute el instalador con un usuario normal"
    fi
    return 1
  fi
  state_set PGDATA "$PGDATA"; state_set PGBIN "$PGBIN"
  ok "PostgreSQL $PG_VERSION arriba en 127.0.0.1:$PG_PORT"
}
start_sink() {
  [ -n "$PY" ] || return 0
  rm -rf "$SINK_DIR"; mkdir -p "$SINK_DIR"
  cat >"$SINK_DIR/otlp_sink.py" <<'PYEOF'
# Minimal OTLP/HTTP receiver used by install.sh in local mode (no Jaeger available).
# Stores every exported protobuf payload so the installer can look for the trace ids.
import gzip, http.server, os, sys
port, out, pidfile = int(sys.argv[1]), sys.argv[2], sys.argv[3]
with open(pidfile, "w") as f:
    f.write(str(os.getpid()))
class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(n) if n else b""
        if (self.headers.get("Content-Encoding") or "").lower() == "gzip" and data:
            data = gzip.decompress(data)
        with open(out, "ab") as f:
            f.write(data)
        with open(out + ".requests", "a") as f:
            f.write("%s %d\n" % (self.path, len(data)))
        self.send_response(200)
        self.send_header("Content-Type", "application/x-protobuf")
        self.send_header("Content-Length", "0")
        self.end_headers()
    def log_message(self, *args):
        pass
http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
PYEOF
  (nohup "$PY" -I "$(native_path "$SINK_DIR/otlp_sink.py")" "$SINK_PORT" "$(native_path "$SINK_DIR/traces.bin")" \
    "$(native_path "$SINK_DIR/pid")" </dev/null >"$LOG_DIR/otlp-sink.log" 2>&1 &)
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do [ -s "$SINK_DIR/pid" ] && break; sleep 1; done
  SINK_PID="$(cat "$SINK_DIR/pid" 2>/dev/null)"
  state_set SINK_PID "$SINK_PID"
  [ -n "$SINK_PID" ] && info "receptor OTLP/HTTP local en 127.0.0.1:$SINK_PORT (PID $SINK_PID)"
}
wait_health() { # timeout-seconds [logfile-to-watch]
  local t0 el; t0=$(now_s)
  while :; do
    if curl -s --noproxy '*' -m 5 "$BASE_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then return 0; fi
    el=$(($(now_s) - t0))
    [ $el -ge "$1" ] && return 1
    if [ -n "${2:-}" ] && grep -qE 'APPLICATION FAILED TO START|Application run failed' "$2" 2>/dev/null; then return 1; fi
    sleep 2
  done
}
start_app() { # logfile
  local log="$1" dbpw
  dbpw="$(cat "$DB_PASSWORD_FILE")"
  local otel=""
  [ -n "$SINK_PID" ] && otel="http://127.0.0.1:$SINK_PORT"
  info "java -jar $(basename "$APP_JAR") --server.port=$APP_PORT (log: $(rel "$log"))"
  (
    export DB_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/transfer_limits" DB_USERNAME=transfer DB_PASSWORD="$dbpw"
    export TRACING_SAMPLING_PROBABILITY=1.0
    [ -n "$otel" ] && export OTEL_EXPORTER_OTLP_ENDPOINT="$otel"
    nohup "$JAVA_HOME/bin/java$EXE" -jar "$(native_path "$APP_JAR")" --server.port="$APP_PORT" --server.address=127.0.0.1 \
      --logging.level.org.springframework.web.servlet.DispatcherServlet=DEBUG </dev/null >"$log" 2>&1 &
  )
  local t0 st=1; t0=$(now_s)
  if wait_health 180 "$log"; then st=0; fi
  APP_PID="$(grep -oE 'with PID [0-9]+' "$log" | head -1 | awk '{print $3}')"
  [ -n "$APP_PID" ] || APP_PID="$(pid_on_port "$APP_PORT")"
  state_set APP_PID "$APP_PID"; state_set APP_PORT "$APP_PORT"
  if [ $st -eq 0 ]; then ok "app UP en $(($(now_s) - t0))s (PID ${APP_PID:-?})"; fi
  return $st
}
diagnose_app() { # log -> category
  local l="$1"
  if grep -qE 'Port [0-9]+ (is|was) already in use|Address already in use|BindException' "$l"; then echo port
  elif grep -qE 'password authentication failed|FATAL: .*authentication' "$l"; then echo dbauth
  elif grep -qE 'FlywayException|FlywayValidateException|Validate failed|Migration .* failed' "$l"; then echo flyway
  elif grep -qE 'Connection to .* refused|Connection refused|PSQLException' "$l"; then echo dbdown
  elif grep -qE 'UnsupportedClassVersionError' "$l"; then echo java
  else echo unknown; fi
}
stop_app_only() { [ -n "$APP_PID" ] && kill_pid "$APP_PID"; APP_PID=""; state_set APP_PID ""; sleep 1; }

DOCKER_HEALTH=""
compose_env() {
  export APP_PORT POSTGRES_PORT="$PG_PORT" JAEGER_UI_PORT OTLP_GRPC_PORT OTLP_HTTP_PORT
}
ensure_dotenv() {
  if [ -f .env ]; then info ".env existente reutilizado (no se muestra su contenido)"; return 0; fi
  local pw; pw="$(rand_secret)"
  { echo "# Generated by install.sh $(date '+%Y-%m-%d') - git-ignored, do not commit."
    echo "POSTGRES_USER=transfer"
    echo "POSTGRES_PASSWORD=$pw"; } >.env
  chmod 600 .env 2>/dev/null
  ok ".env creado desde env.example con un POSTGRES_PASSWORD aleatorio (no se imprime)"
  phase_note ".env generado con password aleatorio"
}
app_container_health() {
  local cid; cid="$("${DC[@]}" ps -q app 2>/dev/null | head -1)"
  [ -n "$cid" ] || { echo "missing"; return; }
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo "missing"
}
wait_docker_healthy() { # timeout
  local t0 h; t0=$(now_s)
  while :; do
    h="$(app_container_health)"
    DOCKER_HEALTH="$h"
    [ "$h" = healthy ] && return 0
    case "$h" in exited | dead) return 1 ;; esac
    [ $(($(now_s) - t0)) -ge "$1" ] && return 1
    sleep 5
  done
}

if [ "$EXIT_CODE" -eq 0 ]; then
  phase_begin 3 "Runtime ($EFFECTIVE_MODE)"
  state_set MODE "$EFFECTIVE_MODE"
  rt_ok=0
  if [ "$EFFECTIVE_MODE" = docker ]; then
    detect_compose || { fatal "docker compose no disponible" "instale el plugin docker compose v2"; }
    if [ ${#DC[@]} -gt 0 ]; then
      ensure_dotenv
      try=1
      while [ $try -le "$RETRIES" ]; do
        phase_try $try
        compose_env
        info "${DC[*]} up -d --build (intento $try/$RETRIES; puertos app=$APP_PORT pg=$PG_PORT jaeger=$JAEGER_UI_PORT)"
        if "${DC[@]}" up -d --build >"$LOG_DIR/docker-up-$try.log" 2>&1; then
          RUNTIME_UP=1; state_set RUNNING 1; state_set APP_PORT "$APP_PORT"
          info "esperando healthcheck del contenedor app (max 300s)"
          if wait_docker_healthy 300 && wait_health 60; then ok "contenedor app healthy y /actuator/health UP"; rt_ok=1; break; fi
          err "la app no quedo healthy (estado: $DOCKER_HEALTH). Ultimos logs:"
          "${DC[@]}" logs --tail 40 app 2>&1 | tee -a "$LOG_DIR/docker-app-fail-$try.log" | tail -20 | sed 's/^/           /'
        else
          err "docker compose up fallo:"; tail -15 "$LOG_DIR/docker-up-$try.log" | sed 's/^/           /'
          if grep -qiE 'port is already allocated|address already in use|bind' "$LOG_DIR/docker-up-$try.log"; then
            warn "auto-reparacion: conflicto de puertos -> se eligen puertos nuevos"
            phase_note "intento $try: conflicto de puertos -> puertos nuevos"
            APP_PORT="$(free_port $((APP_PORT + 1)))"; PG_PORT="$(free_port $((PG_PORT + 1)))"
            JAEGER_UI_PORT="$(free_port $((JAEGER_UI_PORT + 1)))"; OTLP_GRPC_PORT="$(free_port $((OTLP_GRPC_PORT + 10)))"
            OTLP_HTTP_PORT="$(free_port $((OTLP_HTTP_PORT + 10)))"; BASE_URL="http://127.0.0.1:$APP_PORT"
          fi
        fi
        if "${DC[@]}" logs app 2>/dev/null | grep -qE 'password authentication failed|FATAL: .*authentication'; then
          warn "diagnostico: PostgreSQL rechaza la contrasena de .env (el volumen se creo con otra)."
          warn "  Los datos NO se borran: restaure la contrasena original en .env o use --reset-db"
          phase_note "intento $try: autenticacion BD rechazada (volumen con otra contrasena)"
        fi
        if [ "$RESET_DB" -eq 1 ]; then
          warn "auto-reparacion: docker compose down -v (--reset-db: borra el volumen) y reintento"
          phase_note "intento $try fallido -> down -v (--reset-db) y reintento"
          "${DC[@]}" down -v >>"$LOG_DIR/docker-down.log" 2>&1
        else
          warn "auto-reparacion: docker compose down (conserva el volumen de datos) y reintento"
          phase_note "intento $try fallido -> down y reintento"
          "${DC[@]}" down >>"$LOG_DIR/docker-down.log" 2>&1
        fi
        try=$((try + 1)); sleep $((try * 5))
      done
      [ $rt_ok -eq 1 ] || fatal "el stack docker compose no quedo saludable tras $RETRIES intentos" \
        "revise '${DC[*]} logs app' y $(rel "$LOG_DIR")/docker-*.log; luego ./install.sh --mode docker"
    fi
  else
    # --- PostgreSQL embebido
    PGBIN_DIR="$(find_local_pg)"
    if [ -n "$PGBIN_DIR" ]; then
      info "PostgreSQL portable reutilizado: $(tilde "$PGBIN_DIR")"
      phase_note "binarios PostgreSQL reutilizados"
    else
      try=1
      while [ $try -le "$RETRIES" ]; do
        if install_pg; then PGBIN_DIR="$(find_local_pg)"; [ -n "$PGBIN_DIR" ] && break; fi
        warn "descarga de PostgreSQL fallida; reintento en $((try * 5))s"; sleep $((try * 5)); try=$((try + 1))
      done
    fi
    if [ -z "$PGBIN_DIR" ]; then
      fatal "no se pudieron obtener los binarios de PostgreSQL (zonky) desde Maven Central" \
        "instale PostgreSQL 16+ manualmente, cree la base transfer_limits y ejecute la app con DB_URL/DB_USERNAME/DB_PASSWORD (README, paso 3)"
    else
      PGBIN="$PGBIN_DIR/bin"; PG_VERSION="${PGBIN_DIR##*pgsql-}"
      pg_up=0; try=1
      while [ $try -le "$RETRIES" ]; do
        phase_try $try
        if start_pg; then pg_up=1; RUNTIME_UP=1; state_set RUNNING 1; break; fi
        warn "auto-reparacion: PostgreSQL no arranco; se elige otro puerto y se recrea el cluster"
        phase_note "intento $try: PostgreSQL no arranco -> nuevo puerto y cluster limpio"
        PG_PORT="$(free_port $((PG_PORT + 1)))"; try=$((try + 1)); sleep 2
      done
      if [ $pg_up -eq 1 ]; then
        RUNTIME_UP=1
        start_sink
        try=1
        while [ $try -le "$RETRIES" ]; do
          phase_try $try
          APP_LOG="$LOG_DIR/app.log"
          if start_app "$APP_LOG"; then rt_ok=1; break; fi
          cause="$(diagnose_app "$APP_LOG")"
          err "la app no respondio UP (causa probable: $cause). Ultimas lineas de $(rel "$APP_LOG"):"
          grep -vE '^\s+at ' "$APP_LOG" | tail -12 | cut -c1-220 | sed 's/^/           /'
          cp -f "$APP_LOG" "$LOG_DIR/app-fail-$try.log" 2>/dev/null
          stop_app_only
          case "$cause" in
            port) APP_PORT="$(free_port $((APP_PORT + 1)))"; BASE_URL="http://127.0.0.1:$APP_PORT"
              warn "auto-reparacion: puerto ocupado -> app en $APP_PORT"; phase_note "intento $try: puerto ocupado -> $APP_PORT" ;;
            dbauth | flyway | dbdown)
              warn "auto-reparacion: problema de base de datos ($cause) -> se recrea el cluster PostgreSQL"
              phase_note "intento $try: $cause -> cluster PostgreSQL recreado"
              start_pg || true ;;
            java) fatal "el jar requiere Java 27 (UnsupportedClassVersionError)" "exporte JAVA_HOME a un JDK 27"; break ;;
            *) phase_note "intento $try: fallo no clasificado -> reintento" ;;
          esac
          try=$((try + 1))
        done
        [ $rt_ok -eq 1 ] || [ -n "$FATAL_DIAG" ] || fatal "la aplicacion no arranco tras $RETRIES intentos" \
          "revise $(rel "$APP_LOG") (puerto, credenciales, Flyway) y ejecute la app a mano (README, paso 3)"
      else
        fatal "PostgreSQL embebido no arranco tras $RETRIES intentos" "revise $(rel "$PG_LOG") y $(rel "$LOG_DIR/initdb.log")"
      fi
    fi
  fi
  if [ $rt_ok -eq 1 ]; then phase_end OK; else phase_end FAIL; EXIT_CODE=2; fi
fi

# ---------------------------------------------------------------------- FASE 4: demo ---------
CONC_STATS=""; REPLAY_STATS=""; COV_LINE=""; COV_BRANCH=""; BOOT_VERSION=""; RUNTIME_JAVA=""
TRACE_IDS=""
HOOKS_SUMMARY=""
uuid_for() { printf '%08x-%04x-4%03x-8%03x-%012x' "$1" "$2" $(($3 % 4096)) $(($4 % 4096)) "$5"; }
is_problem() { # checks RFC 9457 shape of the last response; prints missing fields
  local miss="" f
  case "$HTTP_CTYPE" in application/problem+json*) ;; *) miss="content-type($HTTP_CTYPE)" ;; esac
  for f in type title status detail instance code traceId; do
    printf '%s' "$HTTP_BODY" | grep -q "\"$f\"" || miss="$miss $f"
  done
  printf '%s' "$miss"
}
record_trace() { local t; t="$(json_str traceId "$HTTP_BODY")"; [ -n "$t" ] && TRACE_IDS="$TRACE_IDS $t"; }
problem_check() { # req expected-status label
  local miss; miss="$(is_problem)"
  [ "$HTTP_CODE" = "$2" ]; expect "$1" "$3 -> HTTP $HTTP_CODE (esperado $2)" $?
  [ -z "$miss" ]; expect R4 "$3: application/problem+json con type/title/status/detail/instance/code/traceId${miss:+ (faltan:$miss)}" $?
  record_trace
}
fire_parallel() { # n account channel amount same-id(0/1) outdir
  local n="$1" acc="$2" ch="$3" amt="$4" same="$5" out="$6" i tid pids=""
  rm -rf "$out"; mkdir -p "$out"
  for i in $(seq 1 "$n"); do
    if [ "$same" = 1 ]; then tid="$(uuid_for "$RUN_NUM" 7 7 7 999)"; else tid="$(uuid_for "$RUN_NUM" 3 "$i" 3 "$i")"; fi
    (curl -s --noproxy '*' -m 60 -o "$out/body.$i" -w '%{http_code}
' -X POST -H 'Content-Type: application/json' \
      --data-binary "{\"transferId\":\"$tid\",\"channel\":\"$ch\",\"amount\":$amt,\"currency\":\"MXN\"}" \
      "$BASE_URL/api/v1/accounts/$acc/transfer-validations" >"$out/code.$i" 2>/dev/null) &
    pids="$pids $!"
  done
  for i in $pids; do wait "$i"; done
}
count_codes() { cat "$1"/code.* 2>/dev/null | grep -c "^$2\$" | tr -d ' '; }

if [ "$EXIT_CODE" -eq 0 ]; then
  phase_begin 4 "Demo y verificacion de requisitos R1-R14"
  RUN_NUM=$(( $(date +%s) % 100000000 ))
  RUN_TAG="$(date +%H%M%S)$((RANDOM % 100))"
  ACC="DEMO-$RUN_TAG"; ACC_C="CONC-$RUN_TAG"; ACC_R="REPLAY-$RUN_TAG"
  A="/api/v1/accounts"

  # ---- R14 health
  say ""; info "R14 - salud del servicio"
  http GET /actuator/health
  [ "$HTTP_CODE" = 200 ] && printf '%s' "$HTTP_BODY" | grep -q '"status":"UP"'
  expect R14 "GET /actuator/health -> $HTTP_CODE, status UP" $?
  req_evid R14 "HTTP $HTTP_CODE $(compact "$HTTP_BODY")"

  # ---- R1 versions
  say ""; info "R1 - versiones efectivas"
  BOOT_VERSION="$(unzip -p "$APP_JAR" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' | grep -i '^Spring-Boot-Version:' | sed 's/^[^:]*:[[:space:]]*//')"
  if [ "$EFFECTIVE_MODE" = docker ]; then
    RUNTIME_JAVA="$("${DC[@]}" exec -T app java -version 2>&1 | tr -d '\r' | head -1)"
  else
    RUNTIME_JAVA="$(grep -oE 'using Java [0-9][0-9.]*' "$APP_LOG" | head -1)"
    [ -n "$RUNTIME_JAVA" ] || RUNTIME_JAVA="$JAVA_VERSION_LINE"
  fi
  info "Spring Boot (MANIFEST del jar): ${BOOT_VERSION:-?} | JVM en ejecucion: ${RUNTIME_JAVA:-?}"
  [ "$BOOT_VERSION" = "4.2.0" ]; expect R1 "Spring-Boot-Version del jar = ${BOOT_VERSION:-?} (esperado 4.2.0)" $?
  printf '%s' "$RUNTIME_JAVA $JAVA_VERSION_LINE" | grep -qE '(Java |version ")27'; expect R1 "JVM 27 (${RUNTIME_JAVA:-?})" $?
  grep -q '<java.version>27</java.version>' pom.xml; expect R1 "pom.xml java.version=27" $?
  req_evid R1 "Spring Boot ${BOOT_VERSION:-?}; ${RUNTIME_JAVA:-?}; build JDK: $JAVA_VERSION_LINE"

  # ---- R2 Maven wrapper
  say ""; info "R2 - build con Maven Wrapper"
  [ -x mvnw ] && [ -f .mvn/wrapper/maven-wrapper.properties ]; expect R2 "mvnw + .mvn/wrapper/maven-wrapper.properties presentes" $?
  [ "$BUILD_OK" -eq 1 ]; expect R2 "./mvnw -B verify -> BUILD SUCCESS (${MAVEN_VERSION:-?})" $?
  req_evid R2 "${MAVEN_VERSION:-?} via ./mvnw; $( [ "$SKIP_TESTS" -eq 1 ] && echo 'verify -DskipTests' || echo verify ); log $(rel "$BUILD_LOG")"

  # ---- R3 style
  say ""; info "R3 - Google Java Style"
  grep -qE 'spotless-maven-plugin:[^ ]*:check|spotless:[^ ]*:check' "$BUILD_LOG"; expect R3 "spotless:check ejecutado en verify sin violaciones" $?
  grep -qE 'maven-checkstyle-plugin:[^ ]*:check|checkstyle:[^ ]*:check' "$BUILD_LOG"; expect R3 "checkstyle:check (google_checks.xml) ejecutado sin violaciones" $?
  cs="$(grep -oE 'You have [0-9]+ Checkstyle violations?' "$BUILD_LOG" | tail -1)"
  req_evid R3 "spotless:check + checkstyle:check en verify; ${cs:-0 violaciones}; failOnViolation=true"

  # ---- R11 / R12 / R4 business flow
  say ""; info "R11 - reglas de negocio (cuenta $ACC)"
  http PUT "$A/$ACC/limits/MOBILE_APP" '{"dailyLimit": 50000.00, "perOperationLimit": 20000.00, "currency": "MXN"}'
  [ "$HTTP_CODE" = 200 ] && [ "$(json_str channel "$HTTP_BODY")" = MOBILE_APP ]; expect R11 "PUT limite MOBILE_APP (50,000 diario / 20,000 por operacion) -> $HTTP_CODE" $?
  http PUT "$A/$ACC/limits/WEB" '{"dailyLimit": 30000.00, "perOperationLimit": 10000.00, "currency": "MXN"}'
  [ "$HTTP_CODE" = 200 ] && [ "$(json_str channel "$HTTP_BODY")" = WEB ]; expect R11 "PUT limite WEB -> $HTTP_CODE" $?
  http PUT "$A/$ACC/limits/BRANCH" '{"dailyLimit": 100000.00, "perOperationLimit": 50000.00, "currency": "MXN"}'
  [ "$HTTP_CODE" = 200 ] && [ "$(json_str channel "$HTTP_BODY")" = BRANCH ]; expect R11 "PUT limite BRANCH -> $HTTP_CODE" $?
  http GET "$A/$ACC/limits"
  nch="$(count_of '"channel"' "$HTTP_BODY")"
  [ "$HTTP_CODE" = 200 ] && [ "$nch" = 3 ]; expect R11 "GET limites -> $HTTP_CODE con $nch canales" $?

  T1="$(uuid_for "$RUN_NUM" 1 1 1 1)"
  BODY1="{\"transferId\":\"$T1\",\"channel\":\"MOBILE_APP\",\"amount\":1500.00,\"currency\":\"MXN\"}"
  http POST "$A/$ACC/transfer-validations" "$BODY1"
  first_body="$HTTP_BODY"
  [ "$HTTP_CODE" = 201 ] && [ -n "$HTTP_LOCATION" ] && [ "$(json_str status "$HTTP_BODY")" = APPROVED ]
  expect R11 "POST 1,500.00 -> $HTTP_CODE APPROVED con Location: ${HTTP_LOCATION:-<falta>}" $?
  http POST "$A/$ACC/transfer-validations" "$BODY1"
  [ "$HTTP_CODE" = 200 ] && [ "$HTTP_BODY" = "$first_body" ]; expect R11 "reintento idempotente mismo transferId -> $HTTP_CODE con cuerpo identico" $?

  http POST "$A/$ACC/transfer-validations" "{\"transferId\":\"$(uuid_for "$RUN_NUM" 1 2 1 2)\",\"channel\":\"MOBILE_APP\",\"amount\":25000.00,\"currency\":\"MXN\"}"
  problem_check R11 422 "POST 25,000.00 > limite por operacion"
  det="$(json_str detail "$HTTP_BODY")"
  printf '%s' "$det" | grep -qF 'excede el límite por operación'; expect R11 "detail en espanol: 'excede el límite por operación'" $?
  [ "$(json_str code "$HTTP_BODY")" = PER_OPERATION_LIMIT_EXCEEDED ]; expect R11 "code=PER_OPERATION_LIMIT_EXCEEDED" $?
  printf '%s' "$det" | grep -qF '25,000.00' && printf '%s' "$det" | grep -qF '20,000.00'
  expect R12 "plantilla Commons Text con montos es-MX: \"$det\"" $?
  req_evid R12 "422 por operacion: \"$det\""

  http POST "$A/$ACC/transfer-validations" "{\"transferId\":\"$(uuid_for "$RUN_NUM" 1 3 1 3)\",\"channel\":\"MOBILE_APP\",\"amount\":20000.00,\"currency\":\"MXN\"}"
  [ "$HTTP_CODE" = 201 ]; expect R11 "POST 20,000.00 (acumulado 21,500.00) -> $HTTP_CODE" $?
  http POST "$A/$ACC/transfer-validations" "{\"transferId\":\"$(uuid_for "$RUN_NUM" 1 4 1 4)\",\"channel\":\"MOBILE_APP\",\"amount\":20000.00,\"currency\":\"MXN\"}"
  [ "$HTTP_CODE" = 201 ] && num_eq "$(json_num consumedToday "$HTTP_BODY")" 41500; expect R11 "POST 20,000.00 (acumulado 41,500.00) -> $HTTP_CODE consumedToday=$(json_num consumedToday "$HTTP_BODY")" $?
  http POST "$A/$ACC/transfer-validations" "{\"transferId\":\"$(uuid_for "$RUN_NUM" 1 5 1 5)\",\"channel\":\"MOBILE_APP\",\"amount\":15000.00,\"currency\":\"MXN\"}"
  problem_check R11 422 "POST 15,000.00 > disponible diario 8,500.00"
  det2="$(json_str detail "$HTTP_BODY")"
  printf '%s' "$det2" | grep -qF 'excede el disponible diario'; expect R11 "detail en espanol: 'excede el disponible diario'" $?
  [ "$(json_str code "$HTTP_BODY")" = DAILY_LIMIT_EXCEEDED ]; expect R11 "code=DAILY_LIMIT_EXCEEDED" $?
  printf '%s' "$det2" | grep -qF '8,500.00' && printf '%s' "$det2" | grep -qF '50,000.00' && printf '%s' "$det2" | grep -qF '41,500.00'
  expect R12 "plantilla diaria con disponible/limite/consumido formateados: \"$det2\"" $?
  req_evid R12 "422 diario: \"$det2\""

  http POST "$A/$ACC/transfer-validations" "{\"transferId\":\"$T1\",\"channel\":\"MOBILE_APP\",\"amount\":1600.00,\"currency\":\"MXN\"}"
  problem_check R11 409 "mismo transferId con otro monto (conflicto)"
  [ "$(json_str code "$HTTP_BODY")" = TRANSFER_ID_CONFLICT ]; expect R11 "code=TRANSFER_ID_CONFLICT" $?

  http POST "$A/$ACC/transfer-validations" '{"transferId":"3f1c2b9e-7a4d-4c1e-9b8a-2d6f5e4c3b2a","amount":-5,"currency":"MXN"}'
  problem_check R11 400 "solicitud invalida (sin channel, monto negativo)"
  printf '%s' "$HTTP_BODY" | grep -q '"errors":\['; expect R11 "400 incluye errors[] ($(count_of '"field"' "$HTTP_BODY") campos)" $?

  http GET "$A/NOEXISTE-$RUN_TAG/limits"
  problem_check R11 404 "GET limites de cuenta desconocida"
  http POST "$A/NOEXISTE-$RUN_TAG/transfer-validations" "{\"transferId\":\"$(uuid_for "$RUN_NUM" 1 6 1 6)\",\"channel\":\"WEB\",\"amount\":10.00,\"currency\":\"MXN\"}"
  problem_check R11 404 "POST a cuenta sin limites"

  http PUT "$A/$ACC/limits/WEB" '{"dailyLimit": 100.00, "perOperationLimit": 500.00, "currency": "MXN"}'
  problem_check R11 422 "PUT con limite por operacion > diario"

  http GET "$A/$ACC/limits"
  mob="$(printf '%s' "$HTTP_BODY" | grep -oE '\{[^{}]*"channel":"MOBILE_APP"[^{}]*\}')"
  num_eq "$(json_num consumedToday "$mob")" 41500; expect R11 "GET final: MOBILE_APP consumedToday=$(json_num consumedToday "$mob") (rechazos y reintentos no consumen)" $?
  req_evid R11 "PUT x3 200; POST 201+Location; replay 200 identico; 422 por operacion/diario; 409; 400 errors[]; 404; consumo final 41,500.00"
  req_evid R4 "codigos verificados 400/404/409/422 con application/problem+json y type/title/status/detail/instance/code/traceId"

  # ---- R8 concurrency
  say ""; info "R8 - concurrencia: $CONC_N POST paralelos (transferId distintos) de 300.00 contra limite diario 10,000.00 (cuenta $ACC_C)"
  http PUT "$A/$ACC_C/limits/WEB" '{"dailyLimit": 10000.00, "perOperationLimit": 1000.00, "currency": "MXN"}'
  t0=$(now_ms)
  fire_parallel "$CONC_N" "$ACC_C" WEB 300.00 0 "$WORK/conc"
  t1=$(now_ms)
  c201="$(count_codes "$WORK/conc" 201)"; c422="$(count_codes "$WORK/conc" 422)"
  cother=$((CONC_N - c201 - c422))
  expected_ok=$((10000 / 300)); [ "$expected_ok" -gt "$CONC_N" ] && expected_ok=$CONC_N
  http GET "$A/$ACC_C/limits"
  consumed="$(json_num consumedToday "$HTTP_BODY")"
  info "resultado: 201=$c201 422=$c422 otros=$cother en $((t1 - t0)) ms; consumedToday=$consumed"
  num_le "$consumed" 10000; expect R8 "consumedToday=$consumed <= limite diario 10000.00" $?
  [ "$c201" -eq "$expected_ok" ]; expect R8 "aprobadas=$c201 == floor(10000/300)=$expected_ok" $?
  num_eq "$consumed" "$((c201 * 300))"; expect R8 "consumedToday == aprobadas x 300.00 ($((c201 * 300)))" $?
  [ "$cother" -eq 0 ]; expect R8 "sin respuestas distintas de 201/422 (otros=$cother)" $?
  CONC_STATS="$CONC_N peticiones paralelas en $((t1 - t0)) ms: 201=$c201, 422=$c422, otros=$cother; consumedToday=$consumed (limite 10000.00, esperado $((expected_ok * 300)).00)"
  say ""; info "R8 - idempotencia concurrente: 20 POST paralelos con el MISMO transferId (cuenta $ACC_R)"
  http PUT "$A/$ACC_R/limits/WEB" '{"dailyLimit": 10000.00, "perOperationLimit": 1000.00, "currency": "MXN"}'
  fire_parallel 20 "$ACC_R" WEB 300.00 1 "$WORK/replay"
  r201="$(count_codes "$WORK/replay" 201)"; r200="$(count_codes "$WORK/replay" 200)"; rother=$((20 - r201 - r200))
  http GET "$A/$ACC_R/limits"
  rcons="$(json_num consumedToday "$HTTP_BODY")"
  info "resultado: 201=$r201 200=$r200 otros=$rother; consumedToday=$rcons"
  [ "$r201" -eq 1 ] && [ "$rother" -eq 0 ]; expect R8 "mismo transferId x20: un 201 y 19 replays 200 (201=$r201, 200=$r200, otros=$rother)" $?
  num_eq "$rcons" 300; expect R8 "consumido una sola vez: consumedToday=$rcons" $?
  REPLAY_STATS="20 peticiones paralelas mismo transferId: 201=$r201, 200=$r200, otros=$rother; consumedToday=$rcons (esperado 300.00)"
  req_evid R8 "$CONC_STATS"; req_evid R8 "$REPLAY_STATS"

  # ---- R9 OpenAPI
  say ""; info "R9 - contrato OpenAPI"
  http GET /openapi/openapi.yaml
  oa="$(printf '%s' "$HTTP_BODY" | grep -m1 -E '^openapi:')"
  [ "$HTTP_CODE" = 200 ] && printf '%s' "$oa" | grep -q 'openapi: 3.1'; expect R9 "GET /openapi/openapi.yaml -> $HTTP_CODE ($oa)" $?
  http GET /v3/api-docs
  [ "$HTTP_CODE" = 200 ] && printf '%s' "$HTTP_BODY" | grep -q '"openapi"'; expect R9 "GET /v3/api-docs -> $HTTP_CODE" $?
  sw="$(curl -s --noproxy '*' -m 15 -o /dev/null -w '%{http_code}' -L "$BASE_URL/swagger-ui.html")"
  [ "$sw" = 200 ]; expect R9 "Swagger UI /swagger-ui.html -> $sw" $?
  req_evid R9 "/openapi/openapi.yaml ($oa), /v3/api-docs 200, swagger-ui $sw"

  # ---- R13 harness
  say ""; info "R13 - harness de Claude Code"
  [ -f CLAUDE.md ]; expect R13 "CLAUDE.md presente" $?
  [ -f .claude/settings.json ] && grep -q 'PreToolUse' .claude/settings.json; expect R13 ".claude/settings.json con hooks PreToolUse" $?
  nsk=0; for s in .claude/skills/*/SKILL.md; do [ -f "$s" ] && nsk=$((nsk + 1)); done
  [ "$nsk" -ge 4 ]; expect R13 "skills: $nsk ($(ls .claude/skills 2>/dev/null | tr '\n' ' '))" $?
  [ -f .claude/agents/code-security-reviewer.md ]; expect R13 "subagente .claude/agents/code-security-reviewer.md" $?
  hk=0; for h in scripts/hooks/pre-push-gate.sh scripts/hooks/protect-files.sh scripts/hooks/format-java.sh scripts/hooks/remind-gate.sh .githooks/pre-push; do [ -f "$h" ] && hk=$((hk + 1)); done
  [ "$hk" -eq 5 ]; expect R13 "scripts de hooks presentes ($hk/5) + .githooks/pre-push" $?
  if [ -n "$HOOKS_PID" ]; then
    info "esperando scripts/hooks/test-hooks.sh (iniciado en segundo plano en la fase 2)"
    wait "$HOOKS_PID" 2>/dev/null
    HOOKS_SUMMARY="$(grep -E '^hook tests:' "$HOOKS_LOG" | tail -1)"
    hexit="$(grep -E '^exit=' "$HOOKS_LOG" | tail -1 | cut -d= -f2)"
    [ "$hexit" = 0 ]; expect R13 "bash scripts/hooks/test-hooks.sh -> ${HOOKS_SUMMARY:-sin resumen} (exit ${hexit:-?})" $?
    blocked="$(grep -cE '^PASS  exit=2' "$HOOKS_LOG" | tr -d ' ')"
    info "casos de bloqueo (exit 2) verificados: $blocked"
  else
    check R13 FAIL "no se pudo ejecutar test-hooks.sh (no es un repositorio git)"
  fi
  hp="$(git config --get core.hooksPath 2>/dev/null)"
  req_evid R13 "CLAUDE.md, settings.json, $nsk skills, subagente, $hk hooks; test-hooks: ${HOOKS_SUMMARY:-n/a}; core.hooksPath=${hp:-<no configurado: bash scripts/install-hooks.sh>}"

  # ---- R7 coverage
  say ""; info "R7 - cobertura JaCoCo"
  CSV="target/site/jacoco/jacoco.csv"
  if [ "$SKIP_TESTS" -eq 1 ]; then
    req_skip R7 "omitido por --skip-tests (sin pruebas ni JaCoCo)"
    warn "R7 SKIPPED (--skip-tests)"
  elif [ -f "$CSV" ]; then
    read -r lm lc bm bc <<EOF
$(awk -F, 'NR>1{lm+=$8; lc+=$9; bm+=$6; bc+=$7} END{print lm+0, lc+0, bm+0, bc+0}' "$CSV")
EOF
    COV_LINE="$(awk -v m="$lm" -v c="$lc" 'BEGIN{ if (m+c==0) print "0.00"; else printf "%.2f", 100*c/(m+c)}')"
    COV_BRANCH="$(awk -v m="$bm" -v c="$bc" 'BEGIN{ if (m+c==0) print "0.00"; else printf "%.2f", 100*c/(m+c)}')"
    info "lineas $COV_LINE % ($lc/$((lm + lc)))  ramas $COV_BRANCH % ($bc/$((bm + bc)))"
    num_le 90 "$COV_LINE"; expect R7 "cobertura de lineas $COV_LINE % >= 90 %" $?
    num_le 90 "$COV_BRANCH"; expect R7 "cobertura de ramas $COV_BRANCH % >= 90 %" $?
    req_evid R7 "LINE $COV_LINE % ($lc/$((lm + lc))), BRANCH $COV_BRANCH % ($bc/$((bm + bc))); jacoco:check en verify; ${TESTS_SUMMARY:-}"
  else
    check R7 FAIL "no existe $CSV"
  fi

  # ---- R6 docker
  say ""; info "R6 - imagen Docker"
  if [ "$EFFECTIVE_MODE" = docker ]; then
    img="$(docker image inspect -f '{{.Id}} {{.Size}}' ms-transfer-limits:local 2>/dev/null)"
    [ -n "$img" ]; expect R6 "imagen ms-transfer-limits:local construida (${img:0:19}...)" $?
    h="$(app_container_health)"
    [ "$h" = healthy ]; expect R6 "contenedor app healthy (HEALTHCHECK) -> $h" $?
    usr="$("${DC[@]}" exec -T app id -un 2>/dev/null | tr -d '\r')"
    [ "$usr" = spring ]; expect R6 "contenedor corre como usuario no-root ($usr)" $?
    req_evid R6 "imagen ms-transfer-limits:local; contenedor $h; usuario $usr"
  else
    grep -qE '^FROM eclipse-temurin:27' Dockerfile && grep -q 'HEALTHCHECK' Dockerfile && [ -f compose.yaml ]
    st=$?
    if [ $st -eq 0 ]; then info "estatico: Dockerfile multi-stage (temurin 27, HEALTHCHECK, usuario no-root) y compose.yaml presentes"; fi
    req_skip R6 "Docker no disponible en este equipo (modo local): no se construyo la imagen. Verificacion estatica: Dockerfile (eclipse-temurin:27, HEALTHCHECK, USER spring) y compose.yaml $( [ $st -eq 0 ] && echo presentes || echo 'INCOMPLETOS')"
    warn "R6 SKIPPED (sin Docker)"
  fi

  # ---- R10 persistence + Flyway (+ restart)
  say ""; info "R10 - PostgreSQL + Flyway + persistencia tras reinicio"
  if [ "$EFFECTIVE_MODE" = docker ]; then
    fl="$("${DC[@]}" logs app 2>/dev/null | grep -m1 -oE 'Successfully applied [0-9]+ migrations? to schema "[^"]*"|Schema "[^"]*" is up to date')"
  else
    fl="$(grep -m1 -oE 'Successfully applied [0-9]+ migrations? to schema "[^"]*"|Schema "[^"]*" is up to date' "$APP_LOG")"
  fi
  [ -n "$fl" ]; expect R10 "Flyway: ${fl:-no se encontro la linea de migracion en el log}" $?
  [ "$EFFECTIVE_MODE" = docker ] && dbdesc="PostgreSQL 18 (contenedor postgres:18-alpine)" || dbdesc="PostgreSQL $PG_VERSION embebido (zonky) en 127.0.0.1:$PG_PORT"
  check R10 PASS "health UP con datasource activo: $dbdesc"
  info "reiniciando la aplicacion para comprobar persistencia"
  rs=1
  if [ "$EFFECTIVE_MODE" = docker ]; then
    "${DC[@]}" restart app >>"$LOG_DIR/docker-restart.log" 2>&1 && wait_docker_healthy 300 && wait_health 60 && rs=0
  else
    stop_app_only
    start_app "$LOG_DIR/app-restart.log" && rs=0
  fi
  if [ $rs -eq 0 ]; then
    http GET "$A/$ACC/limits"
    mob="$(printf '%s' "$HTTP_BODY" | grep -oE '\{[^{}]*"channel":"MOBILE_APP"[^{}]*\}')"
    num_eq "$(json_num consumedToday "$mob")" 41500; expect R10 "tras reiniciar la app los datos persisten (MOBILE_APP consumedToday=$(json_num consumedToday "$mob"))" $?
    http POST "$A/$ACC/transfer-validations" "$BODY1"
    [ "$HTTP_CODE" = 200 ]; expect R10 "idempotencia persistida: replay del primer transferId tras reinicio -> $HTTP_CODE" $?
  else
    check R10 FAIL "la app no volvio a UP tras el reinicio"
  fi
  req_evid R10 "$dbdesc; ${fl:-?}; datos y replay sobreviven al reinicio de la app"

  # ---- R5 tracing (after restart wait gives the exporter time)
  say ""; info "R5 - trazas OpenTelemetry"
  ntid=0; for t in $TRACE_IDS; do printf '%s' "$t" | grep -qE '^[0-9a-f]{32}$' && ntid=$((ntid + 1)); done
  [ "$ntid" -gt 0 ]; expect R5 "traceId W3C (32 hex) en $ntid Problem Details" $?
  if [ "$EFFECTIVE_MODE" = docker ]; then
    jt=0; i=0
    while [ $i -lt 8 ]; do
      jbody="$(curl -s --noproxy '*' -m 15 "http://127.0.0.1:$JAEGER_UI_PORT/api/traces?service=ms-transfer-limits&limit=200&lookback=1h")"
      jt="$(count_of '"traceID"' "$jbody")"
      [ "$jt" -gt 0 ] && break; sleep 5; i=$((i + 1))
    done
    [ "$jt" -gt 0 ]; expect R5 "Jaeger API /api/traces?service=ms-transfer-limits -> $jt trazas" $?
    first="$(printf '%s' "$TRACE_IDS" | awk '{print $1}')"
    jc="$(curl -s --noproxy '*' -m 15 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$JAEGER_UI_PORT/api/traces/$first")"
    [ "$jc" = 200 ]; expect R5 "traza $first del Problem Detail encontrada en Jaeger -> $jc" $?
    req_evid R5 "Jaeger: $jt trazas del servicio; traceId de Problem Details localizado ($jc)"
  else
    inlog=0
    for t in $TRACE_IDS; do grep -qF "[$t," "$APP_LOG" && inlog=$((inlog + 1)); done
    [ "$inlog" -gt 0 ]; expect R5 "traceId de las respuestas presente en lineas del log de la app ($inlog/$ntid)" $?
    if [ -n "$SINK_PID" ]; then
      found=0; nreq=0; i=0
      while [ $i -lt 10 ]; do
        nreq="$(wc -l <"$SINK_DIR/traces.bin.requests" 2>/dev/null | tr -d ' ')"; nreq="${nreq:-0}"
        found="$("$PY" -I -c 'import sys
d=open(sys.argv[1],"rb").read()
print(sum(1 for t in sys.argv[2:] if bytes.fromhex(t) in d))' "$(native_path "$SINK_DIR/traces.bin")" $TRACE_IDS 2>/dev/null)"
        found="${found:-0}"
        [ "$found" -gt 0 ] && break; sleep 3; i=$((i + 1))
      done
      [ "$found" -gt 0 ]; expect R5 "exportador OTLP/HTTP: $nreq envios a /v1/traces; $found/$ntid traceId de Problem Details presentes en los spans exportados" $?
      req_evid R5 "traceId en Problem Details ($ntid) y en el log ($inlog); OTLP/HTTP exportado a receptor local ($nreq envios, $found traceId encontrados). Jaeger no disponible sin Docker"
    else
      req_evid R5 "traceId en Problem Details ($ntid) y en el log ($inlog). Jaeger no disponible sin Docker; sin python3 no se verifico el exportador OTLP"
    fi
  fi
  phase_end OK
fi

# ---------------------------------------------------------------------- FASE 5: reporte ------
phase_begin 5 "Reporte"
req_finalize
npass=0; nfail=0; nskip=0
for i in "${!REQ_IDS[@]}"; do
  case "${REQ_STATUS[$i]}" in PASS) npass=$((npass + 1)) ;; SKIPPED) nskip=$((nskip + 1)) ;; *) nfail=$((nfail + 1)) ;; esac
done
if [ "$EXIT_CODE" -eq 2 ]; then VERDICT="FALLIDA (fallo irrecuperable de instalacion)"
elif [ "$nfail" -gt 0 ]; then VERDICT="CON FALLOS ($nfail requisitos en FAIL)"; EXIT_CODE=1
else VERDICT="EXITOSA ($npass PASS, $nskip SKIPPED, 0 FAIL)"; fi
TOTAL_S=$(($(now_s) - RUN_T0))
md_esc() { printf '%s' "$1" | sed 's/|/\\|/g'; }
{
  echo "# Reporte de instalación — MS-TRANSFER-LIMITS"
  echo
  echo "| Campo | Valor |"
  echo "|---|---|"
  echo "| Fecha | $RUN_DATE |"
  echo "| Veredicto | **$VERDICT** |"
  echo "| Modo | \`$EFFECTIVE_MODE\` (solicitado \`$MODE\`)$( [ "$SKIP_TESTS" -eq 1 ] && echo ', `--skip-tests`') |"
  echo "| Duración total | ${TOTAL_S} s |"
  echo "| Comando | \`./install.sh --mode $MODE$( [ "$SKIP_TESTS" -eq 1 ] && echo ' --skip-tests')$( [ "$KEEP_RUNNING" -eq 1 ] && echo ' --keep-running') --retries $RETRIES\` |"
  echo
  echo "## Entorno"
  echo
  echo "| Elemento | Valor |"
  echo "|---|---|"
  for e in "${ENV_SUMMARY[@]}"; do echo "| ${e%%|*} | $(md_esc "$(tilde "${e#*|}")") |"; done
  echo "| Maven | ${MAVEN_VERSION:-n/a} (Maven Wrapper) |"
  echo "| Spring Boot | ${BOOT_VERSION:-n/a} |"
  echo "| JVM en ejecución | ${RUNTIME_JAVA:-n/a} |"
  [ "$EFFECTIVE_MODE" = local ] && echo "| PostgreSQL | ${PG_VERSION:-n/a} (binarios zonky embedded-postgres desde Maven Central), puerto $PG_PORT |"
  echo "| URL de la API | $BASE_URL |"
  echo
  echo "## Fases"
  echo
  echo "| Fase | Estado | Duración | Intentos | Notas / auto-reparación |"
  echo "|---|---|---|---|---|"
  for i in "${!PH_NAMES[@]}"; do
    [ "$i" -eq "$CUR_PH" ] && continue
    echo "| ${PH_NAMES[$i]} | ${PH_STATUS[$i]} | ${PH_DUR[$i]} s | ${PH_TRIES[$i]} | $(md_esc "$(tilde "${PH_NOTE[$i]:-—}")") |"
  done
  echo
  echo "## Requisitos"
  echo
  echo "| ID | Requisito | Estado | Verificaciones | Evidencia |"
  echo "|---|---|---|---|---|"
  for i in "${!REQ_IDS[@]}"; do
    echo "| ${REQ_IDS[$i]} | ${REQ_TITLES[$i]} | **${REQ_STATUS[$i]}** | $((REQ_CHECKS[$i] - REQ_FAILS[$i]))/${REQ_CHECKS[$i]} | $(md_esc "$(tilde "${REQ_EVID[$i]:-—}")") |"
  done
  echo
  echo "## Concurrencia (R8)"
  echo
  echo "- Límite diario 10,000.00 MXN, monto 300.00 MXN: ${CONC_STATS:-n/a}"
  echo "- Idempotencia concurrente: ${REPLAY_STATS:-n/a}"
  echo
  echo "## Cobertura (R7)"
  echo
  if [ -n "$COV_LINE" ]; then
    echo "- Líneas: **$COV_LINE %** · Ramas: **$COV_BRANCH %** (umbral 90 %, \`jacoco:check\` en \`verify\`)"
    echo "- Pruebas: ${TESTS_SUMMARY:-n/a}"
    echo "- Reporte HTML: \`target/site/jacoco/index.html\`"
  else
    echo "- No medida en esta ejecución$( [ "$SKIP_TESTS" -eq 1 ] && echo ' (`--skip-tests`)')."
  fi
  echo
  echo "## Verificaciones detalladas"
  echo
  echo '```text'
  grep -E '^ +(PASS|FAIL) \[' "$CONSOLE_LOG" | sed 's/^ *//' | while IFS= read -r l; do tilde "$l"; echo; done
  echo '```'
  echo
  if [ -n "$FATAL_DIAG" ]; then
    echo "## Diagnóstico del fallo"
    echo
    echo "- **Diagnóstico:** $(tilde "$FATAL_DIAG")"
    echo "- **Siguiente paso:** $(tilde "$FATAL_NEXT")"
    echo
  fi
  echo "## Logs"
  echo
  echo "| Log | Ruta |"
  echo "|---|---|"
  echo "| Consola del instalador | \`$(rel "$CONSOLE_LOG")\` |"
  [ -n "$BUILD_LOG" ] && echo "| Build Maven | \`$(rel "$BUILD_LOG")\` |"
  [ -f "$HOOKS_LOG" ] && echo "| test-hooks.sh | \`$(rel "$HOOKS_LOG")\` |"
  if [ "$EFFECTIVE_MODE" = local ]; then
    echo "| Aplicación | \`$(rel "$APP_LOG")\` (reinicio: \`$(rel "$LOG_DIR/app-restart.log")\`) |"
    echo "| PostgreSQL | \`$(rel "$PG_LOG")\` |"
  else
    echo "| docker compose | \`$(rel "$LOG_DIR")/docker-*.log\` y \`docker compose logs\` |"
  fi
  echo
  if [ "$KEEP_RUNNING" -eq 1 ] && [ "$RUNTIME_UP" -eq 1 ]; then
    echo "El servicio quedó en ejecución (\`--keep-running\`): API $BASE_URL, Swagger UI $BASE_URL/swagger-ui.html. Detener con \`./install.sh --stop\`."
  else
    echo "El entorno de ejecución se detuvo al terminar (use \`--keep-running\` para dejarlo arriba)."
  fi
} >"$REPORT"
ok "reporte: $(rel "$REPORT")"
phase_end OK

# ---------------------------------------------------------------------- FASE 6: limpieza -----
phase_begin 6 "Limpieza"
if [ "$KEEP_RUNNING" -eq 1 ] && [ "$RUNTIME_UP" -eq 1 ]; then
  state_set RUNNING 1
  say "  El servicio queda en ejecucion:"
  say "    API:         $BASE_URL/api/v1/accounts/{accountId}/limits"
  say "    Swagger UI:  $BASE_URL/swagger-ui.html"
  say "    Health:      $BASE_URL/actuator/health"
  if [ "$EFFECTIVE_MODE" = docker ]; then say "    Jaeger UI:   http://127.0.0.1:$JAEGER_UI_PORT"
  else
    say "    Jaeger UI:   no disponible en modo local (sin Docker)"
    say "    PostgreSQL:  127.0.0.1:$PG_PORT, usuario transfer, password en $(rel "$DB_PASSWORD_FILE")"
  fi
  say "  Detener con:  ./install.sh --stop"
  phase_end SKIPPED
else
  cleanup_runtime
  phase_end OK
fi

# Los logs de la app pueden contener ids de cuenta en URIs (DEBUG de DispatcherServlet): se enmascaran.
for f in "$LOG_DIR"/app*.log; do
  [ -f "$f" ] && sed -E -i.bak 's#(/accounts/)[^/ ?"]*([^/ ?"]{4})#\1****\2#g' "$f" && rm -f "$f.bak"
done

# ---------------------------------------------------------------------- resumen --------------
say ""
say "================================ RESUMEN ================================"
printf '%-5s %-52s %s\n' "ID" "Requisito" "Estado" | tee -a "$CONSOLE_LOG"
for i in "${!REQ_IDS[@]}"; do
  s="${REQ_STATUS[$i]}"; c="$C_G"; [ "$s" = SKIPPED ] && c="$C_Y"; [ "$s" = FAIL ] && c="$C_R"
  printf '%-5s %-52s %s\n' "${REQ_IDS[$i]}" "${REQ_TITLES[$i]:0:52}" "$c$s$C_0"
  printf '%-5s %-52s %s\n' "${REQ_IDS[$i]}" "${REQ_TITLES[$i]:0:52}" "$s" >>"$CONSOLE_LOG"
done
say "------------------------------------------------------------------------"
for i in "${!PH_NAMES[@]}"; do say "$(printf '%-48s %-9s %5ss  intentos=%s' "${PH_NAMES[$i]:0:48}" "${PH_STATUS[$i]}" "${PH_DUR[$i]}" "${PH_TRIES[$i]}")"; done
say "------------------------------------------------------------------------"
say "Instalacion: $VERDICT en ${TOTAL_S}s"
say "Reporte:     $(rel "$REPORT")"
if [ -n "$FATAL_DIAG" ]; then
  say "Diagnostico: $FATAL_DIAG"
  say "Siguiente:   $FATAL_NEXT"
  offer_ai_fix
fi
exit "$EXIT_CODE"
