---
name: event-contract
description: Use sempre que for criar ou alterar um evento em common (records de event/ ou routing keys em Topology). Explica como evoluir o contrato sem quebrar consumidores, quando versionar a routing key e o que atualizar junto.
---

# Criar ou alterar um contrato de evento

Os contratos vivem em `common/src/main/java/dev/bandeira/prsentinel/common/event/` e as routing keys
em `messaging/Topology.java`. Eles são compartilhados por todos os serviços — é o ponto do sistema
onde uma mudança descuidada quebra coisas que você não está olhando.

## O problema

Produtor e consumidor rodam em containers separados e **são deployados em momentos diferentes**.
Durante o deploy, versão nova e versão antiga convivem. Além disso, uma fila pode ter mensagens no
formato antigo esperando para serem consumidas quando o consumidor novo subir.

Ou seja: o contrato novo precisa conviver com o antigo. Não existe "trocar os dois ao mesmo tempo".

## A regra

**Só adicione campos opcionais.** Qualquer outra mudança é incompatível.

### Compatível — pode fazer direto

- **Adicionar campo opcional** a um record existente. O consumidor antigo ignora o campo novo; o
  consumidor novo recebe `null` (ou vazio) das mensagens antigas e trata isso.
- **Adicionar um valor novo a um enum**, desde que todo consumidor já trate valor desconhecido sem
  explodir. Cheque antes — `AgentType` e `Severity` são lidos em vários lugares.
- **Adicionar uma routing key nova** e uma fila nova.
- Mudar comentário, javadoc ou nome de parâmetro (o JSON usa o nome do componente do record —
  renomear componente **não** é seguro, ver abaixo).

### Incompatível — exige routing key versionada

- Remover ou renomear campo
- Mudar o tipo de um campo
- Tornar obrigatório um campo que era opcional
- Mudar o significado de um campo existente
- Remover valor de enum

## Como fazer uma mudança incompatível

1. **Crie uma routing key versionada** em `Topology`, mantendo a antiga:

   ```java
   public static final String RK_DIFF_READY = "pr.diff.ready";
   public static final String RK_DIFF_READY_V2 = "pr.diff.ready.v2";
   ```

2. **Crie o record novo** ao lado do antigo (`DiffReadyEventV2`), sem apagar o antigo.

3. **O produtor publica nas duas** routing keys durante a transição.

4. **Migre os consumidores um a um**, cada um ligando sua fila à key nova.

5. **Só remova** a key antiga, o record antigo e a publicação dupla quando **nenhuma fila** estiver
   ligada à antiga e a fila antiga estiver vazia. Confira no painel do RabbitMQ
   (`http://localhost:15672`).

6. Registre `BREAKING CHANGE` no rodapé do commit e uma entrada em `Changed` no CHANGELOG.

Se a mudança for grande o bastante para exigir versionamento, ela provavelmente merece uma ADR
(skill `write-adr`).

## Ao criar um evento novo

1. **Record** em `common/.../event/`, com javadoc de uma linha dizendo quem publica e quando.
2. Use `record`, tipos imutáveis, `List` em vez de array. Sem Lombok, sem setter.
3. Inclua `reviewId` — é a chave de correlação de todo o fluxo.
4. **Routing key** em `Topology`, no padrão `pr.<substantivo>.<particípio>`
   (`pr.review.requested`, `pr.diff.ready`). Atualize o comentário do fluxo no topo da classe.
5. **Fila do consumidor** com `QueueDeclarations.consumerQueue(service, routingKey)` — já cria a DLQ
   e os bindings.
6. Nada de segredo, token ou corpo cru de webhook dentro de evento: ele trafega e é logado.

## O que atualizar junto — sempre

- [ ] `Topology.java`: constante e comentário do fluxo
- [ ] Diagrama Mermaid do `README.md` — ele tem que bater com `Topology`
- [ ] Tabela de módulos do `README.md`, se o fluxo mudou
- [ ] Tabela de consumo/publicação do `CLAUDE.md`
- [ ] `CHANGELOG.md`, em `[Unreleased]`
- [ ] Testes: serialização/desserialização do record e consumo com Testcontainers

## Checklist

- [ ] A mudança é compatível? Se não, tem routing key versionada?
- [ ] Consumidor novo lê mensagem no formato antigo sem quebrar?
- [ ] Consumidor antigo lê mensagem no formato novo sem quebrar?
- [ ] O evento é idempotente do lado do consumidor (entrega é *at least once*)?
- [ ] Nenhum dado sensível no evento
- [ ] Diagrama Mermaid atualizado
- [ ] `BREAKING CHANGE` no commit, se aplicável
