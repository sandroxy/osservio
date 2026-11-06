CREATE TABLE diagnostic_acknowledgements (
    ordinal INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL UNIQUE,
    occurrences INTEGER NOT NULL, processed_at INTEGER NOT NULL
);
INSERT INTO diagnostic_acknowledgements(fingerprint,occurrences,processed_at)
VALUES ('923a8f2122fe21595658f1a860284a9ce6b0f8d8d180d6634cb21a6db9b5191a',1,100);
PRAGMA user_version=4;
