import {
    getTransactions, getCategories, getCompanies, getAccounts, getDebts,
    createTransaction, updateTransaction, deleteTransaction, saveTransactionParts, formatCurrency, escapeHtml
} from '../../api.js';
import { categoryOptions, leafCategories, sectionOfCategory } from '../../categoryOptions.js';
import { counterpartOptions, savingsCategoryHint, transferCounts, transferLabel } from '../../transfers.js';
import { hasReceiptsToChoose, receiptOptions } from '../../debtReceipts.js';
import { setUpUnmarkedTransfers } from './UnmarkedTransfers.js';
import { setUpSplitEditor } from './SplitEditor.js';
import { t, formatDate, formatMonth as monthAndYear } from '../../i18n.js';

/**
 * Ingrés o despesa.
 *
 * L'import es desa sempre en positiu i el signe viu només al tipus, així que
 * sense ensenyar-lo un ingrés i una despesa del mateix import es veien iguals.
 */
const TYPES = {
    EXPENSE: { etiqueta: 'common.expense', signe: '−', classe: 'text-error' },
    INCOME:  { etiqueta: 'common.income',  signe: '+', classe: 'text-success' }
};

const typeOf = (transaction) => TYPES[transaction.type] ? transaction.type : 'EXPENSE';

/**
 * La categoria que toca a un moviment d'un deute, segons el sentit.
 *
 * Un préstec compta al pressupost pels moviments: el que em deixen eixampla
 * el que es pot repartir, i el que torno n'és una despesa. Triar el deute
 * proposa la categoria perquè no s'hagi de recordar quina és; si el moviment
 * és una altra cosa (el sopar que un amic em va pagar), es pot canviar.
 */
const DEBT_CATEGORIES = {
    DEC: { INCOME: 'Préstecs rebuts', EXPENSE: 'Pagament de deutes' },
    EM_DEUEN: { EXPENSE: 'Préstecs fets', INCOME: 'Cobrament de préstecs' }
};

let currentSortMode = 'month-desc';
let currentTransactions = [];
// Per posar nom a l'etiqueta dels moviments vinculats: el moviment només porta
// l'identificador del deute.
let debtsById = new Map();
// Per dir a quin compte va un traspàs: el moviment només en porta l'id.
let accountsById = new Map();
// La fixa setUpManualEntry, que és qui té el formulari a mà, i la crida el
// listener de la taula, que viu fora.
let openEditor = () => {};
// El mateix per al formulari de dividir.
let openSplitter = () => {};
// I per tornar a buscar els traspassos sense marcar després de desar.
let refreshUnmarkedTransfers = async () => {};

const isSplit = (transaction) => (transaction.parts || []).length > 0;

// Les parts d'un moviment dividit surten plegades: amb quatre parts per
// moviment, la llista es feia el doble de llarga i el que es buscava quedava
// enterrat. Es despleguen amb la pastilla "dividit en N", i el que s'ha
// desplegat es manté en filtrar o tornar a carregar la llista.
const expandedSplits = new Set();

