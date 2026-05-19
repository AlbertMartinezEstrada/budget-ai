-- Taula de Categories
CREATE TABLE IF NOT EXISTS categories (
    id SERIAL PRIMARY KEY,
    nom VARCHAR(100) UNIQUE NOT NULL
);

-- Taula d'Empreses
CREATE TABLE IF NOT EXISTS companies (
    id SERIAL PRIMARY KEY,
    nom VARCHAR(255) UNIQUE NOT NULL
);

-- Taula de Comptes (Cuentas bancarias/efectivo)
CREATE TABLE IF NOT EXISTS accounts (
    id SERIAL PRIMARY KEY,
    nom VARCHAR(100) NOT NULL,
    tipus VARCHAR(50) CHECK (tipus IN ('CORRIENTE', 'AHORRO', 'EFECTIVO', 'TARJETA', 'INVERSIONES')) DEFAULT 'CORRIENTE',
    saldo_actual DECIMAL(10, 2) DEFAULT 0.00,
    moneda VARCHAR(5) DEFAULT 'EUR',
    activa BOOLEAN DEFAULT TRUE,
    color VARCHAR(7), -- Per al frontend (hex color)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Taula de Transaccions (Evolució de 'despeses')
CREATE TABLE IF NOT EXISTS transactions (
    id SERIAL PRIMARY KEY,
    data DATE NOT NULL,
    category_id INTEGER REFERENCES categories(id),
    company_id INTEGER REFERENCES companies(id),
    account_id INTEGER REFERENCES accounts(id) ON DELETE SET NULL,
    descripcio_curta TEXT,
    import DECIMAL(10, 2) NOT NULL,
    saldo_resultant DECIMAL(10, 2), -- El 'Saldo' del CSV
    tipus VARCHAR(20) CHECK (tipus IN ('EXPENSE', 'INCOME', 'TRANSFER')),
    concepte_original TEXT,
    compte_nom VARCHAR(100) DEFAULT 'Principal', -- Per si tens diversos comptes
    moneda VARCHAR(5) DEFAULT 'EUR',
    hash_verificacio VARCHAR(64) UNIQUE, -- Per evitar duplicats
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- Columnes per compatibilitat amb el front actual
    categoria VARCHAR(100),
    empresa VARCHAR(255)
);

-- Taula de Pressupostos
CREATE TABLE IF NOT EXISTS budgets (
    id SERIAL PRIMARY KEY,
    category_id INTEGER REFERENCES categories(id) ON DELETE CASCADE,
    quantitat_limit DECIMAL(10, 2) NOT NULL,
    periode_inici DATE NOT NULL,
    periode_fi DATE NOT NULL,
    actiu BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Taula de Metes Financeres
CREATE TABLE IF NOT EXISTS financial_goals (
    id SERIAL PRIMARY KEY,
    nom VARCHAR(150) NOT NULL,
    descripcio TEXT,
    quantitat_objectiu DECIMAL(10, 2) NOT NULL,
    quantitat_actual DECIMAL(10, 2) DEFAULT 0.00,
    data_objectiu DATE,
    completat BOOLEAN DEFAULT FALSE,
    account_id INTEGER REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Taula de Transaccions Recurrents
CREATE TABLE IF NOT EXISTS recurring_transactions (
    id SERIAL PRIMARY KEY,
    nom VARCHAR(150) NOT NULL,
    category_id INTEGER REFERENCES categories(id),
    company_id INTEGER REFERENCES companies(id),
    import DECIMAL(10, 2) NOT NULL,
    tipus VARCHAR(20) CHECK (tipus IN ('EXPENSE', 'INCOME')) NOT NULL,
    frequencia VARCHAR(50) CHECK (frequencia IN ('DIARIA', 'SETMANAL', 'MENSUAL', 'TRIMESTRAL', 'ANUAL')) NOT NULL,
    proxima_data DATE NOT NULL,
    account_id INTEGER REFERENCES accounts(id),
    activa BOOLEAN DEFAULT TRUE,
    descripcio TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Taula de Transferències
CREATE TABLE IF NOT EXISTS transfers (
    id SERIAL PRIMARY KEY,
    account_origen_id INTEGER REFERENCES accounts(id) ON DELETE CASCADE,
    account_desti_id INTEGER REFERENCES accounts(id) ON DELETE CASCADE,
    import DECIMAL(10, 2) NOT NULL,
    data DATE NOT NULL,
    descripcio TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Inserció de categories per defecte
INSERT INTO categories (nom) VALUES
('Menjar i supermercat'), ('Bars i restaurants'), ('Transport'), ('Allotjament'),
('Compres i roba'), ('Higiene i bellesa'), ('Salut i farmàcia'), ('Gimnàs i esport'),
('Cultura, oci i entreteniment'), ('Jocs de taula i videojocs'), ('Festa i alcohol'),
('Tecnologia'), ('Regals i detalls'), ('Casa i mobiliari'), ('Mascotes'),
('Altres'), ('Educació'), ('Inversions'), ('Nòmina'), ('Ingressos Altres')
ON CONFLICT (nom) DO NOTHING;

-- Inserció d'un compte principal per defecte
INSERT INTO accounts (nom, tipus, saldo_actual, color) VALUES
('Compte Principal', 'CORRIENTE', 0.00, '#4CAF50')
ON CONFLICT DO NOTHING;
