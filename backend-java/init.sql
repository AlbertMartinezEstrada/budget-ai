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
    -- Vol dir dues coses segons on estigui:
    --   a una FULLA, com es mesura: FIXED pel prorrateig, VARIABLE pel gasto
    --     real del mes. NULL compta com a VARIABLE.
    --   a un BLOC de primer nivell, a quina secció del repartiment va: FIXED,
    --     VARIABLE o INCOME. NULL la dedueix de les seves fulles.
    -- INCOME només té sentit a un bloc: marca els diners que entren, que ni es
    -- prorrategen ni es comparen amb un sostre.
    tipus_cost VARCHAR(20) CHECK (tipus_cost IN ('FIXED', 'VARIABLE', 'INCOME'))
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
    -- Un cop els diners surten del compte principal ja estan comptats: el que
    -- facin després (arribar a un altre compte, comprar-hi alguna cosa) mou
    -- saldos però no torna a comptar al pressupost.
    exclos_pressupost BOOLEAN NOT NULL DEFAULT FALSE,
    hash_verificacio VARCHAR(64), -- Per evitar duplicats
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- Columnes per compatibilitat amb el front actual
    categoria VARCHAR(100),
    empresa VARCHAR(255),

    CONSTRAINT transactions_hash_compte_key UNIQUE (hash_verificacio, account_id)
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

-- Regles que s'apliquen soles en importar un extracte.
-- Miren el concepte original i, si hi troben el seu patró, poden marcar el
-- moviment com a ja comptat i assignar-li categoria. S'apliquen a la pantalla
-- de revisió, on encara es pot desmarcar abans de confirmar.
CREATE TABLE IF NOT EXISTS import_rules (
    id BIGSERIAL PRIMARY KEY,
    -- Text a trobar dins del concepte, sense distingir majúscules.
    patro VARCHAR(255) NOT NULL,
    marca_exclos BOOLEAN NOT NULL DEFAULT TRUE,
    -- NULL = la regla no toca la categoria.
    categoria VARCHAR(100),
    notes TEXT,
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Inserció de categories per defecte
--
-- Van en dos passos perquè les subcategories necessiten l'id del seu bloc, i
-- aquest s'assigna en inserir-lo. Mantenir-ho aquí i a
-- migrations/004_blocs_de_repartiment.sql és el preu de tenir una instal·lació
-- nova i una d'existent acabant igual.
-- Blocs de primer nivell. A un bloc, tipus_cost diu a quina secció del
-- repartiment va (FIXED, VARIABLE o INCOME); a null, es dedueix de les fulles.
INSERT INTO categories (nom, tipus_cost) VALUES
('Llar', 'FIXED'), ('Subscripcions', 'FIXED'), ('Assegurances i salut', 'FIXED'),
('Trade Republic', 'VARIABLE'), ('Gastos compartits', 'VARIABLE'),
('Fons d''inversió', 'VARIABLE'), ('Gast mensual', 'VARIABLE'),
('Inversió de risc', 'VARIABLE'), ('Regals i altres', 'VARIABLE'),
('Ingressos', 'INCOME')
ON CONFLICT (nom) DO NOTHING;

-- A una fulla, en canvi, tipus_cost diu com es mesura: un fix pel prorrateig
-- del seu recurrent, un variable pel gasto real del mes. Per això dins d'un
-- bloc fix com "Llar" hi ha fulles variables: el lloguer no es mou, la llum sí.
INSERT INTO categories (nom, parent_id, tipus_cost)
SELECT sub.nom, (SELECT id FROM categories WHERE nom = sub.bloc), sub.natura
FROM (VALUES
    ('Casa', 'Llar', 'FIXED'), ('Internet', 'Llar', 'FIXED'),
    ('Llum', 'Llar', 'VARIABLE'), ('Aigua', 'Llar', 'VARIABLE'),
    ('Claude', 'Subscripcions', 'FIXED'), ('iCloud', 'Subscripcions', 'FIXED'),
    ('Amazon Prime', 'Subscripcions', 'FIXED'), ('Revolut', 'Subscripcions', 'FIXED'),
    ('Telèfon portuguès', 'Subscripcions', 'FIXED'),
    ('Assegurances', 'Assegurances i salut', 'FIXED'),
    ('Medicare', 'Assegurances i salut', 'FIXED'),

    ('Menjar i supermercat', 'Gast mensual', 'VARIABLE'),
    ('Bars i restaurants', 'Gast mensual', 'VARIABLE'),
    -- Separada del supermercat: la compra toca fer-la, demanar sopar es pot
    -- retallar, i amb totes dues al mateix sac el sostre no deia res.
    ('Delivery', 'Gast mensual', 'VARIABLE'),
    ('Transport', 'Gast mensual', 'VARIABLE'), ('Compres i roba', 'Gast mensual', 'VARIABLE'),
    ('Higiene i bellesa', 'Gast mensual', 'VARIABLE'),
    ('Salut i farmàcia', 'Gast mensual', 'VARIABLE'),
    ('Gimnàs i esport', 'Gast mensual', 'VARIABLE'),
    ('Cultura, oci i entreteniment', 'Gast mensual', 'VARIABLE'),
    ('Jocs de taula i videojocs', 'Gast mensual', 'VARIABLE'),
    ('Festa i alcohol', 'Gast mensual', 'VARIABLE'),
    ('Tecnologia', 'Gast mensual', 'VARIABLE'),
    ('Casa i mobiliari', 'Gast mensual', 'VARIABLE'),
    ('Mascotes', 'Gast mensual', 'VARIABLE'), ('Educació', 'Gast mensual', 'VARIABLE'),
    ('Regals i detalls', 'Regals i altres', 'VARIABLE'),
    ('Altres', 'Regals i altres', 'VARIABLE'),
    ('Inversions', 'Fons d''inversió', 'VARIABLE'),
    -- A les fulles d'ingrés, tipus_cost no vol dir res: no es prorrategen ni
    -- es comparen amb un sostre, es mesuren pel que hi ha entrat.
    ('Nòmina', 'Ingressos', NULL), ('Ingressos Altres', 'Ingressos', NULL),
    ('Regals i premis', 'Ingressos', NULL)
) AS sub(nom, bloc, natura)
ON CONFLICT (nom) DO NOTHING;

-- Inserció d'un compte principal per defecte
INSERT INTO accounts (nom, tipus, saldo_actual, color) VALUES
('Compte Principal', 'CORRIENTE', 0.00, '#4CAF50')
ON CONFLICT DO NOTHING;
