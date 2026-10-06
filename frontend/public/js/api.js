const API_URL = `${window.location.protocol}//${window.location.hostname}:8000`;

export const appState = {
    currency: 'EUR',
    theme: 'light',
    userName: '',
    userEmail: '',
    notifications: {
        expenses: true,
        budget: true,
        monthly: false
    }
};

function applyTheme(theme) {
    const html = document.documentElement;
    if (theme === 'dark') {
        html.classList.add('dark');
    } else if (theme === 'light') {
        html.classList.remove('dark');
    } else if (theme === 'system') {
        const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        if (prefersDark) {
            html.classList.add('dark');
        } else {
            html.classList.remove('dark');
        }
    }
    appState.theme = theme;
}

function applyCurrency(currency) {
    appState.currency = currency;
    document.dispatchEvent(new CustomEvent('currencyChanged', { detail: { currency } }));
}

export async function loadAppState() {
    try {
        const settings = await getSettings();
        // El backend serialitza Settings en camelCase (sense @JsonProperty),
        // a diferència de la resta de models.
        appState.userName = settings.userName || 'Usuario';
        appState.userEmail = settings.userEmail || '';
        appState.currency = settings.currency || 'EUR';
        appState.notifications = {
            expenses: settings.notificationsExpenses !== false,
            budget: settings.notificationsBudget !== false,
            monthly: settings.notificationsMonthly === true
        };
        
        applyTheme(settings.theme || 'light');
        applyCurrency(settings.currency || 'EUR');
        
        return settings;
    } catch (error) {
        console.error('Error loading app state:', error);
        return null;
    }
}

export function setTheme(theme) {
    applyTheme(theme);
}

export function setCurrency(currency) {
    applyCurrency(currency);
}

export function formatCurrency(amount) {
    const symbols = { EUR: '€', USD: '$', GBP: '£' };
    const symbol = symbols[appState.currency] || '€';
    const numericAmount = Number.parseFloat(amount);
    const safeAmount = Number.isFinite(numericAmount) ? numericAmount : 0;
    return `${safeAmount.toFixed(2)} ${symbol}`;
}

// Escapa text abans d'interpolar-lo dins d'HTML.
// Les dades venen del CSV del banc i de la resposta de la IA, així que
// no es poden considerar segures.
export function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// Totes les peticions han de portar la cookie de sessió. Com que el frontend
// i el backend són a ports diferents, sense "credentials: include" el
// navegador no l'enviaria.
export async function apiFetch(path, options = {}) {
    try {
        return await fetch(`${API_URL}${path}`, { credentials: 'include', ...options });
    } catch (error) {
        // fetch només falla així quan no arriba a parlar amb el servidor. El
        // navegador ho diu com "Failed to fetch", que no diu res a ningú.
        throw new Error(`No es pot connectar amb el backend (${API_URL}). `
            + 'Comprova que està en marxa amb «docker compose ps».');
    }
}

// Se n'avisa quan el backend respon 401 perquè app.js pugui tornar a la
// pantalla d'entrada sense que cada vista ho hagi de comprovar.
const unauthorizedListeners = [];

export function onUnauthorized(listener) {
    unauthorizedListeners.push(listener);
}

// Helper function for handling API responses
async function handleResponse(response) {
    if (response.status === 401) {
        unauthorizedListeners.forEach(listener => listener());
        throw new Error('Sessió caducada');
    }
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response));
    }
    return response.json();
}

// ============ SESSIÓ ============
export async function login(username, password) {
    const response = await apiFetch('/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
    });

    if (!response.ok) {
        throw new Error(await extractErrorMessage(response));
    }
    return response.json();
}

export async function logout() {
    await apiFetch('/auth/logout', { method: 'POST' });
}

/** Retorna l'usuari de la sessió, o null si no n'hi ha cap d'oberta. */
export async function getCurrentUser() {
    const response = await apiFetch('/auth/me');
    if (response.status === 401) return null;
    if (!response.ok) throw new Error(await extractErrorMessage(response));
    return response.json();
}

// El backend respon els errors com a text pla en uns endpoints i com a JSON
// en d'altres; sense això tot arribava a la UI com a "Error desconegut".
async function extractErrorMessage(response) {
    let body = '';
    try {
        body = await response.text();
    } catch {
        // Sense cos, es descriu pel codi.
    }
    let path = '';
    try {
        path = new URL(response.url).pathname;
    } catch {
        // Una resposta sense URL (als tests) es descriu sense ruta.
    }
    return describeError(response.status, body, path);
}

