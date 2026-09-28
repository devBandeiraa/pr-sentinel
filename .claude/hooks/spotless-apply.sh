#!/usr/bin/env bash
# PostToolUse (Edit|Write): formata com Spotless só o módulo do arquivo .java editado.
# Roda o módulo inteiro em vez do repositório todo porque spotless:apply na raiz
# leva vários segundos a cada edição.
set -uo pipefail

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

file_path=$(python -c '
import json, sys
try:
    print(json.load(sys.stdin).get("tool_input", {}).get("file_path", ""))
except Exception:
    pass
' 2>/dev/null)

[ -n "$file_path" ] || exit 0

case "$file_path" in
  *.java) ;;
  *) exit 0 ;;
esac

# Descobre o módulo pelo caminho, aceitando separador / ou \
module=""
for m in common webhook-gateway diff-fetcher review-agent orchestrator github-publisher; do
  case "$file_path" in
    *"/$m/"*|*"\\$m\\"*) module="$m"; break ;;
  esac
done

[ -n "$module" ] || exit 0

./mvnw -q -pl "$module" spotless:apply >/dev/null 2>&1
exit 0