export async function initTransactions(container) {
    container.innerHTML = `
        <div class="card mb-4">
            <div class="card-header">
                <h3 class="card-title">${t('transactions.filters')}</h3>
                <button class="btn btn-sm btn-outline" id="clear-filters">${t('transactions.clear')}</button>
            </div>
            <div class="filter-grid">
                <div class="form-group">
                    <label for="filter-category">${t('common.category')}</label>
                    <select id="filter-category" class="form-control">
                        <option value="">${t('transactions.all-categories')}</option>
                    </select>
                </div>
                <div class="form-group">
                    <label for="filter-company">${t('table.company')}</label>
                    <select id="filter-company" class="form-control">
                        <option value="">${t('transactions.all-companies')}</option>
                    </select>
                </div>
                <div class="form-group">
                    <label for="filter-account">${t('common.account')}</label>
                    <select id="filter-account" class="form-control">
                        <option value="">${t('transactions.all-accounts')}</option>
                    </select>
                </div>
                <div class="form-group">
                    <label for="filter-type">${t('common.type')}</label>
                    <select id="filter-type" class="form-control">
                        <option value="">${t('transactions.all-types')}</option>
                        <option value="EXPENSE">${t('transactions.filter-expenses')}</option>
                        <option value="INCOME">${t('transactions.filter-income')}</option>
                    </select>
                </div>
                <div class="form-group">
                    <label for="filter-month">${t('common.month')}</label>
                    <select id="filter-month" class="form-control">
                        <option value="">${t('transactions.all-months')}</option>
                    </select>
                </div>
            </div>
        </div>

        <div id="unmarked-transfers" class="card mb-4 p-4 hidden border-l-4 border-amber-400"></div>

        <div class="card">
            <div class="card-header">
                <h3 class="card-title">${t('transactions.list-title')}</h3>
                <div class="card-header-actions">
                    <button class="btn btn-sm btn-primary" id="add-transaction-btn" type="button">
                        ${t('transactions.add-button')}
                    </button>
                    <button class="btn btn-sm btn-outline" id="toggle-transaction-sort" type="button"></button>
                    <button class="btn btn-sm btn-outline hidden" id="toggle-all-parts" type="button"></button>
                    <span class="badge badge-secondary" id="transaction-count">0</span>
                </div>
            </div>
            <div class="table-responsive">
                <table class="table table-hover">
                    <thead>
                        <tr>
                            <th>${t('table.date')}</th>
                            <th>${t('common.account')}</th>
                            <th>${t('common.type')}</th>
                            <th>${t('table.company')}</th>
                            <th>${t('common.category')}</th>
                            <th>${t('common.description')}</th>
                            <th class="text-right">${t('table.amount')}</th>
                            <th style="width: 3rem;"></th>
                        </tr>
                    </thead>
                    <tbody id="transactions-body">
                        <tr><td colspan="8" class="text-center">${t('common.loading')}</td></tr>
                    </tbody>
                </table>
            </div>
        </div>

        <!-- Modal d'alta manual -->
        <div id="transaction-modal" class="fixed inset-0 bg-black/50 hidden items-center justify-center z-50 p-4">
            <div class="bg-white dark:bg-slate-800 rounded-3xl p-6 w-full max-w-md max-h-full overflow-y-auto">
                <h3 class="text-xl font-bold mb-1" id="transaction-modal-title">${t('transactions.add-title')}</h3>
                <p class="text-sm text-gray-500 dark:text-slate-400 mb-4" id="transaction-modal-hint">${t('transactions.add-hint')}</p>
                <form id="transaction-form" class="space-y-4">
                    <input type="hidden" id="edit-id">
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-type">${t('common.type')}</label>
                        <select id="new-type" class="form-control">
                            <option value="EXPENSE">${t('transactions.type-expense-option')}</option>
                            <option value="INCOME">${t('transactions.type-income-option')}</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-amount">${t('common.amount')}</label>
                        <input type="number" id="new-amount" class="form-control" step="0.01" min="0.01" required>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-date">${t('common.date')}</label>
                        <input type="date" id="new-date" class="form-control" required>
                    </div>
                    <div data-unsplit-only>
                        <label class="block text-sm font-medium mb-1" for="new-category">${t('common.category')}</label>
                        <select id="new-category" class="form-control" required></select>
                        <p id="new-category-hint" class="text-xs text-teal-700 dark:text-teal-300 mt-1 hidden"></p>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-company">${t('table.company')}</label>
                        <input type="text" id="new-company" class="form-control" list="company-suggestions"
                               placeholder="${t('common.unknown')}">
                        <datalist id="company-suggestions"></datalist>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-account">${t('common.account')}</label>
                        <select id="new-account" class="form-control"></select>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-counterpart">${t('transactions.counterpart')}</label>
                        <select id="new-counterpart" class="form-control"></select>
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-1" id="new-counterpart-hint">${t('transactions.counterpart-hint')}</p>
                    </div>
                    <div data-unsplit-only>
                        <label class="block text-sm font-medium mb-1" for="new-debt">${t('transactions.debt')}</label>
                        <select id="new-debt" class="form-control"></select>
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-1">${t('transactions.debt-hint')}</p>
                    </div>
                    <div id="new-debt-receipt-field" class="hidden">
                        <label class="block text-sm font-medium mb-1" for="new-debt-receipt">${t('transactions.debt-receipt')}</label>
                        <select id="new-debt-receipt" class="form-control"></select>
                        <p class="text-xs text-gray-500 dark:text-slate-400 mt-1">${t('transactions.debt-receipt-hint')}</p>
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1" for="new-description">${t('common.description')}</label>
                        <input type="text" id="new-description" class="form-control" placeholder="${t('common.optional')}">
                    </div>
                    <label class="flex items-start gap-2 text-sm" data-unsplit-only>
                        <input type="checkbox" id="new-excluded" class="mt-1">
                        <span>
                            ${t('transactions.excluded')}
                            <span class="block text-xs text-gray-500 dark:text-slate-400">${t('transactions.excluded-hint')}</span>
                        </span>
                    </label>
                    <p id="transaction-split-note" class="text-sm text-gray-500 dark:text-slate-400 hidden">${t('transactions.split-note')}</p>
                    <p id="transaction-form-error" class="text-error text-sm hidden"></p>
                    <div class="flex gap-2 justify-end">
                        <button type="button" id="transaction-cancel" class="btn btn-outline">${t('common.cancel')}</button>
                        <button type="submit" class="btn btn-primary">${t('transactions.add')}</button>
                    </div>
                </form>
            </div>
        </div>
    `;

    // Load Filters
    const categories = await getCategories();
    const companies = await getCompanies();
    // Sense deutes la llista funciona igual: només es queda sense etiquetes.
    const debts = await getDebts().catch(error => {
        console.error('Error carregant deutes:', error);
        return [];
    });
    debtsById = new Map(debts.map(debt => [debt.id, debt]));
    
    // Només fulles: els moviments hi són assignats, i filtrar per un bloc no
    // en trobava cap.
    document.getElementById('filter-category')
        .insertAdjacentHTML('beforeend', categoryOptions(categories));

    const accountSelect = document.getElementById('filter-account');
    const accounts = await getAccounts();
    accountsById = new Map(accounts.map(account => [account.id, account]));
    accounts.forEach(account => {
        const option = document.createElement('option');
        option.value = account.id;
        option.textContent = account.nom;
        accountSelect.appendChild(option);
    });

    const companySelect = document.getElementById('filter-company');
    companies.forEach(company => {
        const option = document.createElement('option');
        option.value = company.id;
        option.textContent = company.nom;
        companySelect.appendChild(option);
    });

    try {
        const initialTransactions = await getTransactions();
        currentTransactions = initialTransactions;
        populateMonthOptions(initialTransactions);
        updateSortButton();
        renderTable(initialTransactions, currentSortMode);
    } catch (error) {
        console.error('Error loading initial transactions:', error);
        document.getElementById('transaction-count').textContent = '0';
        updateSortButton();
        renderMessageRow(t('transactions.load-failed', { message: error.message }), true);
    }

    // Event Listeners
    document.getElementById('filter-category').addEventListener('change', loadData);
    document.getElementById('filter-company').addEventListener('change', loadData);
    document.getElementById('filter-account').addEventListener('change', loadData);
    document.getElementById('filter-type').addEventListener('change', loadData);
    document.getElementById('filter-month').addEventListener('change', loadData);
    document.getElementById('toggle-transaction-sort').addEventListener('click', toggleSortMode);
    document.getElementById('toggle-all-parts').addEventListener('click', toggleAllParts);
    document.getElementById('clear-filters').addEventListener('click', () => {
        document.getElementById('filter-category').value = '';
        document.getElementById('filter-company').value = '';
        document.getElementById('filter-account').value = '';
        document.getElementById('filter-type').value = '';
        document.getElementById('filter-month').value = '';
        currentSortMode = 'month-desc';
        updateSortButton();
        loadData();
    });

    // Delegació: les files es repinten a cada filtre, així que un listener
    // posat a cada botó no sobreviuria. I res d'onclick amb dades
    // interpolades, que es trenca amb un nom com O'Brien.
    document.getElementById('transactions-body').addEventListener('click', handleRowAction);

    refreshUnmarkedTransfers = setUpUnmarkedTransfers(document.getElementById('unmarked-transfers'), {
        onJoined: loadData
    });
    refreshUnmarkedTransfers();

    setUpManualEntry(categories, companies, debts);
    // Aquí les parts es desen de seguida, i la llista es torna a carregar
    // perquè el moviment surti amb les seves.
    openSplitter = setUpSplitEditor(container, {
        categories,
        debts,
        save: async (transaction, parts) => {
            await saveTransactionParts(transaction.id, parts);
            await loadData();
        },
        remove: async (transaction) => {
            await saveTransactionParts(transaction.id, []);
            await loadData();
        }
    });
}

