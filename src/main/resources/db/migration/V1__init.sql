CREATE TABLE users (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    email       VARCHAR(255) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    nickname    VARCHAR(100),
    password    VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE teams (
    id           BIGINT GENERATED ALWAYS AS IDENTITY,
    name         VARCHAR(120) NOT NULL,
    invite_code  VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_teams PRIMARY KEY (id),
    CONSTRAINT uk_teams_invite_code UNIQUE (invite_code)
);

CREATE TABLE team_members (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    team_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    joined_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_team_members PRIMARY KEY (id),
    CONSTRAINT uk_team_members UNIQUE (team_id, user_id),
    CONSTRAINT fk_team_members_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_team_members_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE agents (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY,
    team_id                BIGINT       NOT NULL,
    owner_user_id          BIGINT       NOT NULL,
    name                   VARCHAR(100) NOT NULL,
    role_description       VARCHAR(255),
    tool_type              VARCHAR(40)  NOT NULL DEFAULT 'CLAUDE_CODE',
    project_name           VARCHAR(120) NOT NULL,
    connection_token_hash  CHAR(64),
    status                 VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE',
    last_seen_at           TIMESTAMP,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_agents PRIMARY KEY (id),
    CONSTRAINT fk_agents_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_agents_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
);

CREATE TABLE tasks (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    request_key         VARCHAR(64) NOT NULL,
    team_id             BIGINT      NOT NULL,
    requester_agent_id  BIGINT      NOT NULL,
    requester_user_id   BIGINT      NOT NULL,
    receiver_agent_id   BIGINT      NOT NULL,
    prompt              TEXT        NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    result_text         TEXT,
    partially_modified  BOOLEAN     NOT NULL DEFAULT FALSE,
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at        TIMESTAMP,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    CONSTRAINT pk_tasks PRIMARY KEY (id),
    CONSTRAINT uk_tasks_request_key UNIQUE (requester_user_id, request_key)
);

CREATE TABLE task_files (
    id             BIGINT GENERATED ALWAYS AS IDENTITY,
    task_id        BIGINT       NOT NULL,
    relative_path  VARCHAR(500) NOT NULL,
    change_type    VARCHAR(20)  NOT NULL,
    diff_text      TEXT,
    CONSTRAINT pk_task_files PRIMARY KEY (id),
    CONSTRAINT fk_task_files_task FOREIGN KEY (task_id) REFERENCES tasks (id)
);

CREATE TABLE task_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    task_id     BIGINT       NOT NULL,
    event_type  VARCHAR(30)  NOT NULL,
    content     TEXT,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_task_events PRIMARY KEY (id),
    CONSTRAINT fk_task_events_task FOREIGN KEY (task_id) REFERENCES tasks (id)
);

CREATE INDEX ix_task_events_task ON task_events (task_id, created_at);
CREATE INDEX ix_tasks_team ON tasks (team_id, created_at);
