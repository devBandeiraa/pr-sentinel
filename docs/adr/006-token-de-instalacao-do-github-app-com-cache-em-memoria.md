# ADR-006 — Token de instalação do GitHub App obtido sob demanda e cacheado em memória

- **Status:** Aceita
- **Data:** 2026-10-19

## Contexto

A fase 3 põe o `diff-fetcher` a falar com a API do GitHub pela primeira vez. Até aqui nenhum serviço
autenticava: o `webhook-gateway` só valida HMAC de uma requisição que chega, e validar assinatura não
exige credencial de saída.

O PR Sentinel é um GitHub App, não um usuário com PAT. Isso foi decidido na fase 1 e aparece no
`docker-compose.yml` (`GITHUB_APP_ID`, chave privada montada como secret) e em `PullRequestRef`, que
carrega `installationId` em todos os eventos desde o início. A consequência é que autenticar não é
"mandar um token no header": o GitHub exige duas etapas.

1. Um **JWT** assinado com a chave privada RSA do App (`RS256`), com `iss` igual ao app id e
   validade máxima de 10 minutos. Esse JWT autentica o App, não a instalação, e só serve para falar
   dos próprios metadados do App.
2. Um **installation access token**, obtido com aquele JWT em
   `POST /app/installations/{installationId}/access_tokens`. É esse token que lê PR e diff, vale
   **1 hora** e tem o escopo das permissões concedidas na instalação.

Dois fatos restringem a solução:

- O token expira em 1 hora, e o rate limit de uma instalação é de 5.000 requisições por hora. Pedir
  token novo a cada evento gasta duas requisições extras só para começar a trabalhar.
- A chave privada que o GitHub entrega ao gerar o App vem em **PKCS#1** (`BEGIN RSA PRIVATE KEY`).
  O Java lê PKCS#8 nativamente, e só.

Vale dizer o volume esperado: dezenas de PRs por dia, um `diff-fetcher` com uma réplica. Não é um
cenário de alta concorrência.

## Decisão

O `diff-fetcher` obtém o installation token sob demanda e guarda em **cache em memória, por
`installationId`**, numa classe `InstallationTokenProvider` na camada `infrastructure`.

**Renovação com folga.** O token é considerado vencido 5 minutos antes do `expires_at` que o GitHub
devolve. A folga existe porque o relógio do container e o do GitHub não são o mesmo, e porque um
token que vence no meio da paginação do diff quebra a busca pela metade.

**Cache por instalação, não global.** A chave é o `installationId`, que vem no evento. Um App pode
estar instalado em vários lugares, e cada instalação tem token e escopo próprios. `ConcurrentHashMap`
dá conta: a disputa é entre poucas threads de consumidor do RabbitMQ.

**A chave privada é lida de arquivo, em PKCS#8.** O caminho vem de
`github.private-key-path` (padrão `/run/secrets/github-app.pem`), montado como secret do Docker — a
chave nunca passa por variável de ambiente, nem por propriedade de aplicação. Converter o PEM
baixado do GitHub é passo de configuração, documentado no README:

```bash
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
  -in github-app.pem -out secrets/github-app.pem
```

Chave em formato errado falha no boot, com mensagem dizendo qual conversão fazer. Falhar ao subir é o
comportamento desejado: a alternativa é descobrir no primeiro PR do dia.

**O JWT é montado com `java.security`,** `SHA256withRSA`, sem biblioteca de JWT. São três partes
concatenadas em Base64 URL e uma assinatura; a validade é de 9 minutos, abaixo do teto de 10 do
GitHub.

**Nada disso vai para log.** Nem a chave, nem o JWT, nem o token, nem trecho deles. O que se registra
é o `installationId` e se o token veio do cache ou da API.

## Alternativas consideradas

**Pedir token novo em cada evento, sem cache.** Elimina estado do processo e qualquer discussão sobre
invalidação. Descartada pelo custo: duas requisições extras por PR, um round-trip de rede antes de
cada busca de diff, e consumo de rate limit que cresce junto com o volume. O ganho de simplicidade é
pequeno diante de um `ConcurrentHashMap` de uma entrada.

**Guardar o token no Postgres, compartilhado entre réplicas.** Faria réplicas do `diff-fetcher`
aproveitarem o token uma da outra. Descartada por desproporcionalidade e por efeito colateral ruim:
coloca credencial viva em repouso num banco que hoje guarda só metadados de review, e cria um
problema de criptografia em repouso que não existia. Com uma réplica e token de 1 hora, o ganho é
quase nulo.

**Um serviço de autenticação separado, emprestando token aos outros.** Resolveria a duplicação
quando o `github-publisher` precisar do mesmo token na fase 6. Descartada por agora: seria um sexto
serviço, com fila e modo de falha próprios, para uma classe de 60 linhas. Quando a fase 6 chegar, a
duplicação prevista é extrair `InstallationTokenProvider` para o `common` — o que exige uma decisão
nova, porque hoje o `common` não conhece HTTP.

**BouncyCastle para ler PKCS#1 direto.** Deixaria a configuração à prova de erro: o PEM baixado do
GitHub funcionaria sem conversão. Descartada porque entrega uma dependência criptográfica grande, com
seu próprio fluxo de CVEs para acompanhar, em troca de remover um comando `openssl` da documentação.

**`nimbus-jose-jwt` para montar o JWT.** Biblioteca madura e evita assinatura escrita à mão.
Descartada porque não resolve o PKCS#1 por si só (ainda exigiria a conversão ou o BouncyCastle), e o
que ela substitui aqui são umas quinze linhas de `java.security` cobertas por teste que verifica a
assinatura com a chave pública correspondente.

## Consequências

**Positivas**

- Uma busca de diff custa uma requisição à API no caminho quente, e não três.
- A credencial de longo prazo (a chave privada) fica em arquivo montado como secret e nunca em
  variável de ambiente, que vaza fácil em `docker inspect`, em log de crash e em dump de ambiente.
- O token, que é a credencial de curta duração, nunca é escrito em disco.
- Configuração errada falha no boot, não no primeiro PR.
- O cache é por instalação, então o App pode ser instalado em vários repositórios ou organizações sem
  mudança de código.

**Negativas**

- **O cache morre no restart.** Depois de subir, o primeiro evento de cada instalação paga as duas
  requisições de autenticação. Aceitável: é uma vez por deploy, por instalação.
- **Réplicas não compartilham token.** Escalar o `diff-fetcher` horizontalmente multiplica as
  chamadas de autenticação pelo número de réplicas. Com o volume de hoje não dói; se doer, a resposta
  é a ADR nova citada acima, não um remendo.
- **O `openssl` entra no caminho de configuração.** Quem seguir o README sem atenção salva o PEM cru
  e o serviço não sobe. É dívida de usabilidade assumida de propósito, para não carregar o
  BouncyCastle.
- **A fase 6 vai duplicar isso.** O `github-publisher` precisa do mesmo token. Fica registrado aqui
  que a duplicação é conhecida e esperada, e que a extração para o `common` é decisão da fase 6 — não
  um descuido para alguém "consertar" antes da hora.
- Revogar a chave privada do App passa a exigir redeploy dos serviços que a montam, porque ela é
  lida uma vez, no boot.