/**
 * Les frases estàndard d'HTTP. Soles no expliquen res: "Not Found" no diu què
 * no s'ha trobat ni què s'hi pot fer. És el que posa Spring quan l'error no
 * porta missatge, per exemple quan el backend en marxa és més antic que el
 * frontend i no coneix la ruta.
 */
const BARE_STATUS_PHRASES = new Set([
    'bad request', 'unauthorized', 'forbidden', 'not found', 'method not allowed',
    'conflict', 'payload too large', 'unsupported media type', 'internal server error',
    'bad gateway', 'service unavailable', 'gateway timeout'
]);

/**
 * El missatge que veu l'usuari per a una resposta d'error.
 *
 * Primer el que digui el backend ("message"; "detail" i "error" per als
 * endpoints antics). Si no diu res, o només la frase estàndard del codi, una
 * explicació pel codi que digui què pot haver passat i què fer-hi.
 *
 * @param status el codi HTTP
 * @param body   el cos de la resposta, tal qual
 * @param path   la ruta, per dir quina ha fallat
 */
export function describeError(status, body, path = '') {
    let message = '';
    const text = (body || '').trim();
    if (text) {
        try {
            const parsed = JSON.parse(text);
            message = parsed.message || parsed.detail || parsed.error || '';
        } catch {
            // Una pàgina d'error en HTML (d'un proxy, per exemple) no es pot
            // ensenyar dins d'un missatge.
            message = text.startsWith('<') ? '' : text;
        }
    }
    message = String(message).trim();
    if (message && !BARE_STATUS_PHRASES.has(message.toLowerCase())) return message;
    return explainStatus(status, path);
}

function explainStatus(status, path) {
    const route = path ? ` (${path})` : '';
    const rebuild = 'Si acabes d\'actualitzar l\'aplicació, reconstrueix el backend amb '
        + '«docker compose up -d --build backend».';
    switch (status) {
        case 400:
            return `El backend ha rebutjat la petició${route} sense dir per què.`;
        case 403:
            return 'No tens permís per fer això.';
        case 404:
            return `El backend no troba el que s'ha demanat${route}. ${rebuild}`;
        case 405:
            return `El backend no accepta aquesta operació${route}. ${rebuild}`;
        case 409:
            return 'No es pot fer: hi ha dades que en depenen.';
        case 413:
            return 'El fitxer és massa gran.';
        case 500:
            return 'Error intern del backend. El detall és al log: «docker compose logs backend».';
        case 502:
        case 503:
        case 504:
            return 'El backend no respon. Comprova que està en marxa amb «docker compose ps».';
        default:
            return `Error ${status}${route}.`;
    }
}

// Fetch all transactions
export async function getTransactions(filters = {}) {
    const params = new URLSearchParams(filters);
    const response = await apiFetch(`/gastos?${params.toString()}`);
    return handleResponse(response);
}

/**
 * Alta manual d'un moviment: efectiu, un préstec, el que no surt de l'extracte.
 *
 * A diferència dels que venen del CSV, aquest no porta hash de verificació:
 * no és cap línia d'extracte i dos moviments iguals el mateix dia han de poder
 * conviure.
 */
