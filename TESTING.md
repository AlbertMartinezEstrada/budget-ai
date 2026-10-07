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

### `RepaymentScheduleTest` i `DebtServiceTest`

El calendari de retorn d'un deute i com es compara amb el que s'ha retornat.
Fixen les dues trampes del calendari —l'última quota és el que falta, i els
mesos es compten des del primer pagament perquè el 31 no es perdi al febrer—,
que els pagaments es cobreixen per ordre, i que només descompten els moviments
en sentit de retorn: l'entrada del préstec no.

Al servei, que la reserva del pressupost no passi mai del que quedava per
tornar a l'inici del mes, i que els deutes que em deuen no reservin res.

### `InternalTransferServiceTest`

Els traspassos entre comptes propis. Que entre comptes del dia a dia no
comptin i cap a l'estalvi sí; que mouen el saldo de l'altre compte, i desfer-los
el retorna; que un traspàs al mateix compte es rebutgi; i que, en revisar un
extracte, l'altra pota d'un traspàs ja desat es reconegui només si té el mateix
import, el sentit contrari i dates a tres dies o menys, i que cada traspàs en
reconegui una sola línia.

### `TransactionLinesTest` i `TransactionPartServiceTest`

Un moviment dividit en parts. El primer fixa que d'un moviment dividit surt una
línia per part i que la categoria, l'exclòs i el deute del moviment deixen de
comptar: és el que fan servir el pressupost, els deutes i l'anàlisi, i si
fallés, el moviment comptaria sencer a la categoria d'abans.

El segon, les validacions en dividir: que les parts sumin el total (i el
missatge digui quant falta), que no n'hi hagi una de sola, que cap vagi a un
grup, que no portin un tercer decimal, i que el deute passi del moviment a la
part.

### `ApiErrorHandlerTest` i `ClientErrorsTest`

Què arriba al navegador quan alguna cosa falla: que tots els errors portin
`status`, `message` i `path`, que un error intern no ensenyi el seu text però
porti una referència per trobar-lo al log, i que les validacions pròpies hi
arribin tal qual.

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

### `NamingConventionTest`

Recorre tot `src` i falla si hi troba una variable d'una sola lletra (`t ->`,
`catch (Exception e)`, `Transaction t = ...`). Abans en llegir el codi calia
buscar d'on sortia cada `t` per saber què era. Buida comentaris i literals
abans de mirar-ho, perquè un comentari en català com "t'ho" no la faci saltar.

### `frontend/test/api.test.js`

`formatCurrency` amb valors buits i `escapeHtml`. El primer llançava un
`TypeError` amb `undefined` que tombava graelles senceres; el segon és l'única
barrera contra dades del CSV del banc i de la resposta de Gemini.

### `frontend/test/contract.test.js`

Guàrdia contra patrons concrets que ja han fallat: noms de camp inexistents,
URLs del backend escrites a mà fora d'`api.js`, `onclick` amb dades
interpolades, classes de Tailwind construïdes en temps d'execució i vistes que
interpolen dades sense escapar-les. També que cap variable es digui amb una
sola lletra, i que la pàgina no carregui res d'un altre domini (CDN).

No comprova que el codi sigui correcte; comprova que no tornin errors coneguts.
Analitza el codi amb els comentaris eliminats, perquè uns quants comentaris
expliquen precisament aquests errors i en citen els noms.

### `frontend/test/categoryOptions.test.js`

Els desplegables de categoria: que surtin per seccions en l'ordre del
pressupost i un grup per bloc, que la secció d'un bloc sense secció declarada es
dedueixi de les fulles igual que a `BudgetService`, que per defecte no s'ofereixi
cap bloc (un moviment en un bloc comptaria dues vegades) i que els noms
s'escapin. Abans cada pantalla muntava el seu, desendreçat, i algun oferia
blocs on només hi poden anar fulles.

### `frontend/test/transfers.test.js`

Els traspassos al navegador: que un traspàs entre comptes del dia a dia no
compti i un cap a l'estalvi sí, amb el mateix criteri que el backend (si no, el
formulari diria una cosa i en desar-lo en passaria una altra), que el
desplegable no ofereixi el mateix compte i que els noms s'escapin.