async function handleRowAction(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;

    const id = Number.parseInt(button.dataset.id, 10);
    if (!Number.isInteger(id)) return;

    if (button.dataset.action === 'toggle-parts') {
        if (expandedSplits.has(id)) expandedSplits.delete(id);
        else expandedSplits.add(id);
        renderTable(currentTransactions, currentSortMode);
        return;
    }

    if (button.dataset.action === 'edit-transaction' || button.dataset.action === 'split-transaction') {
        const transaction = currentTransactions.find(candidate => candidate.id === id);
        if (!transaction) return;
        if (button.dataset.action === 'edit-transaction') openEditor(transaction);
        else openSplitter(transaction);
        return;
    }

    if (button.dataset.action !== 'delete-transaction') return;

    // Esborrar mou el saldo del compte cap enrere, així que es pregunta.
    if (!confirm(t('transactions.delete-confirm'))) return;

    button.disabled = true;
    try {
        await deleteTransaction(id);
        await loadData();
    } catch (error) {
        alert(error.message || t('transactions.delete-failed'));
        button.disabled = false;
    }
}

/** Alta i edició d'un moviment. */
function setUpManualEntry(categories, companies, debts) {
    const modal = document.getElementById('transaction-modal');
    const form = document.getElementById('transaction-form');
    const error = document.getElementById('transaction-form-error');

    const leaves = leafCategories(categories);

    /** En un moviment dividit, la categoria, el deute i l'exclòs són de cada part. */
    const showSplitFields = (split) => {
        form.querySelectorAll('[data-unsplit-only]')
            .forEach(field => field.classList.toggle('hidden', split));
        document.getElementById('transaction-split-note').classList.toggle('hidden', !split);
    };

    const ownSelect = document.getElementById('new-account');
    const counterpartSelect = document.getElementById('new-counterpart');
    const excludedBox = document.getElementById('new-excluded');
    const counterpartHint = document.getElementById('new-counterpart-hint');
    const defaultCounterpartHint = counterpartHint.textContent;

    /**
     * L'altre compte d'un traspàs, i com comptarà.
     *
     * Entre comptes del dia a dia no compta, i la casella de «no comptar» es
     * marca i es bloqueja: el backend el desaria igualment com a exclòs, i
     * ensenyar-la desmarcada diria una cosa que no passarà. Cap a l'estalvi,
     * compta, i la casella torna a ser de l'usuari.
     */
    const refreshCounterpart = () => {
        counterpartSelect.innerHTML = counterpartOptions([...accountsById.values()], ownSelect.value,
            counterpartSelect.value);
        const own = accountsById.get(Number.parseInt(ownSelect.value, 10));
        const counterpart = accountsById.get(Number.parseInt(counterpartSelect.value, 10));
        const wasForced = excludedBox.disabled;

        if (counterpart && !transferCounts(own, counterpart)) {
            excludedBox.checked = true;
            excludedBox.disabled = true;
            counterpartHint.textContent = t('transactions.hint-everyday-transfer');
            return;
        }
        // Si l'havia marcat el traspàs, en deixar de ser-ho torna a comptar.
        if (wasForced) excludedBox.checked = false;
        excludedBox.disabled = false;
        counterpartHint.textContent = counterpart
            ? t('transactions.hint-savings-transfer', { account: counterpart.nom })
            : defaultCounterpartHint;
    };
    ownSelect.addEventListener('change', refreshCounterpart);
    counterpartSelect.addEventListener('change', refreshCounterpart);

    /**
     * A Estalvi i inversió mana la categoria: el que hi va des del dia a dia
     * és estalvi, estigui marcat com a traspàs o no, i el que es paga des d'un
     * compte d'estalvi és gastat del fons. Un regal pagat des de Principal amb
     * aquesta categoria comptaria com a estalvi, i val més dir-ho abans de desar.
     */
    const refreshCategoryHint = () => {
        const category = categories.find(candidate => candidate.nom === document.getElementById('new-category').value);
        const isSavings = category && sectionOfCategory(categories, category.id) === 'SAVINGS';
        const hint = isSavings
            ? savingsCategoryHint(document.getElementById('new-type').value,
                accountsById.get(Number.parseInt(ownSelect.value, 10)), counterpartSelect.value)
            : '';
        const hintElement = document.getElementById('new-category-hint');
        hintElement.textContent = hint;
        hintElement.classList.toggle('hidden', !hint);
    };
    document.getElementById('new-category').addEventListener('change', refreshCategoryHint);
    document.getElementById('new-type').addEventListener('change', refreshCategoryHint);
    ownSelect.addEventListener('change', refreshCategoryHint);
    counterpartSelect.addEventListener('change', refreshCategoryHint);

    getAccounts().then(accounts => {
        ownSelect.innerHTML = accounts
            .map(account => `<option value="${account.id}">${escapeHtml(account.nom)}</option>`)
            .join('');
        refreshCounterpart();
    }).catch(error => console.error('Error loading accounts:', error));

    document.getElementById('new-category').innerHTML =
        categoryOptions(categories, { value: (category) => category.nom });

    // Els saldats també hi són: un moviment antic pot ser d'un deute ja
    // tornat, i editar-lo no l'ha de desvincular.
    document.getElementById('new-debt').innerHTML = [
        `<option value="">${t('transactions.no-debt')}</option>`,
        ...debts.map(debt => `<option value="${debt.id}">${escapeHtml(debtOptionLabel(debt))}</option>`)
    ].join('');

    /**
     * El desplegable del rebut, només per als deutes a quotes: amb un sol
     * rebut, o sense calendari, no hi ha res a triar.
     */
    const refreshReceipts = (selected = null) => {
        const debt = debtsById.get(Number.parseInt(document.getElementById('new-debt').value, 10));
        const field = document.getElementById('new-debt-receipt-field');
        const select = document.getElementById('new-debt-receipt');
        const show = hasReceiptsToChoose(debt);
        // En un moviment dividit, el deute és de cada part, i les parts paguen
        // sempre el que toca.
        field.classList.toggle('hidden', !show || !document.getElementById('transaction-split-note').classList.contains('hidden'));
        select.innerHTML = show ? receiptOptions(debt, selected) : '';
    };

    const leafNames = new Set(leaves.map(category => category.nom));
    document.getElementById('new-debt').addEventListener('change', (event) => {
        refreshReceipts();
        const debt = debtsById.get(Number.parseInt(event.target.value, 10));
        if (!debt) return;
        const suggested = DEBT_CATEGORIES[debt.direccio]?.[document.getElementById('new-type').value];
        if (suggested && leafNames.has(suggested)) {
            document.getElementById('new-category').value = suggested;
        }
    });

    document.getElementById('company-suggestions').innerHTML = companies
        .map(category => `<option value="${escapeHtml(category.nom)}"></option>`)
        .join('');

    const close = () => {
        modal.classList.add('hidden');
        modal.classList.remove('flex');
    };

    const open = () => {
        modal.classList.remove('hidden');
        modal.classList.add('flex');
    };

    document.getElementById('add-transaction-btn').addEventListener('click', () => {
        form.reset();
        error.classList.add('hidden');
        document.getElementById('edit-id').value = '';
        document.getElementById('transaction-modal-title').textContent = t('transactions.add-title');
        document.getElementById('transaction-modal-hint').textContent = t('transactions.add-hint');
        // Per defecte, avui: el cas normal és apuntar una cosa que acaba de passar.
        document.getElementById('new-date').value = todayInputValue();
        counterpartSelect.value = '';
        refreshCounterpart();
        showSplitFields(false);
        refreshReceipts();
        refreshCategoryHint();
        open();
    });

    /**
     * Obre el formulari amb un moviment ja existent a dins.
     *
     * Les dades surten de la llista que ja s'ha carregat i no d'una crida
     * nova: és la mateixa que s'està ensenyant, i demanar-la un altre cop
     * només afegiria una espera.
     */
    openEditor = (transaction) => {
        form.reset();
        error.classList.add('hidden');

        document.getElementById('edit-id').value = transaction.id;
        document.getElementById('transaction-modal-title').textContent = t('transactions.edit-title');
        document.getElementById('transaction-modal-hint').textContent = t('transactions.edit-hint');

        document.getElementById('new-type').value = typeOf(transaction);
        document.getElementById('new-amount').value = Number.parseFloat(transaction.cost) || '';
        document.getElementById('new-date').value = transaction.data || '';
        document.getElementById('new-category').value = transaction.categoria || '';
        document.getElementById('new-company').value = transaction.empresa || '';
        document.getElementById('new-description').value = transaction.descripcio_curta || '';
        document.getElementById('new-excluded').checked = Boolean(transaction.exclos_pressupost);
        document.getElementById('new-debt').value = transaction.deute_id ? String(transaction.deute_id) : '';
        if (transaction.account?.id) {
            document.getElementById('new-account').value = transaction.account.id;
        }
        // Les opcions depenen del compte: primer el compte, després l'altre.
        counterpartSelect.value = '';
        excludedBox.disabled = false;
        refreshCounterpart();
        counterpartSelect.value = transaction.compte_contrapart_id ? String(transaction.compte_contrapart_id) : '';
        excludedBox.checked = Boolean(transaction.exclos_pressupost);
        refreshCounterpart();
        showSplitFields(isSplit(transaction));
        refreshReceipts(transaction.deute_rebut || null);
        refreshCategoryHint();

        open();
    };

    document.getElementById('transaction-cancel').addEventListener('click', close);
    modal.addEventListener('click', (event) => {
        if (event.target.id === 'transaction-modal') close();
    });

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        error.classList.add('hidden');

        const amount = Number.parseFloat(document.getElementById('new-amount').value);
        if (!Number.isFinite(amount) || amount <= 0) {
            error.textContent = t('transactions.amount-positive');
            error.classList.remove('hidden');
            return;
        }

        const submitButton = form.querySelector('button[type="submit"]');
        submitButton.disabled = true;

        const accountId = Number.parseInt(document.getElementById('new-account').value, 10);
        const payload = {
            data: document.getElementById('new-date').value,
            cost: amount,
            type: document.getElementById('new-type').value,
            categoria: document.getElementById('new-category').value,
            empresa: document.getElementById('new-company').value.trim() || t('common.unknown'),
            descripcio_curta: document.getElementById('new-description').value.trim(),
            exclos_pressupost: document.getElementById('new-excluded').checked,
            // -1 desvincula: en una edició, no enviar-lo deixaria el vincle
            // que hi havia.
            deute_id: Number.parseInt(document.getElementById('new-debt').value, 10) || -1,
            // Buit és "el que toca". Va sempre amb el deute: sense, no en paga cap.
            deute_rebut: document.getElementById('new-debt-receipt').value || null,
            // El mateix per al traspàs: -1 vol dir que ja no ho és.
            compte_contrapart_id: Number.parseInt(counterpartSelect.value, 10) || -1
        };
        if (Number.isInteger(accountId)) payload.account = { id: accountId };

        const editingId = document.getElementById('edit-id').value;

        try {
            if (editingId) {
                await updateTransaction(Number.parseInt(editingId, 10), payload);
            } else {
                await createTransaction(payload);
            }
            close();
            await loadData();
            // Els rebuts del desplegable han canviat d'estat amb aquest pagament.
            await refreshDebts();
            // Marcar un traspàs pot haver resolt un parell, o desar-ne un de nou.
            await refreshUnmarkedTransfers();
        } catch (failure) {
            error.textContent = failure.message || t('transactions.save-failed');
            error.classList.remove('hidden');
        } finally {
            // Es rehabilita sempre: si fallava, el botó quedava bloquejat i
            // calia tancar i tornar a obrir el formulari.
            submitButton.disabled = false;
        }
    });
}

