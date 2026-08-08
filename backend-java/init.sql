-- Taula de Categories
-- Formen un arbre: una categoria amb fills és un grup i una sense fills és
-- una fulla. Les transaccions només s'assignen a fulles.
CREATE TABLE IF NOT EXISTS categories (
    id BIGSERIAL PRIMARY KEY,
    nom VARCHAR(100) UNIQUE NOT NULL,
    -- Grup al qual pertany. NULL = categoria de primer nivell.
    -- ON DELETE SET NULL: esborrar un grup no ha d'arrossegar els seus fills
    -- ni, per tant, les transaccions que hi pengen.
    parent_id BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    -- FIXED o VARIABLE. Només té sentit a les fulles; als grups es deixa NULL.
    -- Una fulla amb NULL es tracta com a VARIABLE.
    tipus_cost VARCHAR(20) CHECK (tipus_cost IN ('FIXED', 'VARIABLE'))
);

-- Taula d'Empreses
CREATE TABLE IF NOT EXISTS companies (
    id BIGSERIAL PRIMARY KEY,
    nom VARCHAR(255) UNIQUE NOT NULL
);

-- Taula de Comptes (Cuentas bancarias/efectivo)
CREATE TABLE IF NOT EXISTS accounts (
    id BIGSERIAL PRIMARY KEY,
    nom VARCHAR(100) NOT NULL,
    tipus VARCHAR(50) CHECK (tipus IN ('CORRIENTE', 'AHORRO', 'EFECTIVO', 'TARJETA', 'INVERSIONES')) DEFAULT 'CORRIENTE',
    saldo_actual DECIMAL(15, 2) DEFAULT 0.00,
    moneda VARCHAR(5) DEFAULT 'EUR',
    activa BOOLEAN DEFAULT TRUE,
    color VARCHAR(7), -- Per al frontend (hex color)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Taula de Transaccions (Evolució de 'despeses')
CREATE TABLE IF NOT EXISTS transactions (
    id BIGSERIAL PRIMARY KEY,
    data DATE NOT NULL,
    category_id BIGINT REFERENCES categories(id),
    company_id BIGINT REFERENCES companies(id),
    account_id BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
    descripcio_curta TEXT,
    import DECIMAL(15, 2) NOT NULL,
    saldo_resultant DECIMAL(15, 2), -- El 'Saldo' del CSV
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
    id BIGSERIAL PRIMARY KEY,
    category_id BIGINT REFERENCES categories(id) ON DELETE CASCADE,
    quantitat_limit DECIMAL(15, 2) NOT NULL,
    -- Percentatge del sou assignat a la categoria. Si està informat, mana
    -- sobre quantitat_limit: el sostre es recalcula si canvia el sou.
    percentatge DECIMAL(5, 2) CHECK (percentatge IS NULL OR (percentatge >= 0 AND percentatge <= 100)),
    periode_inici DATE NOT NULL,
    periode_fi DATE NOT NULL,
    actiu BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Taula de Metes Financeres
CREATE TABLE IF NOT EXISTS financial_goals (
    id BIGSERIAL PRIMARY KEY,
    nom VARCHAR(150) NOT NULL,
    descripcio TEXT,
    quantitat_objectiu DECIMAL(15, 2) NOT NULL,
    quantitat_actual DECIMAL(15, 2) DEFAULT 0.00,
    data_objectiu DATE,
    completat BOOLEAN DEFAULT FALSE,
    account_id BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Taula de Transaccions Recurrents
CREATE TABLE IF NOT EXISTS recurring_transactions (
    id BIGSERIAL PRIMARY KEY,
    nom VARCHAR(150) NOT NULL,
    category_id BIGINT REFERENCES categories(id),
    company_id BIGINT REFERENCES companies(id),
    import DECIMAL(15, 2) NOT NULL,
    tipus VARCHAR(20) CHECK (tipus IN ('EXPENSE', 'INCOME')) NOT NULL,
    frequencia VARCHAR(50) CHECK (frequencia IN ('DIARIA', 'SETMANAL', 'MENSUAL', 'TRIMESTRAL', 'ANUAL')) NOT NULL,
    proxima_data DATE NOT NULL,
    account_id BIGINT REFERENCES accounts(id),
    activa BOOLEAN DEFAULT TRUE,
    descripcio TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Taula de Transferències
CREATE TABLE IF NOT EXISTS transfers (
    id BIGSERIAL PRIMARY KEY,
    account_origen_id BIGINT REFERENCES accounts(id) ON DELETE CASCADE,
    account_desti_id BIGINT REFERENCES accounts(id) ON DELETE CASCADE,
    import DECIMAL(15, 2) NOT NULL,
    data DATE NOT NULL,
    descripcio TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Taula de Configuració
-- Faltava en aquest fitxer: la instal·lació existent la té perquè Hibernate
-- la va crear quan ddl-auto era "update". Amb ddl-auto=validate, una
-- instal·lació nova no arrencava perquè la taula no existia.
CREATE TABLE IF NOT EXISTS settings (
    id SERIAL PRIMARY KEY,
    user_name VARCHAR(255),
    user_email VARCHAR(255),
    currency VARCHAR(255),
    theme VARCHAR(255),
    notifications_expenses BOOLEAN,
    notifications_budget BOOLEAN,
    notifications_monthly BOOLEAN,
    -- Sou de referència sobre el qual s'apliquen els percentatges.
    expected_monthly_income DECIMAL(15, 2),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- Sou d'un mes concret, quan no és el de sempre (paga extra, mes curt...).
-- Si un mes no hi surt, s'aplica el de settings.expected_monthly_income.
-- El període va com a text "YYYY-MM": "any" és paraula reservada de SQL i
-- dues columnes separades s'han de mantenir sincronitzades a cada consulta.
CREATE TABLE IF NOT EXISTS monthly_income (
    id BIGSERIAL PRIMARY KEY,
    periode VARCHAR(7) UNIQUE NOT NULL,
    import DECIMAL(15, 2) NOT NULL,
    notes TEXT
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
