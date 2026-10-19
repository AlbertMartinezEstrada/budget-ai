-- Les sessions obertes: una fila cada vegada que s'entra.
--
-- Abans la sessió era només el token de la cookie, i sortir només esborrava la
-- cookie d'aquell navegador: qui s'hagués copiat el token podia continuar
-- entrant fins que caduqués, set dies després. Ara el token porta
-- l'identificador d'aquesta fila (jti), i sense la fila no serveix.
--
-- Després d'aplicar-la cal tornar a entrar: els tokens d'abans no en porten.
--
-- Es pot aplicar dues vegades: IF NOT EXISTS.
CREATE TABLE IF NOT EXISTS sessions (
    -- L'identificador que porta el token: un UUID aleatori.
    id VARCHAR(36) PRIMARY KEY,
    -- El navegador i el sistema, per reconèixer-la a Configuració.
    user_agent VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Quan caduca el token. Les caducades s'esborren soles cada hora.
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