export async function createTransaction(data) {
    const response = await apiFetch(`/gastos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

/**
 * Edita un moviment ja desat.
 *
 * El backend desfà l'efecte antic al saldo i aplica el nou, així que canviar
 * l'import o el compte no descompensa res.
 */
export async function updateTransaction(id, data) {
    const response = await apiFetch(`/gastos/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

/**
 * Esborra un moviment i desfà el que va fer al saldo del compte.
 *
 * No n'hi ha prou d'esborrar la fila: el saldo es va moure en desar-lo.
 */
export async function deleteTransaction(id) {
    const response = await apiFetch(`/gastos/${id}`, { method: 'DELETE' });
    return handleResponse(response);
}

/**
 * Divideix un moviment en parts, o treu la divisió amb una llista buida.
 *
 * Les parts han de sumar exactament l'import del moviment. El saldo no es
 * toca: el moviment ja el va moure pel total.
 */
export async function saveTransactionParts(id, parts) {
    const response = await apiFetch(`/gastos/${id}/parts`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(parts),
    });
    return handleResponse(response);
}

// ============ REGLES D'IMPORTACIÓ ============
// Miren el concepte original del moviment i, si hi troben el seu patró, el
// marquen com a ja comptat i li poden posar categoria.
export async function getImportRules() {
    const response = await apiFetch('/import-rules');
    return handleResponse(response);
}

export async function createImportRule(data) {
    const response = await apiFetch('/import-rules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function deleteImportRule(id) {
    const response = await apiFetch(`/import-rules/${id}`, { method: 'DELETE' });
    if (!response.ok) throw new Error(await extractErrorMessage(response));
}

// Fetch all categories
export async function getCategories() {
    const response = await apiFetch(`/categories`);
    return handleResponse(response);
}

// Fetch all companies
export async function getCompanies() {
    const response = await apiFetch(`/companies`);
    return handleResponse(response);
}

// Upload a CSV file for processing
export async function uploadCsv(file, accountId) {
    const formData = new FormData();
    formData.append('file', file);
    // El compte va a la pujada perquè el descart de duplicats es fa per
    // extracte: la mateixa xifra el mateix dia a dos comptes és un traspàs.
    if (accountId) formData.append('accountId', accountId);

    const response = await apiFetch(`/upload-csv`, {
        method: 'POST',
        body: formData,
    });
    return handleResponse(response);
}

// Confirm the reviewed transactions
export async function confirmTransactions(data) {
    const response = await apiFetch(`/confirm-upload`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

// ============ ACCOUNTS ============
export async function getAccounts() {
    const response = await apiFetch(`/accounts`);
    return handleResponse(response);
}

export async function getAccount(id) {
    const response = await apiFetch(`/accounts/${id}`);
    return handleResponse(response);
}

export async function createAccount(data) {
    const response = await apiFetch(`/accounts`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function updateAccount(id, data) {
    const response = await apiFetch(`/accounts/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function deleteAccount(id) {
    const response = await apiFetch(`/accounts/${id}`, {
        method: 'DELETE',
    });
    return handleResponse(response);
}

export async function adjustBalance(id, amount) {
    const response = await apiFetch(`/accounts/${id}/adjust-balance`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ amount }),
    });
    return handleResponse(response);
}

// ============ BUDGETS ============
export async function getBudgets() {
    const response = await apiFetch(`/budgets`);
    return handleResponse(response);
}

export async function getCurrentBudget() {
    const response = await apiFetch(`/budgets/current`);
    return handleResponse(response);
}

export async function getBudget(id) {
    const response = await apiFetch(`/budgets/${id}`);
    return handleResponse(response);
}

export async function createBudget(data) {
    const response = await apiFetch(`/budgets`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function updateBudget(id, data) {
    const response = await apiFetch(`/budgets/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function deleteBudget(id) {
    const response = await apiFetch(`/budgets/${id}`, {
        method: 'DELETE',
    });
    return handleResponse(response);
}

/**
 * Resum del mes per grups: cost de vida (fixos prorratejats + variables reals)
 * i caixa (moviments reals). Vegeu ARQUITECTURA.md.
 */
export async function getBudgetMonthlySummary(year, month) {
    const params = new URLSearchParams({ year, month });
    const response = await apiFetch(`/budgets/monthly-summary?${params.toString()}`);
    return handleResponse(response);
}

/** Duplica al mes indicat les assignacions del mes anterior. */
export async function copyPreviousMonthBudgets(year, month) {
    const params = new URLSearchParams({ year, month });
    const response = await apiFetch(`/budgets/copy-previous-month?${params.toString()}`, {
        method: 'POST'
    });
    return handleResponse(response);
}

// ============ SOU DEL MES ============
export async function getMonthlyIncomes() {
    const response = await apiFetch(`/budgets/monthly-income`);
    return handleResponse(response);
}

export async function setMonthlyIncome(period, data) {
    const response = await apiFetch(`/budgets/monthly-income/${period}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function deleteMonthlyIncome(period) {
    const response = await apiFetch(`/budgets/monthly-income/${period}`, { method: 'DELETE' });
    return handleResponse(response);
}

// ============ CATEGORIES (escriptura) ============
export async function createCategory(data) {
    const response = await apiFetch(`/categories`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function updateCategory(id, data) {
    const response = await apiFetch(`/categories/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function deleteCategory(id) {
    const response = await apiFetch(`/categories/${id}`, { method: 'DELETE' });
    return handleResponse(response);
}

// ============ TRANSFERS ============
export async function getTransfers() {
    const response = await apiFetch(`/transfers`);
    return handleResponse(response);
}

export async function getTransfer(id) {
    const response = await apiFetch(`/transfers/${id}`);
    return handleResponse(response);
}

export async function createTransfer(data) {
    const response = await apiFetch(`/transfers`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function deleteTransfer(id) {
    const response = await apiFetch(`/transfers/${id}`, {
        method: 'DELETE',
    });
    return handleResponse(response);
}

// ============ RECURRING TRANSACTIONS ============
export async function getRecurringTransactions() {
    const response = await apiFetch(`/recurring`);
    return handleResponse(response);
}

export async function getRecurringTransaction(id) {
    const response = await apiFetch(`/recurring/${id}`);
    return handleResponse(response);
}

export async function createRecurringTransaction(data) {
    const response = await apiFetch(`/recurring`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function updateRecurringTransaction(id, data) {
    const response = await apiFetch(`/recurring/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function deleteRecurringTransaction(id) {
    const response = await apiFetch(`/recurring/${id}`, {
        method: 'DELETE',
    });
    return handleResponse(response);
}

export async function processRecurring() {
    const response = await apiFetch(`/recurring/process`, {
        method: 'POST',
    });
    return handleResponse(response);
}

// ============ COSTOS FIXOS ============
// Tots porten el mes des del qual valen: els mesos anteriors no canvien.
function fixedCostPeriod(year, month) {
    return new URLSearchParams({ year, month }).toString();
}

export async function getFixedCosts(year, month) {
    const response = await apiFetch(`/fixed-costs?${fixedCostPeriod(year, month)}`);
    return handleResponse(response);
}

export async function createFixedCost(year, month, data) {
    const response = await apiFetch(`/fixed-costs?${fixedCostPeriod(year, month)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function updateFixedCost(id, year, month, data) {
    const response = await apiFetch(`/fixed-costs/${id}?${fixedCostPeriod(year, month)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function deleteFixedCost(id, year, month) {
    const response = await apiFetch(`/fixed-costs/${id}?${fixedCostPeriod(year, month)}`, {
        method: 'DELETE',
    });
    return handleResponse(response);
}

// ============ FINANCIAL GOALS ============
export async function getGoals() {
    const response = await apiFetch(`/goals`);
    return handleResponse(response);
}

export async function getGoal(id) {
    const response = await apiFetch(`/goals/${id}`);
    return handleResponse(response);
}

export async function createGoal(data) {
    const response = await apiFetch(`/goals`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function updateGoal(id, data) {
    const response = await apiFetch(`/goals/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function deleteGoal(id) {
    const response = await apiFetch(`/goals/${id}`, {
        method: 'DELETE',
    });
    return handleResponse(response);
}

export async function addAmountToGoal(id, amount) {
    const response = await apiFetch(`/goals/${id}/add-amount`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ amount }),
    });
    return handleResponse(response);
}

// ============ DEUTES I PRÉSTECS ============
// Cada deute arriba amb el que s'ha retornat, el que queda i el calendari amb
// l'estat de cada pagament: el backend ho calcula a partir dels moviments
// vinculats, que són els que diuen el que ha passat.
export async function getDebts() {
    const response = await apiFetch('/debts');
    return handleResponse(response);
}

export async function createDebt(data) {
    const response = await apiFetch('/debts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

export async function updateDebt(id, data) {
    const response = await apiFetch(`/debts/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}

/** Els moviments vinculats no s'esborren: només perden el vincle. */
export async function deleteDebt(id) {
    const response = await apiFetch(`/debts/${id}`, { method: 'DELETE' });
    return handleResponse(response);
}

// ============ ANALYTICS ============
export async function getMonthlySummary(year, month) {
    const params = new URLSearchParams({ year, month });
    const response = await apiFetch(`/analytics/monthly-summary?${params.toString()}`);
    return handleResponse(response);
}

export async function getCategoryBreakdown(year, month) {
    const params = new URLSearchParams({ year, month });
    const response = await apiFetch(`/analytics/category-breakdown?${params.toString()}`);
    return handleResponse(response);
}

export async function getYearlySummary(year) {
    const params = new URLSearchParams({ year });
    const response = await apiFetch(`/analytics/yearly-summary?${params.toString()}`);
    return handleResponse(response);
}

export async function getMonthlyTrend(year) {
    const params = new URLSearchParams(year ? { year } : {});
    const response = await apiFetch(`/analytics/monthly-trend?${params.toString()}`);
    return handleResponse(response);
}

// ============ SETTINGS ============
export async function getSettings() {
    const response = await apiFetch(`/settings`);
    return handleResponse(response);
}

export async function updateSettings(data) {
    const response = await apiFetch(`/settings`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    return handleResponse(response);
}