/** «Pares (dec)», «Steam Deck (dec, saldat)»: el deute i en quin sentit va. */
function debtOptionLabel(debt) {
    const key = debt.direccio === 'DEC'
        ? (debt.saldat ? 'transactions.debt-option-owe-settled' : 'transactions.debt-option-owe')
        : (debt.saldat ? 'transactions.debt-option-owed-settled' : 'transactions.debt-option-owed');
    return t(key, { name: debt.nom });
}

/** Torna a llegir els deutes: el calendari de cadascun depèn dels pagaments. */
async function refreshDebts() {
    try {
        const debts = await getDebts();
        debtsById = new Map(debts.map(debt => [debt.id, debt]));
    } catch (error) {
        console.error('Error carregant deutes:', error);
    }
}

/** Avui en el format que espera un <input type="date">. */
function todayInputValue() {
    const now = new Date();
    // toISOString() passa a UTC i pot restar un dia segons la zona horària.
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    return `${now.getFullYear()}-${month}-${day}`;
}

async function loadData() {
    const categoryId = document.getElementById('filter-category').value;
    const companyId = document.getElementById('filter-company').value;
    const accountId = document.getElementById('filter-account').value;
    const type = document.getElementById('filter-type').value;
    const selectedMonth = document.getElementById('filter-month').value;

    const filters = {};
    if (categoryId) filters.categoryId = categoryId;
    if (companyId) filters.companyId = companyId;
    if (accountId) filters.accountId = accountId;
    if (type) filters.type = type;
    if (selectedMonth) {
        const { startDate, endDate } = buildMonthRange(selectedMonth);
        filters.startDate = startDate;
        filters.endDate = endDate;
    }

    try {
        const transactions = await getTransactions(filters);
        currentTransactions = transactions;
        renderTable(transactions, currentSortMode);
    } catch (error) {
        console.error('Error loading transactions:', error);
        currentTransactions = [];
        document.getElementById('transaction-count').textContent = '0';
        renderMessageRow(t('transactions.load-failed', { message: error.message }), true);
    }
}