### `frontend/test/assets.test.js`

Tailwind i les fonts es generen amb `npm run build:assets` i es desen a
`public/`. Aquest test torna a compilar Tailwind i compara el resultat amb
`public/css/tailwind.css`, i comprova que les fonts de `public/vendor/` siguin
les dels paquets instal·lats. Una classe nova sense regenerar el CSS no dona
cap error: simplement no té estil. Necessita `npm ci` abans.

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
- `DebtIntegrationTest`: que les devolucions vinculades descomptin del pendent
  i l'entrada del préstec no; que editar un moviment el vinculi, el conservi i
  el desvinculi; que un deute inexistent es rebutgi abans de moure cap saldo;
  que esborrar el deute deixi els moviments; i que el pressupost reservi la
  quota del mes i deixi de fer-ho quan el deute ja està saldat.
- `ApiErrorsIntegrationTest`: els errors per HTTP, amb la sessió de debò. Que
  una ruta desconeguda digui que cal reconstruir el backend, que un moviment o
  un compte que no existeixen diguin quin, que un JSON mal escrit o un
  paràmetre invàlid s'expliquin sense classes de Java, i que les validacions
  que abans tornaven un 400 buit diguin què falla.
- `TransactionPartsIntegrationTest`: la transferència a Trade Republic dividida
  en quatre parts. Que cadascuna compti a la seva categoria i l'exclosa enlloc,
  sense tornar a moure el saldo; que la part vinculada descompti del deute
  només el seu import; que unes parts que no sumen es rebutgin sense canviar
  res; que treure la divisió torni el moviment a com era; que esborrar-lo
  s'emporti les parts; i que el filtre per categoria i l'anàlisi mirin les
  parts. També en importar: que una fila dividida a la revisió entri amb les
  seves parts, que si una no quadra no s'importi res i el missatge digui quina,
  que l'alta manual accepti parts i que l'edició les rebutgi en comptes
  d'ignorar-les.
- `RecurringBudgetIntegrationTest`: que el que es posa a Recurrents surti al
  pressupost. Que un recurrent a la llum (una fulla variable) en sigui el pla i
  es compari amb el que s'hi gasta; que un import del mes continuï manant
  només aquell mes i que copiar el mes anterior no l'arrossegui; que un ingrés
  recurrent sigui la previsió de la nòmina i entri al que es reparteix; que
  editar-lo per HTTP com ho fa la pantalla, sense «activa», no el desactivi ni
  li tregui el compte; i que un recurrent en un bloc, sense categoria o de
  l'altre sentit es rebutgi dient per què. Abans cap d'aquests casos donava
  error: el recurrent simplement no sortia al pressupost.
- `InternalTransfersIntegrationTest`: traspassos entre comptes propis, per HTTP
  com els fa la pantalla. Que el traspàs a Revolut no compti i el que s'hi paga
  sí, a la seva categoria; que el de Trade Republic compti com a estalvi i el
  que en torna el resti sense ser ingrés; que mogui el saldo dels dos comptes i
  esborrar-lo els desfaci; que canviar-ne l'altre compte mogui el diner; que un
  traspàs al mateix compte es rebutgi sense tocar res; que a l'extracte de
  Revolut l'entrada d'un traspàs ja desat surti reconeguda; que una regla el
  marqui sola; i que filtrar per Trade Republic el trobi.
- `MonthlySummaryIntegrationTest`: el resum del mes que pinta Pressupostos.
  Entre d'altres, que l'estalvi es reparteixi després dels fixos i que els
  variables només es quedin el que en sobra, i que les seccions surtin en
  l'ordre en què es reparteixen: ingressos, fixos, estalvi i variables.
- `FixedCostIntegrationTest`: el menú de recurrents de Pressupostos. Que un
  canvi valgui des d'un mes sense tocar els anteriors, que un import posat en un
  sol mes no es copiï als següents, i que s'hi pugui posar la llum, una fulla
  variable, però no un bloc.

## Què NO cobreixen

- **No hi ha tests d'extrem a extrem** del navegador.
- **No es comprova la interfície**: que una vista pinti el que toca només està
  verificat a mà.
- Els tests d'integració criden els controllers directament o via MockMvc, no
  contra un servidor amb un port obert.
