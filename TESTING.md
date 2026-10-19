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

**Prova de navegador** (`frontend/e2e/navegador.mjs`, Playwright). Necessita el
backend i el frontend en marxa i **una base de dades de prova**, perquè importa
moviments:

```bash
cd frontend && E2E_BASE_DE_DADES_DE_PROVA=si E2E_USERNAME=… E2E_PASSWORD=… npm run test:navegador
```

El CI la fa a cada PR amb un PostgreSQL nou (job «Navegador»). Després de les
vistes en clar i en fosc, tria cada idioma a Configuració i torna a recórrer
totes les vistes: falla si el menú no diu el que diu el fitxer de l'idioma, si
falta una traducció (surt com a error a la consola) o si un text no hi cap (la
pàgina s'eixampla o el menú talla un nom). El contrast el mesura amb els colors
que calcula el navegador; els que no surten en `rgb()` (un color amb opacitat
de Tailwind 4 surt en `oklab()`) els pinta en un píxel i els llegeix, en lloc de
prendre'ls per transparents. En tornar a pujar l'extracte, l'avís
que ja hi era (el diu el backend) ha de ser en l'idioma de la pantalla: és la
prova que la capçalera `Accept-Language` arriba.

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

El calendari de retorn d'un deute i què paga cada pagament. Fixen les dues
trampes del calendari —l'última quota és el que falta, i els mesos es compten
des del primer pagament perquè el 31 no es perdi al febrer—, i el cas de la
Steam Deck: un rebut no va endarrerit fins que el seu mes s'acaba, un pagament
paga el rebut triat o el primer sense pagar, uns cèntims de menys no el deixen a
mitges, el que sobra acorta el final (no avança el mes següent), i un rebut es
pot saltar (passa al final) o descomptar del deute. També els períodes setmanal
i trimestral, i que només descompten els moviments en sentit de retorn.

Al servei, que la reserva del pressupost sigui el rebut del mes (cap si està
saldat o el mes s'ha saltat), que els deutes que em deuen no reservin res, i que
un rebut que un pagament ha triat no es pugui treure.

### `InternalTransferServiceTest`

Els traspassos entre comptes propis. Que entre comptes del dia a dia no
comptin i cap a l'estalvi sí; que mouen el saldo de l'altre compte, i desfer-los
el retorna; que un traspàs al mateix compte es rebutgi; i que, en revisar un
extracte, l'altra pota d'un traspàs ja desat es reconegui només si té el mateix
import, el sentit contrari i dates a tres dies o menys, i que cada traspàs en
reconegui una sola línia. També els traspassos sense marcar: que una despesa i
un ingrés del mateix import en comptes diferents, a tres dies o menys, facin un
parell (i no si són del mateix compte, d'una setmana després, dividits, d'un
deute o ja marcats), que cada moviment vagi a un sol parell, el més proper, i
que només s'ajunti una despesa i un ingrés del mateix import. I en quin sentit
mou estalvi un traspàs: cap a l'estalvi suma i en sentit contrari resta, estigui
desat al compte d'on surt o al d'on arriba.

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

També l'extracte de Trade Republic (comes i cometes, import en format de
màquina, compres de valors que no s'importen, dividends i comissions), i que un
fitxer il·legible —unes cometes mal tancades, un format desconegut, una data
dolenta— doni un missatge en català amb la línia i els formats coneguts, no
l'excepció de commons-csv. I que el separador del format clàssic es dedueixi de
la capçalera.

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
exactament `28.26` i no `28.25999999999999`, i que ni els traspassos ni el que
està marcat com a «no comptar» (tampoc una part d'un moviment dividit) surtin al
resum, al desglossament, a la tendència ni a l'any. I l'estalviat, amb el
criteri de `MoneyFlows`: el que va a Estalvis, marcat com a traspàs o no, menys
el que en torna, que no és despesa, amb la seva taxa (i sense taxa quan no hi ha
ingressos); que el que es paga des d'un compte d'estalvi sigui despesa però no
resti del que queda sense destinar; i el resum de tot per al tauler, amb les
cinc categories on més s'ha gastat.

### `MoneyFlowsTest`

L'únic lloc que decideix què és cada línia per al pressupost, l'anàlisi i el
tauler. Que a Estalvi i inversió (també una subcategoria) mani la categoria:
estalvi estigui marcat com a traspàs o no, des de qualsevol de les dues potes, i
el que en torna en resta; que el que es paga des de Trade Republic sigui gastat
del fons, i un regal des de Principal amb una categoria d'estalvi, estalvi. Que
fora d'Estalvi i inversió un ingrés sigui ingrés i una despesa despesa, i que la
part de l'assegurança que s'aparta a Trade Republic compti a Assegurances des
de qualsevol pota (abans l'entrada a Trade Republic en restava). Que el «no
comptar» no sigui res, que d'un moviment dividit cada part vagi per la seva
categoria, i el que queda sense destinar.

### `BudgetReviewServiceTest`

El semàfor del pla contra el real: que a les despeses el dolent sigui passar-se'n
i a l'estalvi i als ingressos quedar-se'n curt; que fins al 10 % (mínim 5 €)
sigui un avís; que un càrrec trimestral o anual surti com a puntual i no com a
desviament; que al mes en curs l'estalvi que encara no hi és vagi «en curs»
però una despesa ja passada no. I el rang: sis mesos per defecte, sense passar
del mes actual, i un rang al revés o de més de 24 mesos rebutjat sense calcular
res.

### `BudgetAdviceServiceTest`

La previsió i les propostes, amb resums del pressupost fets a mà. Que es
proposi pujar a la mitjana quan s'ha passat almenys dues vegades, i baixar al
màxim gastat quan en sobra tots els mesos; que un pla que s'ajusta, un pla que
surt d'un recurrent o un canvi de menys de 5 € no es toquin; que un pla per
percentatge es proposi en percentatge; que els mesos sense moviments no
comptin. I la previsió: el gastat més la mitjana dels dies que queden, o el pla
o el ritme si no hi ha historial, amb el que es pot gastar cada dia.

### `GeneralAnalysisServiceTest`

La pestanya General, amb moviments fets a mà. El rang (el mes actual per
defecte, sense mesos futurs, rebutjat al revés o de més de 36 mesos); el període
anterior del mateix llarg i l'habitual (la mitjana dels mesos amb moviments pel
llarg del període); l'estructura de cada mes (fixos, variables, estalvi i el que
es paga del fons, que no resta del que queda sense destinar); les categories per
blocs, de més a menys, amb el que no està classificat; els comerços per import i
per visites (un moviment dividit és una visita); la despesa formiga (fins a 10 €
i fora dels fixos); i els moviments d'un bloc, amb l'import de cada part.

### `DecisionsServiceTest`

La pestanya Decisions, amb resums del pressupost fets a mà. Els recurrents de
despesa vigents passats a cost anual i ordenats (sense els d'ingrés ni els que
ja s'han acabat); que una subcategoria que puja tres mesos seguits i ja és un
20 % i 10 € per sobre sigui una tendència, i que no ho sigui la que puja poc,
la de pocs euros o la que no puja cada mes, ni res amb menys de quatre mesos;
el simulador (partides amb pla propi o, si no n'hi ha, les fulles on s'ha
gastat, i l'estalvi i els ingressos mitjans); i les metes pendents.

### `AiEngineServiceTest`

Que la resposta de la IA no pugui corrompre les dades del banc. La IA només pot
decidir empresa, categoria i descripció; l'import, la data, el tipus i el hash
de verificació no es toquen mai. Es cobreix el cas en què retorna un nombre de
files diferent del que se li ha enviat.

També la crida a Gemini, amb un servidor simulat (`MockRestServiceServer`): que
la clau vagi a la capçalera `x-goog-api-key` i no a l'adreça, i que si Gemini
falla o respon amb una forma inesperada la importació continuï amb els
moviments tal com eren, sense classificar.

### `JwtServiceTest`, `LoginThrottleTest`, `AuthCookiesTest`, `LocalCredentialsTest`

La sessió. Que un token amb la signatura manipulada, signat amb una altra clau
o caducat es rebutgi; que la cookie porti sempre `HttpOnly` i `SameSite=Strict`;
que la contrasenya no es desi mai en clar; que faltar una credencial faci
fallar l'arrencada en comptes de deixar l'API oberta; i que el límit d'intents
bloquegi al cinquè error i es desbloquegi sol. Cada bloqueig seguit dura el
doble que l'anterior fins al màxim, i un login correcte torna al primer minut.

### `DockerComposeEnvironmentTest`

Que cada variable d'entorn que llegeix `application.properties` es passi al
contenidor del backend a `docker-compose.yml`. Amb Docker, una opció que no hi
és no arriba: posar-la al `.env` no fa res. Va passar amb `CORS_ALLOWED_ORIGINS`
i després amb totes les opcions de sessió (`AUTH_SECURE_COOKIE` inclosa).

### `NamingConventionTest`

Recorre tot `src` i falla si hi troba una variable d'una sola lletra (`t ->`,
`catch (Exception e)`, `Transaction t = ...`). Abans en llegir el codi calia
buscar d'on sortia cada `t` per saber què era. Buida comentaris i literals
abans de mirar-ho, perquè un comentari en català com "t'ho" no la faci saltar.
Com que buida els literals, una segona regla mira a part els àlies de les
consultes de `@Query` (`FROM Budget b`), que viuen dins de les cometes i hi
passaven.

### `MessagesTest`

Els missatges del backend en els quatre idiomes
(`src/main/resources/i18n/messages_xx.properties`), com `i18n.test.js` al
frontend: les mateixes claus i els mateixos `{valors}` que el català, cap clau
repetida, cada clau que fa servir el codi existeix i no en sobra cap. També la
traducció: un missatge dins d'un altre va en el mateix idioma, el plural amb
`count`, els imports amb el format de cada idioma i els identificadors sense
punts de milers («10.000 pagaments» va sortir així en provar-ho), i que l'idioma
sigui el de la petició, amb el català si no n'hi ha o no és un dels quatre.
`ApiErrorsIntegrationTest` ho comprova per HTTP amb la capçalera
`Accept-Language`.

### `SettingsServiceTest`

L'idioma de la interfície es desa als ajustos (`ca`, `es`, `pt` o `en`; buit
vol dir el del navegador) i un altre es rebutja. I que una actualització
parcial no toqui el que no s'ha enviat: els valors per defecte de `Settings`
eren a la declaració dels camps (`theme = "light"`), de manera que desar només
el perfil tornava el tema fosc a clar i les notificacions als valors per
defecte. El cos de la petició es construeix amb Jackson, com al controlador;
comprovat que, amb el valor a la declaració, el test falla.

### `frontend/test/api.test.js`

`formatCurrency` amb valors buits i `escapeHtml`. El primer llançava un
`TypeError` amb `undefined` que tombava graelles senceres; el segon és l'única
barrera contra dades del CSV del banc i de la resposta de Gemini. També que
`formatCurrency` segueixi l'idioma i la moneda: «1.234,57 €» en català,
«€1,234.57» en anglès (abans sortia «1234.57 €» en tots), i que cada petició
porti l'idioma a la capçalera `Accept-Language` sense perdre les de qui crida.

### `frontend/test/i18n.test.js`

Els idiomes. Que els quatre fitxers (`public/lang/Language_xx.properties`)
tinguin exactament les mateixes claus que el català, sense cap repetida; que
cada traducció porti els mateixos `{valors}`; que cap traducció porti
`< > " &` (van tal qual dins de l'HTML); que cada clau que demana el codi
existeixi i que no en sobri cap; que cada crida passi els `{valors}` que el
seu text necessita (si no, sortiria «{name}» a la pantalla); que un text amb
valors no s'interpoli a l'HTML sense `escapeHtml`; i que a cap fitxer de
`public/` no quedi cap text escrit a mà, que sortiria igual en els quatre
idiomes. El
detector de textos escrits a mà té el seu propi test: si deixés de trobar-ne,
l'altre passaria sempre. També el lector de `.properties`, els plurals, com es
tria l'idioma (el desat, el del navegador, el català) i els formats de número,
percentatge i data de cada idioma.

### `frontend/test/contract.test.js`

Guàrdia contra patrons concrets que ja han fallat: noms de camp inexistents,
URLs del backend escrites a mà fora d'`api.js`, `onclick` amb dades
interpolades, classes de Tailwind construïdes en temps d'execució i vistes que
interpolen dades sense escapar-les. També que cap variable es digui amb una
sola lletra (a tot el JavaScript del frontend: la pàgina, els tests, el
servidor i els scripts), que cap missatge d'error s'afegeixi a l'HTML sense
`escapeHtml` (el backend repeteix el que se li envia), i que la pàgina no
carregui res d'un altre domini (CDN).

No comprova que el codi sigui correcte; comprova que no tornin errors coneguts.
Analitza el codi amb els comentaris eliminats, perquè uns quants comentaris
expliquen precisament aquests errors i en citen els noms.

### `frontend/test/categoryOptions.test.js`

Els desplegables de categoria: que surtin per seccions en l'ordre del
pressupost i un grup per bloc, que la secció d'un bloc sense secció declarada es
dedueixi de les fulles igual que a `BudgetService`, que per defecte no s'ofereixi
cap bloc (un moviment en un bloc comptaria dues vegades) i que els noms
s'escapin. Abans cada pantalla muntava el seu, desendreçat, i algun oferia
blocs on només hi poden anar fulles. També que Estalvi i inversió surti com a
secció pròpia, amb el camí de les fulles d'Inversió, i dins de les de despesa.

### `frontend/test/debtReceipts.test.js`

Els rebuts d'un deute al navegador: que es diguin pel mes i no pel dia, que el
desplegable de Transaccions comenci per «el que toca», no ofereixi els rebuts
trets i reconegui el triat pel mes com el backend (`samePeriod`), i que un rebut
triat que ja no és al calendari surti igualment perquè editar el moviment no el
canviï sense voler.

### `frontend/test/transfers.test.js`

Els traspassos al navegador: que un traspàs entre comptes del dia a dia no
compti i un cap a l'estalvi sí, amb el mateix criteri que el backend (si no, el
formulari diria una cosa i en desar-lo en passaria una altra), que el
desplegable no ofereixi el mateix compte i que els noms s'escapin. I l'avís
d'una categoria d'estalvi (`savingsCategoryHint`), amb el criteri de
`MoneyFlows`: des del dia a dia és estalvi, marcat com a traspàs o no, i pagat
des d'un compte d'estalvi és gastat del fons.

### `frontend/test/budgetReview.test.js`

La pestanya Presupuesto de l'anàlisi: que les paraules del semàfor depenguin de
la secció («Pasado» en una despesa, «Corto» en l'estalvi), que la desviació
porti sempre el signe, que els mesos es comptin enrere bé al canvi d'any, el
resum de l'historial de cada bloc i que l'historial vagi per seccions amb el que
més s'ha desviat primer. I les propostes: el perquè de cadascuna, l'efecte net
de les marcades i que en aplicar-les un pla per percentatge s'enviï com a
percentatge.

### `frontend/test/generalAnalysis.test.js`

La pestanya General de l'anàlisi: els períodes (mes, trimestre, any, els últims
12 mesos) i les seves opcions, el nom d'un rang, que el canvi respecte d'una
referència digui si és bo o dolent segons la xifra (més despesa és dolent, més
estalvi és bo) i no doni cap % sobre zero, l'estructura en % dels ingressos (amb
el que s'ha gastat de més a part), que els mesos buits del principi no surtin
al gràfic, que l'eix arribi al 100 % i els colors fixos de cada sèrie.

### `frontend/test/decisions.test.js`

El simulador de la pestanya Decisiones: que retallar una partida alliberi
diners per estalviar i pujar-la en tregui (sense negatius ni camps mal escrits
que canviïn res), quants mesos falten per a una meta i si és a temps, quants
mesos abans s'hi arriba amb la simulació, que aplicar-la enviï només el que
canvia (un percentatge com a percentatge), els recurrents per blocs i el text
de les tendències.

### `frontend/test/darkMode.test.js`

Que el tema fosc no es trenqui en silenci. Que cada classe de color pensada per
al tema clar que fa servir l'aplicació (fons clars, grisos de text, colors amb
significat en to fosc) tingui el seu equivalent a `css/dark.css`; que
`dark.css` es carregui després de Tailwind; que els CSS vagin a les capes de
Tailwind 4 (els propis i les icones a `components` des de `layers.css`,
`dark.css` a `utilities`): fora de cap capa, `.material-symbols-outlined`
trepitjaria un `hidden` de la vista; que les seves traduccions vagin amb
`:where(html.dark)` i no trepitgin una variant `dark:` de la vista; i que els
camps i les taules agafin els colors de les variables del tema, no un blanc
fix. Abans, els camps eren blancs amb el text clar del tema i no es veia què
s'escrivia. També, que la barra lateral (fosca en tots dos temes) no faci servir
colors de text per a fons clar: en clar, els enllaços i el logotip no arribaven
a 2.5:1.

### `frontend/test/lightContrast.test.js`

Que el tema clar es llegeixi: cap text per sota de 4.5:1 (WCAG AA). Que cada
color de text que fa servir l'aplicació (les classes de Tailwind i les pròpies
com `text-success`) arribi a 4.5:1 sobre blanc; que el text d'una pastilla o
d'un botó arribi a 4.5:1 sobre el seu fons (`bg-emerald-100 text-emerald-700`,
`bg-green-700 text-white`…, buscats a totes les vistes); i que els botons propis
amb text blanc (`.btn-success`…) tinguin un fons prou fosc. Abans, el verd dels
ingressos es quedava a 2.5:1, el vermell de les despeses a 3.8:1 i el botó
verd amb text blanc a 3.3:1. Comprovat que, amb el codi d'abans, els tres
tests fallen. Llegeix els colors de les variables del tema de Tailwind 4
(`var(--color-red-600)`) i falla si en llegeix menys de 20: en passar a
Tailwind 4 no en sabia llegir cap i passava sense comprovar res.