/** Desplega totes les parts o, si ja ho estan totes, les plega. */
function toggleAllParts() {
    const splitIds = currentTransactions.filter(isSplit).map(transaction => transaction.id);
    const allExpanded = splitIds.every(id => expandedSplits.has(id));
    splitIds.forEach(id => (allExpanded ? expandedSplits.delete(id) : expandedSplits.add(id)));
    renderTable(currentTransactions, currentSortMode);
}

/** El botó només surt si a la llista hi ha algun moviment dividit. */
function updatePartsButton(transactions) {
    const button = document.getElementById('toggle-all-parts');
    if (!button) return;
    const splitIds = transactions.filter(isSplit).map(transaction => transaction.id);
    button.classList.toggle('hidden', splitIds.length === 0);
    button.textContent = t(splitIds.length > 0 && splitIds.every(id => expandedSplits.has(id))
        ? 'transactions.collapse-parts'
        : 'transactions.expand-parts');
}

function toggleSortMode() {
    currentSortMode = currentSortMode === 'month-desc' ? 'month-asc' : 'month-desc';
    updateSortButton();
    renderTable(currentTransactions, currentSortMode);
}

function updateSortButton() {
    const sortButton = document.getElementById('toggle-transaction-sort');

    if (!sortButton) {
        return;
    }

    sortButton.textContent = t(currentSortMode === 'month-desc' ? 'transactions.sort-newest' : 'transactions.sort-oldest');
    sortButton.setAttribute('aria-label', t('transactions.sort-aria', { order: sortButton.textContent }));
}

