-- Seed initial administrator and engineer accounts for development and testing
INSERT INTO engineer_account (username, password_hash, role, version, created_at) VALUES ('admin', '2048:aGVsaXhjb3J0ZXhzYWx0MQ==:V0DWISnULJpoTO4F7SX7qpvQmUcOjsJXzGDV7DMakuA=', 'ADMIN', 0, CURRENT_TIMESTAMP);
INSERT INTO engineer_account (username, password_hash, role, version, created_at) VALUES ('engineer_1', '2048:aGVsaXhjb3J0ZXhzYWx0Mg==:Ukez5khs1pitrN6WJxhWxfYiMiFEmNKFeYcfH82ta2c=', 'ENGINEER', 0, CURRENT_TIMESTAMP);
