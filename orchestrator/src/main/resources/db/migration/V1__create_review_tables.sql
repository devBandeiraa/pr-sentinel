CREATE TABLE review (
    id             UUID PRIMARY KEY,
    delivery_id    VARCHAR(64)  NOT NULL UNIQUE,
    owner          VARCHAR(100) NOT NULL,
    repo           VARCHAR(100) NOT NULL,
    pr_number      INT          NOT NULL,
    head_sha       VARCHAR(40)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    verdict        VARCHAR(30),
    requested_at   TIMESTAMPTZ  NOT NULL,
    completed_at   TIMESTAMPTZ,
    CONSTRAINT ck_review_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'PARTIAL', 'FAILED'))
);

CREATE INDEX idx_review_status_requested ON review (status, requested_at);

CREATE TABLE agent_result (
    id             BIGSERIAL PRIMARY KEY,
    review_id      UUID        NOT NULL REFERENCES review (id),
    agent          VARCHAR(20) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    model          VARCHAR(100),
    input_tokens   BIGINT,
    output_tokens  BIGINT,
    latency_ms     BIGINT,
    error_message  TEXT,
    completed_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_result UNIQUE (review_id, agent)
);

CREATE TABLE finding (
    id             BIGSERIAL PRIMARY KEY,
    agent_result_id BIGINT       NOT NULL REFERENCES agent_result (id),
    file           VARCHAR(500) NOT NULL,
    line           INT          NOT NULL,
    severity       VARCHAR(10)  NOT NULL,
    title          VARCHAR(200) NOT NULL,
    explanation    TEXT         NOT NULL,
    suggestion     TEXT,
    confidence     NUMERIC(3, 2) NOT NULL
);

CREATE INDEX idx_finding_agent_result ON finding (agent_result_id);