function renderTable(transactions, sortBy = 'month-desc') {
    const tbody = document.getElementById('transactions-body');
    document.getElementById('transaction-count').textContent = transactions.length;
    updatePartsButton(transactions);

    if (transactions.length === 0) {
        renderMessageRow(t('transactions.no-results'));
        return;
    }

    const sortedTransactions = [...transactions].sort((left, right) => compareTransactions(left, right, sortBy));
    const shouldGroupByMonth = sortBy.startsWith('month-');

    let currentMonthKey = null;
    tbody.innerHTML = sortedTransactions.map(transaction => {
        const currentDate = parseTransactionDate(transaction.data);
        const monthKey = getMonthKey(currentDate);
        const showMonthHeader = shouldGroupByMonth && monthKey !== currentMonthKey;

        if (showMonthHeader) {
            currentMonthKey = monthKey;
        }

        return `${showMonthHeader ? buildMonthRow(currentDate) : ''}${buildTransactionRow(transaction)}`;
    }).join('');
}

function compareTransactions(left, right, sortBy) {
    const leftDate = parseTransactionDate(left.data);
    const rightDate = parseTransactionDate(right.data);
    const leftTime = leftDate ? leftDate.getTime() : Number.NEGATIVE_INFINITY;
    const rightTime = rightDate ? rightDate.getTime() : Number.NEGATIVE_INFINITY;

    switch (sortBy) {
        case 'date-asc':
        case 'month-asc':
            return leftTime - rightTime;
        case 'date-desc':
        case 'month-desc':
        default:
            return rightTime - leftTime;
    }
}

