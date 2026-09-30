#!/usr/bin/env bash
# One command to build and run the prototype in a hardened, sandboxed container.
#   ./docker-run.sh          build + serve the demo console on http://localhost:8080 (host loopback only)
#   ./docker-run.sh demo     build + scripted walkthrough, with NO network access at all
#   ./docker-run.sh test     build + self-checks, with NO network access at all (exit code 0 = all passed)
# Only requirement: Docker (Docker Desktop on macOS/Windows, Docker Engine on Linux). No JDK needed on the host.
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-serve}"
IMAGE="meridian-dispute-poc:local"
HOST_PORT="${MERIDIAN_HOST_PORT:-8080}"

case "$MODE" in serve|demo|test) ;; *) echo "Usage: ./docker-run.sh [serve|demo|test]" >&2; exit 2 ;; esac

if ! command -v docker >/dev/null 2>&1; then
  cat >&2 <<'EOF'

  Docker is required and was not found.
    macOS:    install Docker Desktop from https://www.docker.com/products/docker-desktop/ and open it once
    Windows:  install Docker Desktop, then run this script from Git Bash or WSL
    Linux:    install Docker Engine (https://docs.docker.com/engine/install/)
EOF
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker is installed but the Docker engine isn't running. Start Docker Desktop and try again." >&2
  exit 1
fi

echo "› Building image $IMAGE …"
docker build --pull -t "$IMAGE" .

# Sandbox profile applied to every mode:
#   --read-only                    root filesystem is immutable; only a small, non-executable /tmp is writable
#   --cap-drop ALL                 no Linux capabilities (the app needs none: unprivileged ports, no raw sockets)
#   --security-opt no-new-privileges   blocks privilege escalation via setuid binaries
#   --user 10001:10001             unprivileged user (also the image default)
#   --memory / --cpus / --pids-limit   bounded resources
#   --init                         proper signal handling (Ctrl+C) and zombie reaping
SANDBOX=(
  --rm --init
  --read-only --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m
  --cap-drop ALL --security-opt no-new-privileges:true
  --user 10001:10001
  --memory 512m --cpus 1.5 --pids-limit 256
)
TTY=(); if [[ -t 0 && -t 1 ]]; then TTY=(-it); fi

if [[ "$MODE" == "serve" ]]; then
  echo "› Starting sandboxed container. Demo console: http://localhost:${HOST_PORT}  (Ctrl+C to stop)"
  # Published on the host's loopback only (127.0.0.1): not reachable from other machines on your network.
  exec docker run "${SANDBOX[@]}" "${TTY[@]}" --name meridian-dispute-poc \
       -p "127.0.0.1:${HOST_PORT}:8080" "$IMAGE" serve
else
  echo "› Running '$MODE' in a sandboxed container with networking disabled (--network none) …"
  # Every service talks over the container's own loopback, so demo/test need no network at all.
  exec docker run "${SANDBOX[@]}" "${TTY[@]}" --network none "$IMAGE" "$MODE"
fi