### `frontend/test/server.test.js`

El servidor que entrega la pàgina: que enviï la Content-Security-Policy (només
scripts propis, cap iframe d'una altra web, connexions només a la mateixa
adreça i al backend del port 8000), `nosniff`, `X-Frame-Options` i
`Referrer-Policy`; que no s'anunciï com a Express ni obri CORS a tothom; i que
una capçalera `Host` estranya no pugui afegir regles a la política. I que els
fitxers d'idioma surtin com a text en UTF-8.

### `frontend/test/assets.test.js`

Tailwind, les fonts i Chart.js es generen amb `npm run build:assets` i es desen
a `public/`. Aquest test torna a compilar Tailwind i compara el resultat amb
`public/css/tailwind.css`, i comprova que les fonts i Chart.js de
`public/vendor/` siguin els dels paquets instal·lats. Una classe nova sense regenerar el CSS no dona
cap error: simplement no té estil. Necessita `npm ci` abans. També, que cap
variable de `main.css` es digui com una del tema de Tailwind: amb
`--radius-lg` a `main.css`, les caselles amb `rounded-lg` sortien rodones.

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
  seguretat de debò. Que cap endpoint retorni dades sense cookie: es pregunten
  a Spring totes les rutes que existeixen, de manera que una de nova queda
  comprovada sola. Abans era una llista feta a mà d'una dotzena.
