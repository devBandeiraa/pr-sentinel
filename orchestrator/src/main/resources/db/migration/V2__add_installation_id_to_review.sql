-- A tabela review guardava owner, repo, pr_number e head_sha, mas não o installation_id —
-- e sem ele não dá para reconstruir o PullRequestRef que vai no pr.review.ready.
--
-- Isso quebraria duas coisas: o evento publicado sairia com installationId zero, e o
-- github-publisher da fase 6 não teria como trocar o id pelo token do GitHub App (ADR-006).
--
-- Migration aditiva em três passos para ser segura com dados existentes: cria a coluna
-- aceitando nulo, preenche, e só então exige NOT NULL.
ALTER TABLE review ADD COLUMN installation_id BIGINT;

UPDATE review SET installation_id = 0 WHERE installation_id IS NULL;

ALTER TABLE review ALTER COLUMN installation_id SET NOT NULL;