function buildMonthRow(date) {
    return `
        <tr class="table-group-row">
            <td colspan="8">${formatMonth(date)}</td>
        </tr>
    `;
}

/** Les etiquetes de "no compta" i del deute, d'un moviment o d'una part. */
function budgetBadges(excluded, debtId) {
    return `
        ${excluded
            ? `<span class="badge badge-outline text-sm" title="${t('transactions.excluded-badge-title')}">${t('transactions.excluded-badge')}</span>`
            : ''}
        ${debtId
            ? `<span class="badge badge-outline text-sm" title="${t('transactions.debt-badge-title')}">
                   ${escapeHtml(t('transactions.debt-badge', { name: debtsById.get(debtId)?.nom || '?' }))}
               </span>`
            : ''}
    `;
}

/** "⇄ a Revolut": un traspàs entre comptes teus no és ni despesa ni ingrés. */
function transferBadge(transaction) {
    if (!transaction.compte_contrapart_id) return '';
    const counterpart = accountsById.get(transaction.compte_contrapart_id);
    return `<span class="badge badge-outline text-sm" title="${t('transactions.transfer-badge-title')}">
                ${escapeHtml(transferLabel(transaction.type, counterpart?.nom))}
            </span>`;
}

function buildTransactionRow(transaction) {
    const style = TYPES[typeOf(transaction)];
    const split = isSplit(transaction);
    const expanded = split && expandedSplits.has(transaction.id);

    // D'un moviment dividit manen les parts: la categoria, l'exclòs i el
    // deute del moviment ja no compten, i ensenyar-los confondria.
    return `
        <tr>
            <td>${escapeHtml(formatDate(transaction.data) || '-')}</td>
            <td class="text-sm">${escapeHtml(transaction.account?.nom || '-')}</td>
            <td class="text-sm ${style.classe}">${t(style.etiqueta)}</td>
            <td>
                ${escapeHtml(transaction.empresa || '-')}
                ${transferBadge(transaction)}
                ${split ? '' : budgetBadges(transaction.exclos_pressupost, transaction.deute_id)}
            </td>
            <td>
                ${split
                    ? partsToggle(transaction, expanded)
                    : `<span class="badge badge-outline">${escapeHtml(transaction.categoria || '-')}</span>`}
            </td>
            <td class="text-muted text-sm">${escapeHtml(transaction.descripcio_curta || '-')}</td>
            <td class="text-right font-bold ${style.classe}" style="white-space: nowrap;">
                ${style.signe}${formatAmount(transaction.cost)}
            </td>
            <td class="text-right" style="white-space: nowrap;">
                <button class="btn btn-sm btn-outline" data-action="split-transaction"
                        data-id="${transaction.id}" title="${t('transactions.split-title')}">
                    <span class="material-symbols-outlined text-sm">call_split</span>
                </button>
                <button class="btn btn-sm btn-outline" data-action="edit-transaction"
                        data-id="${transaction.id}" title="${t('transactions.edit-row')}">
                    <span class="material-symbols-outlined text-sm">edit</span>
                </button>
                <button class="btn btn-sm btn-outline" data-action="delete-transaction"
                        data-id="${transaction.id}" title="${t('transactions.delete-row')}">
                    <span class="material-symbols-outlined text-sm">delete</span>
                </button>
            </td>
        </tr>
        ${expanded ? transaction.parts.map(part => buildPartRow(part, style)).join('') : ''}
    `;
}

