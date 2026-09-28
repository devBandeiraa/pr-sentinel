#!/usr/bin/env bash
# PreToolUse (Bash): impede que um comando de shell leia .env, secrets/ ou *.pem.
#
# As regras de permissions.deny já bloqueiam Read/Write/Edit nesses caminhos, mas
# não alcançam `cat .env` via Bash — este hook fecha esse caminho.
# Sai com 2 para bloquear; a mensagem em stderr explica o motivo.
#
# Comandos que apenas referenciam o arquivo sem imprimir conteúdo (como
# `cp .env.example .env`) continuam permitidos de propósito.
set -uo pipefail

command=$(python -c '
import json, sys
try:
    print(json.load(sys.stdin).get("tool_input", {}).get("command", ""))
except Exception:
    pass
' 2>/dev/null)

[ -n "$command" ] || exit 0

readers='cat|less|more|head|tail|strings|xxd|od|base64|grep|rg|awk|sed|type|Get-Content|gc'
targets='(^|[^.[:alnum:]_-])\.env([[:space:]"'\''|;&]|$)|secrets/|secrets\\|\.pem([[:space:]"'\''|;&]|$)'

if printf '%s' "$command" | grep -qE "(^|[|;&[:space:]])($readers)([[:space:]])" \
   && printf '%s' "$command" | grep -qE "$targets"; then
  echo "Bloqueado: este comando leria um segredo (.env, secrets/ ou *.pem). Use a variável de ambiente correspondente." >&2
  exit 2
fi

exit 0
