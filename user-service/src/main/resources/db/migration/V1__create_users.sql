CREATE TABLE users (
    user_id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

INSERT INTO users (user_id, status) VALUES
    ('u001', 'ACTIVE'),
    ('u002', 'ACTIVE'),
    ('u003', 'INACTIVE');