/**
 * La pastilla "dividit en N", que plega i desplega les parts. Plegada, el
 * títol ja diu on va cada part, per no haver-la d'obrir per saber-ho.
 */
function partsToggle(transaction, expanded) {
    const summary = transaction.parts
        .map(part => `${part.category?.nom || '-'}: ${formatAmount(part.import)}`)
        .join(' · ');
    return `<button type="button" class="badge badge-secondary parts-toggle" data-action="toggle-parts"
                    data-id="${transaction.id}" aria-expanded="${expanded}"
                    title="${escapeHtml(t(expanded ? 'transactions.hide-parts' : 'transactions.show-parts', { summary }))}">
                ${escapeHtml(t('transactions.split-in', { count: transaction.parts.length }))}
                <span class="material-symbols-outlined text-sm">${expanded ? 'expand_less' : 'expand_more'}</span>
            </button>`;
}

/** Una part, just a sota del seu moviment. */
function buildPartRow(part, style) {
    return `
        <tr class="text-sm">
            <td></td>
            <td></td>
            <td></td>
            <td class="text-muted">
                <span class="material-symbols-outlined text-sm align-middle">subdirectory_arrow_right</span>
                ${budgetBadges(part.exclos_pressupost, part.deute_id)}
            </td>
            <td><span class="badge badge-outline">${escapeHtml(part.category?.nom || '-')}</span></td>
            <td class="text-muted text-sm">${escapeHtml(part.descripcio || '')}</td>
            <td class="text-right ${style.classe}" style="white-space: nowrap;">
                ${style.signe}${formatAmount(part.import)}
            </td>
            <td></td>
        </tr>
    `;
}

/** @param message text pla: pot dur el missatge d'error del backend, que repeteix el que se li ha enviat. */
function renderMessageRow(message, isError = false) {
    document.getElementById('transactions-body').innerHTML = `
        <tr>
            <td colspan="8" class="text-center ${isError ? 'text-error' : ''}">${escapeHtml(message)}</td>
        </tr>
    `;
}

function populateMonthOptions(transactions) {
    const monthSelect = document.getElementById('filter-month');
    const previousValue = monthSelect.value;
    const monthKeys = [...new Set(
        transactions
            .map(transaction => getMonthKey(parseTransactionDate(transaction.data)))
            .filter(monthKey => monthKey !== 'no-date')
    )].sort((left, right) => right.localeCompare(left));

    monthSelect.innerHTML = `<option value="">${t('transactions.all-months')}</option>`;

    monthKeys.forEach(monthKey => {
        const option = document.createElement('option');
        option.value = monthKey;
        option.textContent = formatMonth(createMonthDate(monthKey));
        monthSelect.appendChild(option);
    });

    if (monthKeys.includes(previousValue)) {
        monthSelect.value = previousValue;
    }
}

function buildMonthRange(monthKey) {
    const [year, month] = monthKey.split('-').map(Number);
    const lastDay = new Date(year, month, 0).getDate();

    return {
        startDate: `${year}-${String(month).padStart(2, '0')}-01`,
        endDate: `${year}-${String(month).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`
    };
}

function parseTransactionDate(rawDate) {
    if (!rawDate) {
        return null;
    }

    if (/^\d{4}-\d{2}-\d{2}$/.test(rawDate)) {
        const [year, month, day] = rawDate.split('-').map(Number);
        return new Date(year, month - 1, day);
    }

    const parsedDate = new Date(rawDate);
    return Number.isNaN(parsedDate.getTime()) ? null : parsedDate;
}

function formatMonth(date) {
    if (!date) {
        return t('transactions.no-date');
    }

    const label = monthAndYear(date.getFullYear(), date.getMonth() + 1);
    return label.charAt(0).toUpperCase() + label.slice(1);
}

function getMonthKey(date) {
    if (!date) {
        return 'no-date';
    }

    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function createMonthDate(monthKey) {
    const [year, month] = monthKey.split('-').map(Number);
    return new Date(year, month - 1, 1);
}

function formatAmount(amount) {
    const numericAmount = Number.parseFloat(amount);
    return formatCurrency(Number.isFinite(numericAmount) ? numericAmount : 0);
}