- `MigrationsIntegrationTest`: que una base de dades de l'agost (l'`init.sql`
  d'abans de la migració 004, a `src/test/resources/migrations`) més totes les
  migracions quedi igual que una de nova feta amb l'`init.sql` d'ara: mateixes
  columnes, restriccions, índexs i categories. Que cada migració es pugui passar
  dues vegades seguides sense errors ni canvis (la 008 no podia). I que estiguin
  numerades sense salts. Els altres tests fan la base de dades amb `init.sql`;
  la de debò hi ha arribat amb migracions, i sense aquest test una diferència
  només es veuria quan el backend no arrenqués a casa.
- `PersistenceIntegrationTest`: l'escala de les columnes `NUMERIC` en anar i
  tornar, els valors per defecte de `@PrePersist`, les actualitzacions parcials
  i les claus foranes.
- `DebtIntegrationTest`: que les devolucions vinculades descomptin del pendent
  i l'entrada del préstec no; que editar un moviment el vinculi, el conservi i
  el desvinculi; que un deute inexistent es rebutgi abans de moure cap saldo;
  que esborrar el deute deixi els moviments (i esborri els seus rebuts trets);
  que el pressupost reservi la quota del mes i deixi de fer-ho quan el deute ja
  està saldat; que un pagament pagui el rebut triat i, editant-lo, el que toca;
  que desvincular el deute buidi el rebut; i que saltar, descomptar i desfer un
  rebut passin per HTTP, amb un 400 si un pagament l'havia triat.
