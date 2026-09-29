-- Registro de entregas já processadas, usado para idempotência (ADR-005).
--
-- delivery_id é chave primária de propósito: a unicidade do banco é a trava de
-- concorrência. A reserva é um INSERT ... ON CONFLICT DO NOTHING, nunca um
-- SELECT seguido de INSERT — esse padrão é um TOCTOU e publica duas vezes
-- quando a mesma entrega chega em paralelo.
--
-- VARCHAR(64) casa com review.delivery_id no orchestrator. O delivery do GitHub
-- é um UUID de 36 caracteres; DeliveryId rejeita acima de 64 antes do banco.
CREATE TABLE webhook_delivery (
    delivery_id VARCHAR(64) PRIMARY KEY,
    review_id   UUID        NOT NULL,
    action      VARCHAR(30) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL
);

-- Sustenta o expurgo por retenção; sem ele o DELETE vira seq scan.
CREATE INDEX idx_webhook_delivery_received_at ON webhook_delivery (received_at);
