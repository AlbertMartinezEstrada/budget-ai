# Tests

## Com executar-los

**Backend, tests unitaris** (JUnit 5 + Mockito + AssertJ). Ràpids i sense
dependències externes:

```bash
cd backend-java && ./gradlew test
```

**Backend, tests d'integració**. Aixequen un PostgreSQL de debò amb
Testcontainers, o sigui que **necessiten Docker en marxa**:

```bash
cd backend-java && ./gradlew integrationTest
```

Tots dos de cop:

```bash
cd backend-java && ./gradlew check
```

Estan separats a propòsit: els unitaris han de poder executar-se sempre, també
en una màquina sense Docker o amb el motor aturat.

**Frontend** (executor de tests integrat de Node, sense cap dependència nova):

```bash
cd frontend && npm test
```

O dins del contenidor, que és on hi ha les dependències instal·lades:

```bash
docker exec budget_web npm test
```

Cap dels dos necessita la base de dades ni la xarxa: són ràpids i es poden
executar abans de cada commit.

## Què cobreixen, i per què aquests i no uns altres

Els tests no busquen cobertura ampla, sinó els errors que **ja han passat**.
Cada bloc correspon a una fallada real que va arribar a l'aplicació.

### `JsonContractTest`

Fixa els noms de les propietats JSON que consumeix el frontend.

És el test més important del conjunt. La causa de la majoria d'errors greus va
ser que el frontend llegia camps que el backend no ha produït mai: en
JavaScript, un camp inexistent és `undefined` i no un error, així que la
interfície ensenyava zeros i graelles buides sense que res petés. L'única
manera de detectar-ho era mirar-s'ho a ull.

Si algú reanomena una propietat, aquests tests fallen. **Quan fallin, cal
actualitzar també el codi del frontend que la llegeix**: no n'hi ha prou
d'ajustar l'expectativa del test.

### `BankReaderServiceTest`

El parser d'imports. La versió antiga esborrava tots els punts abans de
convertir la coma en decimal: funcionava amb el format europeu però
multiplicava per 100 qualsevol import anglosaxó (`45.30` → `4530`). Es cobreixen
els cinc formats que pot enviar un banc, i que un import il·legible aturi la
importació en comptes de desar-se com a zero.

### `AccountServiceTest` i `FinancialGoalServiceTest`

Actualitzacions parcials i aritmètica de saldos.

Les entitats tenien valors per defecte als camps (`currentAmount = ZERO`), de
manera que un objecte construït per Jackson a partir d'un cos parcial mai no
arribava amb `null`: era impossible distingir "no m'han enviat aquest camp" de
"me'l volen posar a zero", i editar esborrava dades. Els valors per defecte
s'apliquen ara a `@PrePersist`. **Aquest error el van trobar aquests tests**,
no una revisió del codi.

### `AnalyticsServiceTest`

Les claus que retorna el servei són contracte amb el frontend, i tres d'elles
estaven mal llegides. També comprova que la suma de `291.21 - 262.95` doni
exactament `28.26` i no `28.25999999999999`.

### `AiEngineServiceTest`

Que la resposta de la IA no pugui corrompre les dades del banc. La IA només pot
decidir empresa, categoria i descripció; l'import, la data, el tipus i el hash
de verificació no es toquen mai. Es cobreix el cas en què retorna un nombre de
files diferent del que se li ha enviat.

### `JwtServiceTest`, `LoginThrottleTest`, `AuthCookiesTest`, `LocalCredentialsTest`

La sessió. Que un token amb la signatura manipulada, signat amb una altra clau
o caducat es rebutgi; que la cookie porti sempre `HttpOnly` i `SameSite=Strict`;
que la contrasenya no es desi mai en clar; que faltar una credencial faci
fallar l'arrencada en comptes de deixar l'API oberta; i que el límit d'intents
bloquegi al cinquè error i es desbloquegi sol.

### `frontend/test/api.test.js`

`formatCurrency` amb valors buits i `escapeHtml`. El primer llançava un
`TypeError` amb `undefined` que tombava graelles senceres; el segon és l'única
barrera contra dades del CSV del banc i de la resposta de Gemini.

### `frontend/test/contract.test.js`

Guàrdia contra patrons concrets que ja han fallat: noms de camp inexistents,
URLs del backend escrites a mà fora d'`api.js`, `onclick` amb dades
interpolades, classes de Tailwind construïdes en temps d'execució i vistes que
interpolen dades sense escapar-les.

No comprova que el codi sigui correcte; comprova que no tornin errors coneguts.
Analitza el codi amb els comentaris eliminats, perquè uns quants comentaris
expliquen precisament aquests errors i en citen els noms.

## Tests d'integració

Van a `src/test/java/.../integration/` i porten l'etiqueta `integration`.
Aixequen PostgreSQL amb Testcontainers, no una base de dades en memòria:
l'esquema fa servir `NUMERIC` amb escala fixa, `BIGSERIAL` i claus foranes amb
el comportament de PostgreSQL, i amb H2 comprovarien una cosa diferent de la
que s'executa en producció.

L'esquema surt del mateix `init.sql` que fa servir `docker-compose`, i el
context arrenca amb `ddl-auto=validate`. **Això vol dir que aquests tests també
comproven que `init.sql` quadri amb les entitats**: qualsevol columna que hi
falti o que tingui un tipus diferent fa que el context no arrenqui i que tots
els tests fallin de cop.

Aquesta comprovació va trobar dos errors el mateix dia que es va escriure, tots
dos invisibles a la instal·lació existent perquè les taules les havia creat
Hibernate quan `ddl-auto` era `update`:

1. `init.sql` **no creava la taula `settings`**.
2. Totes les claus primàries eren `SERIAL` (enter) quan les entitats fan servir
   `Long`, i les claus foranes `INTEGER` en comptes de `BIGINT`.

Amb `ddl-auto=validate`, qualsevol dels dos impedia arrencar una instal·lació
nova.

### Què cobreixen

- `TransferIntegrationTest`: que una transferència mogui els diners, que
  esborrar-la els retorni, que cinc cicles seguits no deixin residus decimals i
  que un error a mig camí no mogui ni un cèntim.
- `ConfirmUploadIntegrationTest`: que confirmar desi els moviments i ajusti el
  saldo, i que confirmar dues vegades el mateix lot no dupliqui res.
- `ApiSecurityIntegrationTest`: l'API sencera per HTTP amb la cadena de
  seguretat de debò. Que cap endpoint retorni dades sense cookie.
- `PersistenceIntegrationTest`: l'escala de les columnes `NUMERIC` en anar i
  tornar, els valors per defecte de `@PrePersist`, les actualitzacions parcials
  i les claus foranes.

## Què NO cobreixen

- **No hi ha tests d'extrem a extrem** del navegador.
- **No es comprova la interfície**: que una vista pinti el que toca només està
  verificat a mà.
- Els tests d'integració criden els controllers directament o via MockMvc, no
  contra un servidor amb un port obert.