- `ApiErrorsIntegrationTest`: els errors per HTTP, amb la sessió de debò. Que
  una ruta desconeguda digui que cal reconstruir el backend, que un moviment o
  un compte que no existeixen diguin quin, que un JSON mal escrit o un
  paràmetre invàlid s'expliquin sense classes de Java, que les validacions
  que abans tornaven un 400 buit diguin què falla, i que un extracte
  il·legible sigui un 400 amb la línia (no un «Error intern») i el de Trade
  Republic arribi a la pantalla de revisió.
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
  sí, a la seva categoria; que el de Trade Republic compti a Estalvis i el
  que en torna el resti sense ser ingrés; que mogui el saldo dels dos comptes i
  esborrar-lo els desfaci; que canviar-ne l'altre compte mogui el diner; que un
  traspàs al mateix compte es rebutgi sense tocar res; que a l'extracte de
  Revolut l'entrada d'un traspàs ja desat surti reconeguda; que una regla el
  marqui sola; i que filtrar per Trade Republic el trobi. I les dues potes
  desades com a moviments normals: que surtin com a traspàs sense marcar, que
  ajuntar-les tregui de l'anàlisi l'ingrés que no ho era sense tocar cap saldo (i l'estalvi
  segueixi comptant al pressupost), que marcar el traspàs editant-lo o amb una
  regla en importar ajunti sola l'altra pota, i que dos moviments que no fan un
  traspàs no s'ajuntin. I Estalvi i inversió: que el que passa a Trade Republic
  sigui estalviat, també sense marcar com a traspàs, i un regal pagat amb la
  targeta de Trade Republic, gastat del fons (i despesa a l'anàlisi); que el
  pressupost, l'anàlisi i el tauler (`/analytics/overall`) donin les mateixes
  xifres; i que l'estalvi es compti pel compte on entren els diners encara que
  el traspàs s'hagi desat a Trade Republic.
- `BudgetReviewIntegrationTest`: el pla contra el real per HTTP, amb tres mesos
  de pressupostos i moviments. Que cada bloc i subcategoria surti amb el pla, el
  real i el semàfor; que el càrrec anual de l'assegurança surti com a puntual;
  que l'historial compti només els mesos tancats; i que un mes mal escrit o un
  rang al revés tornin un 400.
- `BudgetAdviceIntegrationTest`: les propostes per HTTP, de punta a punta.
  Que es proposi pujar Restaurants a la mitjana, que aplicar-ho ho posi al pla
  del mes que ve (amb la resta copiada, el percentatge com a percentatge i el
  pressupost trimestral sense tocar), i que un canvi mal fet no apliqui res, ni
  tan sols la còpia.
- `GeneralAnalysisIntegrationTest`: la pestanya General per HTTP. El període
  amb les categories, els comerços i la despesa formiga; els moviments d'un
  bloc amb la data com la llegeix el navegador; i els errors (400 per un mes mal
  escrit, 404 per una categoria que no existeix).
- `DecisionsIntegrationTest`: la pestanya Decisions per HTTP, amb el pressupost
  de debò: el recurrent a l'any, el Delivery que puja, el simulador amb el pla i
  l'estalvi mitjà, i la meta.
- `MonthlySummaryIntegrationTest`: el resum del mes que pinta Pressupostos.
  Entre d'altres, que Estalvi i inversió sigui una secció pròpia però que el
  seu percentatge surti del mateix bot que els variables (el que sobra dels
  fixos), i que el que queda per repartir sigui el mateix a les dues seccions.
- `FixedCostIntegrationTest`: el menú de recurrents de Pressupostos. Que un
  canvi valgui des d'un mes sense tocar els anteriors, que un import posat en un
  sol mes no es copiï als següents, i que s'hi pugui posar la llum, una fulla
  variable, però no un bloc.

## Prova de navegador

Entra, importa l'extracte d'exemple per la pantalla fins a desar-lo i el torna
a pujar (ha de dir que ja hi és, no donar error), i recorre les 14 vistes i els
seus formularis en clar i en fosc. Falla amb:

- errors de JavaScript o a la consola, i peticions que fallen;
- violacions de la Content-Security-Policy;
- una capçalera que no diu el nom de la vista;
- qualsevol text o camp per sota de 4.5:1 (3:1 si és gran), amb les
  transicions aturades perquè un color a mig canviar no compti.

Abans tot això es comprovava a mà a cada PR. El primer dia va trobar dos
errors: tornar a pujar un extracte ja importat deia «Resposta inesperada del
servidor», i el backend registrava com a error inesperat cada petició que el
navegador tallava en canviar de vista (ara `ApiErrorHandlerTest` ho vigila).
Comprovada trencant coses a propòsit: amb aquell error de pujada, o traient
una regla de `dark.css`, falla i diu on.

## Què NO cobreixen

- **La interfície es comprova per fora**: la prova de navegador mira que cada
  vista pinti sense errors i es llegeixi, no que cada xifra sigui la bona; això
  ho cobreixen els tests del backend i dels mòduls purs del frontend.
- Els tests d'integració criden els controllers directament o via MockMvc, no
  contra un servidor amb un port obert.
