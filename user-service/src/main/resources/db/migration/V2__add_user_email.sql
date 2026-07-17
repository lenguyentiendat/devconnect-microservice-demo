ALTER TABLE users
    ADD COLUMN email VARCHAR(254);

ALTER TABLE users
    ADD COLUMN email_normalized VARCHAR(254)
        GENERATED ALWAYS AS (LOWER(email)) ${emailGeneratedStorage};

CREATE UNIQUE INDEX users_email_lower_unique
    ON users (email_normalized);
