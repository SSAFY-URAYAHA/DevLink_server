-- One active connection token maps to exactly one Agent. The unique index also serves
-- the per-request token lookup in DatabaseAgentTokenVerifier. NULL (no token issued) stays allowed.
ALTER TABLE agents ADD CONSTRAINT uk_agents_connection_token_hash UNIQUE (connection_token_hash);
