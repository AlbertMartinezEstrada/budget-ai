import {
    uploadCsv, confirmTransactions, getCategories, getAccounts, getDebts,
    getImportRules, createImportRule, deleteImportRule, formatCurrency, escapeHtml
} from '../../api.js';
import { categoryOptions, leafCategories } from '../../categoryOptions.js';
import { counterpartOptions, transferCounts } from '../../transfers.js';
import { setUpSplitEditor } from '../transactions/SplitEditor.js';
import { t, formatDate } from '../../i18n.js';

/**
 * Ingrés o despesa.
 *
 * L'import es desa sempre en positiu i el signe viu només al tipus, així que
 * sense ensenyar-lo un ingrés de 2.000 € i una despesa de 2.000 € es veien
 * exactament igual a la revisió. I no és un detall estètic: el tipus decideix
 * si el saldo del compte puja o baixa en confirmar.
 */
const TYPES = {
    EXPENSE: { etiqueta: 'common.expense', signe: '−', classe: 'text-error' },
    INCOME:  { etiqueta: 'common.income',  signe: '+', classe: 'text-success' }
};

const typeOf = (transaction) => TYPES[transaction.type] ? transaction.type : 'EXPENSE';

export async function initUpload(container) {
    container.innerHTML = `
        <div class="card mb-4">
            <div class="card-header">
                <h3 class="card-title">${t('upload.title')}</h3>
                <span class="material-symbols-outlined" style="font-size: 1.5rem; color: var(--primary-color);">cloud_upload</span>
            </div>
            <div class="upload-area" id="drop-zone">
                <input type="file" id="file-input" accept=".csv" class="file-input">
                <label for="file-input" class="file-label">
                    <span class="material-symbols-outlined" style="font-size: 3rem; color: var(--text-secondary);">csv</span>
                    <span class="mt-2 text-sm text-gray-500">${t('upload.drop')}</span>
                </label>
                <button id="upload-btn" class="btn btn-primary mt-4" disabled>${t('upload.analyse')}</button>
            </div>
            <div class="form-group mt-3">
                <label for="upload-account">${t('upload.account')}</label>
                <select id="upload-account" class="form-control"></select>
                <p class="text-sm text-muted mt-1">${t('upload.account-hint')}</p>
            </div>
            <div id="upload-status" class="mt-4 text-center hidden"></div>
        </div>

        <details class="card mb-4">
            <summary style="cursor: pointer; padding: 1rem; font-weight: 600;">${t('upload.rules')}</summary>
            <div class="p-3">
                <p class="text-sm text-muted mb-3">${t('upload.rules-hint')}</p>
                <form id="rule-form" class="flex flex-wrap items-end gap-2 mb-3">
                    <div class="form-group" style="flex: 1; min-width: 12rem; margin: 0;">
                        <label for="rule-pattern">${t('upload.rule-pattern')}</label>
                        <input type="text" id="rule-pattern" class="form-control"
                               placeholder="${t('upload.rule-pattern-placeholder')}" required>
                    </div>
                    <div class="form-group" style="min-width: 12rem; margin: 0;">
                        <label for="rule-transfer">${t('upload.rule-transfer')}</label>
                        <select id="rule-transfer" class="form-control"></select>
                    </div>
                    <div class="form-group" style="min-width: 12rem; margin: 0;">
                        <label for="rule-category">${t('upload.rule-category')}</label>
                        <select id="rule-category" class="form-control"></select>
                    </div>
                    <button type="submit" class="btn btn-primary">${t('upload.add-rule')}</button>
                </form>
                <div id="rules-list"></div>
            </div>
        </details>

        <div id="review-section" class="card hidden">
            <div class="card-header bg-warning-light">
                <h3 class="card-title text-warning-dark">${t('upload.review-title')}</h3>
                <div class="flex gap-2">
                    <button id="cancel-review" class="btn btn-outline btn-sm">${t('common.cancel')}</button>
                    <button id="confirm-review" class="btn btn-success btn-sm">${t('upload.confirm')}</button>
                </div>
            </div>

            <div class="flex flex-wrap items-center justify-between gap-3 p-3">
                <input type="search" id="review-search" class="input input-sm"
                       style="min-width: 16rem;"
                       placeholder="${t('upload.search-placeholder')}">
                <div class="flex items-center gap-2">
                    <button id="select-all" class="btn btn-outline btn-sm" type="button">${t('upload.select-all')}</button>
                    <button id="select-none" class="btn btn-outline btn-sm" type="button">${t('upload.select-none')}</button>
                </div>
            </div>
            <p id="review-summary" class="px-3 pb-2 text-sm text-gray-500"></p>

            <div class="table-responsive max-h-96 overflow-y-auto">
                <table class="table table-sm">
                    <thead>
                        <tr>
                            <th style="width: 2.5rem;"></th>
                            <th>${t('table.date')}</th>
                            <th>${t('common.type')}</th>
                            <th>${t('upload.company-editable')}</th>
                            <th>${t('upload.category-select')}</th>
                            <th class="text-right">${t('table.amount')}</th>
                            <th title="${t('upload.excluded-title')}">${t('transactions.excluded')}</th>
                            <th style="width: 3rem;"></th>
                        </tr>
                    </thead>
                    <tbody id="review-body"></tbody>
                </table>
            </div>
            <p id="review-empty" class="p-4 text-center text-gray-500 hidden">${t('upload.no-match')}</p>
        </div>
    `;

    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');
    const uploadButton = document.getElementById('upload-btn');
    const statusDiv = document.getElementById('upload-status');
    const reviewSection = document.getElementById('review-section');
    const reviewBody = document.getElementById('review-body');
    const confirmButton = document.getElementById('confirm-review');
    const cancelButton = document.getElementById('cancel-review');

    let currentReviewData = [];
    let categoriesList = [];
    let debts = [];

    // Load categories immediately
    try {
        categoriesList = await getCategories();
    } catch (error) {
        console.error('Error loading categories:', error);
    }

    // Per vincular una part d'un moviment a un deute. Sense deutes la revisió
    // funciona igual: només no se'n poden triar.
    try {
        debts = await getDebts();
    } catch (error) {
        console.error('Error loading debts:', error);
    }
    const debtsById = new Map(debts.map(debt => [debt.id, debt]));
    const leaves = leafCategories(categoriesList);
    const leafNameById = new Map(leaves.map(category => [category.id, category.nom]));
    const leafNames = new Set(leaves.map(category => category.nom));

    // Dividir un moviment abans d'importar-lo: les parts es guarden a la fila
    // i s'envien amb la confirmació. El backend les valida abans de moure cap
    // saldo, i si alguna no quadra no s'importa res.
    const openSplitter = setUpSplitEditor(container, {
        categories: categoriesList,
        debts,
        save: async (transaction, parts) => {
            transaction.parts = parts;
            renderReviewTable();
        },
        remove: async (transaction) => {
            delete transaction.parts;
            renderReviewTable();
        }
    });
    const isSplit = (transaction) => (transaction.parts || []).length > 0;

    // Un extracte és d'un compte. Sense triar-lo, tot queia al principal i amb
    // tres comptes els saldos i el pressupost deixaven de voler dir res.
    let accounts = [];
    try {
        accounts = await getAccounts();
        document.getElementById('upload-account').innerHTML = accounts
            .map(account => `<option value="${account.id}">${escapeHtml(account.nom)}</option>`)
            .join('');
    } catch (error) {
        console.error('Error loading accounts:', error);
    }
    const accountsById = new Map(accounts.map(account => [account.id, account]));
    const importAccount = () => accountsById.get(Number.parseInt(document.getElementById('upload-account').value, 10));

    // ============ REGLES ============
    const rulesList = document.getElementById('rules-list');

    async function renderRules() {
        try {
            const rules = await getImportRules();
            rulesList.innerHTML = rules.length === 0
                ? `<p class="text-sm text-muted">${t('upload.no-rules')}</p>`
                : rules.map(rule => `
                    <div class="flex items-center justify-between gap-2 py-1 text-sm">
                        <span>
                            <code>${escapeHtml(rule.patro)}</code>
                            ${rule.compte_traspas_id
                                ? escapeHtml(t('upload.rule-to-transfer', {
                                    account: accountsById.get(rule.compte_traspas_id)?.nom || t('transfers.another-account')
                                }))
                                : rule.marca_exclos ? t('upload.rule-excluded') : ''}
                            ${rule.categoria ? `→ ${escapeHtml(rule.categoria)}` : ''}
                        </span>
                        <button class="btn btn-sm btn-outline" data-action="delete-rule"
                                data-id="${rule.id}" type="button">${t('common.delete')}</button>
                    </div>`).join('');
        } catch (error) {
            rulesList.innerHTML = `<p class="text-error text-sm">${escapeHtml(error.message)}</p>`;
        }
    }

    // Només fulles: una regla que assignés un grup faria que el moviment es
    // rebutgés en confirmar.
    const ruleCategorySelect = document.getElementById('rule-category');
    ruleCategorySelect.innerHTML = `<option value="">${t('upload.rule-keep-category')}</option>`
        + categoryOptions(categoriesList, { value: (category) => category.nom });

    // Qualsevol compte: la regla s'aplica a tots els extractes, i al del
    // mateix compte el backend no la fa servir.
    const ruleTransferSelect = document.getElementById('rule-transfer');
    ruleTransferSelect.innerHTML = counterpartOptions(accounts, null, null, t('upload.rule-not-transfer'));

    document.getElementById('rule-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        const patro = document.getElementById('rule-pattern').value.trim();
        if (!patro) return;

        try {
            const transferAccountId = Number.parseInt(ruleTransferSelect.value, 10);
            await createImportRule({
                patro,
                // Un traspàs no es marca: si compta, ho decideixen els comptes.
                marca_exclos: !Number.isInteger(transferAccountId),
                categoria: ruleCategorySelect.value || null,
                ...(Number.isInteger(transferAccountId) ? { compte_traspas_id: transferAccountId } : {})
            });
            document.getElementById('rule-pattern').value = '';
            ruleTransferSelect.value = '';
            await renderRules();
        } catch (error) {
            alert(error.message || t('upload.rule-save-failed'));
        }
    });

    rulesList.addEventListener('click', async (event) => {
        const button = event.target.closest('button[data-action="delete-rule"]');
        if (!button) return;

        try {
            await deleteImportRule(Number.parseInt(button.dataset.id, 10));
            await renderRules();
        } catch (error) {
            alert(error.message || t('upload.rule-delete-failed'));
        }
    });

    await renderRules();

    fileInput.addEventListener('change', () => {
        if (fileInput.files.length > 0) {
            uploadButton.disabled = false;
            statusDiv.textContent = t('upload.file-selected', { name: fileInput.files[0].name });
            statusDiv.classList.remove('hidden');
        } else {
            uploadButton.disabled = true;
            statusDiv.classList.add('hidden');
        }
    });

    uploadButton.addEventListener('click', async () => {
        const file = fileInput.files[0];
        if (!file) return;

        uploadButton.disabled = true;
        statusDiv.innerHTML = `<div class="spinner"></div> ${t('upload.analysing')}`;
        statusDiv.classList.remove('hidden', 'text-error', 'text-success');

        try {
            const result = await uploadCsv(file, document.getElementById('upload-account').value);
            if (result.status === 'info') {
                // Tot el fitxer ja era a la base de dades: no és cap error. Abans
                // sortia «Resposta inesperada del servidor» en tornar a pujar un
                // extracte ja importat.
                reviewSection.classList.add('hidden');
                statusDiv.textContent = t('upload.info', { message: result.message });
                uploadButton.disabled = false;
                return;
            }
            if (result.status === 'review') {
                // Tot entra marcat: el cas normal és importar-ho sencer i
                // descartar-ne quatre, no al revés. Menys l'altra pota d'un
                // traspàs ja desat: tornar-la a importar mouria el saldo dues vegades.
                currentReviewData = result.data.map(transaction => ({
                    ...transaction,
                    inclos: !transaction.traspas_registrat
                }));
                document.getElementById('review-search').value = '';
                renderReviewTable();
                reviewSection.classList.remove('hidden');
                statusDiv.textContent = t('upload.analysis-done');
                statusDiv.classList.add('text-success');
            } else {
                throw new Error(t('upload.unexpected-response'));
            }
        } catch (error) {
            statusDiv.textContent = t('upload.failed', { message: error.message });
            statusDiv.classList.add('text-error');
            uploadButton.disabled = false;
        }
    });

    /**
     * Files que passen el filtre de cerca, amb el seu índex original.
     *
     * L'índex ha de sobreviure al filtre: és el que lliga cada <tr> amb la seva
     * posició a currentReviewData, i sense ell una cerca desplaçaria les
     * edicions a un altre moviment.
     */
    function visibleRows() {
        const query = document.getElementById('review-search').value.trim().toLowerCase();
        const rows = currentReviewData.map((transaction, index) => ({ transaction, index }));
        if (!query) return rows;

        return rows.filter(({ transaction }) => [
            transaction.empresa, transaction.concepte_original, transaction.categoria, transaction.cost,
            // També pel tipus, per poder aïllar d'un cop tot el que entra.
            t(TYPES[typeOf(transaction)].etiqueta)
        ].some(field => String(field ?? '').toLowerCase().includes(query)));
    }

    function renderReviewTable() {
        const rows = visibleRows();

        reviewBody.innerHTML = rows.map(({ transaction, index }) => {
            // Si la categoria que proposa la IA no és a la llista oficial, el
            // navegador seleccionava la primera opció sense dir res i el
            // moviment s'acabava desant com a "Menjar i supermercat". Ara es
            // marca explícitament perquè es vegi que cal revisar-la.
            // Un bloc tampoc hi és: només s'hi ofereixen fulles, i si la IA en
            // proposa un, la fila ha de demanar que se'n triï una.
            const known = leafNames.has(transaction.categoria);

            const options = categoryOptions(categoriesList, {
                value: (category) => category.nom,
                selected: known ? transaction.categoria : null
            });

            const unknownOption = known
                ? ''
                : `<option value="" selected>${t('common.choose-category')}</option>`;

            // Un moviment descartat no s'ha de revisar: ni cal categoria ni ha
            // de cridar l'atenció com si li faltés alguna cosa. Un de dividit
            // tampoc: la categoria la porta cada part.
            const split = isSplit(transaction);
            const needsReview = transaction.inclos && !known && !split;

            const type = typeOf(transaction);
            const style = TYPES[type];

            return `
            <tr data-index="${index}" class="${needsReview ? 'row-needs-review' : ''}"
                style="${transaction.inclos ? '' : 'opacity: 0.45;'}">
                <td>
                    <input type="checkbox" name="inclos" ${transaction.inclos ? 'checked' : ''}
                           title="${t('upload.include-title')}">
                </td>
                <td>${escapeHtml(formatDate(transaction.data) || transaction.data)}</td>
                <td>
                    <select class="input input-sm" name="type"
                            title="${t('upload.type-title')}">
                        <option value="EXPENSE" ${type === 'EXPENSE' ? 'selected' : ''}>${t('common.expense')}</option>
                        <option value="INCOME" ${type === 'INCOME' ? 'selected' : ''}>${t('common.income')}</option>
                    </select>
                </td>
                <td>
                    <input type="text" class="input input-sm w-full" value="${escapeHtml(transaction.empresa || '')}" name="empresa">
                    ${transferCell(transaction)}
                </td>
                <td>
                    ${split
                        ? `<span class="badge badge-secondary" title="${t('upload.split-title')}">${escapeHtml(t('transactions.split-in', { count: transaction.parts.length }))}</span>`
                        : `<select class="input input-sm w-full" name="categoria" ${needsReview ? 'required' : ''}>
                               ${unknownOption}${options}
                           </select>`}
                </td>
                <td class="text-right ${style.classe}" style="white-space: nowrap;">
                    ${style.signe}${formatCurrency(transaction.cost)}
                </td>
                <td class="text-center">
                    ${split
                        ? `<span class="text-muted" title="${t('upload.split-excluded-title')}">—</span>`
                        : isNeutralTransfer(transaction)
                            ? `<input type="checkbox" name="exclos" checked disabled
                                      title="${t('upload.neutral-title')}">`
                            : `<input type="checkbox" name="exclos" ${transaction.exclos_pressupost ? 'checked' : ''}
                                      title="${t('upload.exclude-title')}">`}
                </td>
                <td class="text-right">
                    <button type="button" class="btn btn-sm btn-outline" data-action="split-row" data-index="${index}"
                            title="${t('transactions.split-title')}">
                        <span class="material-symbols-outlined text-sm">call_split</span>
                    </button>
                </td>
            </tr>
            ${split ? transaction.parts.map(part => partRow(part, style)).join('') : ''}
            `;
        }).join('');

        document.getElementById('review-empty').classList.toggle('hidden', rows.length > 0);
        updateSummary();
    }

    /** Un traspàs entre comptes del dia a dia: no compta, i no es pot fer comptar. */
    const isNeutralTransfer = (transaction) => Boolean(transaction.compte_contrapart_id)
        && !transaction.traspas_registrat
        && !transferCounts(importAccount(), accountsById.get(transaction.compte_contrapart_id));

    /**
     * Si la fila és un traspàs, a quin compte. Una línia que ja és l'altra
     * pota d'un traspàs desat ho diu, i surt desmarcada.
     */
    function transferCell(transaction) {
        if (transaction.traspas_registrat) {
            const from = accountsById.get(transaction.compte_contrapart_id)?.nom || t('transfers.another-account');
            return `<div class="text-sm text-muted mt-1" title="${t('upload.registered-title')}">
                        ${escapeHtml(t('upload.registered', { account: from }))}
                    </div>`;
        }
        return `<select class="input input-sm w-full mt-1" name="traspas"
                        title="${t('upload.transfer-title')}">
                    ${counterpartOptions(accounts, importAccount()?.id, transaction.compte_contrapart_id)}
                </select>`;
    }

    /** Una part, sota el seu moviment. Sense data-index: no és cap fila editable. */
    function partRow(part, style) {
        const debtName = part.deute_id > 0 ? debtsById.get(part.deute_id)?.nom : null;
        return `
            <tr class="text-sm">
                <td></td>
                <td></td>
                <td></td>
                <td class="text-muted">
                    <span class="material-symbols-outlined text-sm align-middle">subdirectory_arrow_right</span>
                    ${escapeHtml(part.descripcio || '')}
                    ${part.exclos_pressupost ? `<span class="badge badge-outline text-sm">${t('transactions.excluded-badge')}</span>` : ''}
                    ${debtName ? `<span class="badge badge-outline text-sm">${escapeHtml(t('transactions.debt-badge', { name: debtName }))}</span>` : ''}
                </td>
                <td><span class="badge badge-outline">${escapeHtml(leafNameById.get(part.category?.id) || '-')}</span></td>
                <td class="text-right ${style.classe}" style="white-space: nowrap;">
                    ${style.signe}${formatCurrency(part.import)}
                </td>
                <td></td>
                <td></td>
            </tr>
        `;
    }

    reviewBody.addEventListener('click', (event) => {
        const button = event.target.closest('button[data-action="split-row"]');
        if (!button) return;
        const transaction = currentReviewData[Number.parseInt(button.dataset.index, 10)];
        if (transaction) openSplitter(transaction);
    });

    function updateSummary() {
        const chosen = currentReviewData.filter(transaction => transaction.inclos);
        const hidden = currentReviewData.length - visibleRows().length;

        // Els dos costats van separats: sumar-los en una sola xifra restaria
        // els ingressos de les despeses i no voldria dir res.
        const sumOf = (type) => chosen
            .filter(transaction => typeOf(transaction) === type)
            .reduce((sum, transaction) => sum + (Number.parseFloat(transaction.cost) || 0), 0);

        const spent = sumOf('EXPENSE');
        const earned = sumOf('INCOME');

        document.getElementById('review-summary').textContent = [
            t('upload.summary-selected', { chosen: chosen.length, total: currentReviewData.length }),
            t('upload.summary-spent', { amount: formatCurrency(spent) }),
            ...(earned > 0 ? [t('upload.summary-earned', { amount: formatCurrency(earned) })] : []),
            ...(hidden > 0 ? [t('upload.summary-hidden', { count: hidden })] : [])
        ].join(' · ');

        confirmButton.textContent = chosen.length === currentReviewData.length
            ? t('upload.confirm-all')
            : t('upload.confirm-count', { count: chosen.length });
        confirmButton.disabled = chosen.length === 0;
    }

    /**
     * Cada edició es desa a currentReviewData de seguida.
     *
     * La taula es repinta sencera en cercar o en marcar, i el que hi hagi
     * escrit als camps es perdria: només vivia al DOM. Es fa per delegació
     * perquè les files no existeixen quan s'enganxa el listener.
     */
    reviewBody.addEventListener('input', (event) => {
        const row = event.target.closest('tr[data-index]');
        if (!row) return;

        const record = currentReviewData[row.dataset.index];
        if (event.target.name === 'empresa') record.empresa = event.target.value;

        if (event.target.name === 'categoria') {
            record.categoria = event.target.value;
            // La fila deixa d'estar marcada en triar-li categoria. Sense això
            // seguia en vermell fins al següent repintat i semblava que la
            // selecció no s'hagués desat.
            row.classList.toggle('row-needs-review', !record.categoria);
            event.target.required = !record.categoria;
        }

        if (event.target.name === 'type') {
            record.type = event.target.value;
            // Canviar el tipus canvia el signe i el color de l'import, i també
            // de quin costat compta al resum: es repinta la fila sencera.
            renderReviewTable();
        }
    });

    reviewBody.addEventListener('change', (event) => {
        if (event.target.name === 'traspas') {
            const row = event.target.closest('tr[data-index]');
            if (!row) return;
            const record = currentReviewData[row.dataset.index];
            const counterpart = accountsById.get(Number.parseInt(event.target.value, 10));
            const wasNeutral = record.compte_contrapart_id
                && !transferCounts(importAccount(), accountsById.get(record.compte_contrapart_id));
            record.compte_contrapart_id = counterpart ? counterpart.id : null;
            // El mateix que farà el backend: entre comptes del dia a dia no
            // compta. Si la marca l'havia posat el traspàs, se'n va amb ell.
            if (counterpart && !transferCounts(importAccount(), counterpart)) {
                record.exclos_pressupost = true;
            } else if (wasNeutral) {
                record.exclos_pressupost = false;
            }
            renderReviewTable();
            return;
        }

        if (event.target.name !== 'inclos' && event.target.name !== 'exclos') return;

        const row = event.target.closest('tr[data-index]');
        if (!row) return;

        if (event.target.name === 'exclos') {
            currentReviewData[row.dataset.index].exclos_pressupost = event.target.checked;
            updateSummary();
            return;
        }

        currentReviewData[row.dataset.index].inclos = event.target.checked;
        // No es repinta la taula sencera: només canvia aquesta fila, i
        // repintar-la mouria l'scroll de l'usuari a dalt de tot.
        row.style.opacity = event.target.checked ? '' : '0.45';
        updateSummary();
    });

    document.getElementById('review-search').addEventListener('input', renderReviewTable);

    // Marquen i desmarquen el lot sencer, també el que la cerca amaga: així
    // "desmarcar-ho tot" sempre deixa zero, es miri el que es miri.
    document.getElementById('select-all').addEventListener('click', () => {
        currentReviewData.forEach(transaction => { transaction.inclos = true; });
        renderReviewTable();
    });

    document.getElementById('select-none').addEventListener('click', () => {
        currentReviewData.forEach(transaction => { transaction.inclos = false; });
        renderReviewTable();
    });

    confirmButton.addEventListener('click', async () => {
        // Les dades surten de currentReviewData i no del DOM: amb la cerca
        // activa, la taula només ensenya una part i llegir-ne les files
        // n'importaria una part.
        const accountId = Number.parseInt(document.getElementById('upload-account').value, 10);

        const confirmedData = currentReviewData
            .filter(transaction => transaction.inclos)
            // `inclos` és de la pantalla, no del model: el backend no l'espera.
            .map(({ inclos, ...transaction }) => ({
                ...transaction,
                // Tot el fitxer va al compte triat a dalt.
                ...(Number.isInteger(accountId) ? { account: { id: accountId } } : {}),
                exclos_pressupost: Boolean(transaction.exclos_pressupost),
                // Una línia reconeguda com l'altra pota d'un traspàs que l'usuari
                // torna a marcar és un altre moviment: desada com a traspàs,
                // tornaria a moure el saldo de l'altre compte.
                ...(transaction.traspas_registrat ? { compte_contrapart_id: -1 } : {})
            }));

        if (confirmedData.length === 0) {
            statusDiv.textContent = t('upload.nothing-selected');
            statusDiv.classList.remove('hidden');
            return;
        }

        // Només es valida el que s'importa: un moviment descartat pot quedar
        // sense categoria i no ha de bloquejar la resta. Un de dividit porta
        // la categoria a cada part.
        const missingCategory = confirmedData.some(transaction => !transaction.categoria && !isSplit(transaction));
        if (missingCategory) {
            const search = document.getElementById('review-search');
            if (search.value) {
                // Si la cerca amagava la fila incompleta, l'avís semblava fals.
                search.value = '';
                renderReviewTable();
            }
            statusDiv.textContent = t('upload.missing-category');
            statusDiv.classList.remove('hidden');
            return;
        }

        confirmButton.disabled = true;
        statusDiv.textContent = t('common.saving');

        try {
            const discarded = currentReviewData.length - confirmedData.length;
            const result = await confirmTransactions(confirmedData);
            // Els descartats es diuen explícitament: si no, veure menys
            // moviments dels que tenia el fitxer sembla que se n'hagin perdut.
            statusDiv.textContent = t('upload.saved', { message: result.message || t('upload.saved-default') })
                + (discarded > 0 ? ' ' + t('upload.discarded', { count: discarded }) : '');
            reviewSection.classList.add('hidden');
            currentReviewData = [];
            reviewBody.innerHTML = '';
            document.getElementById('review-search').value = '';
            fileInput.value = '';
            uploadButton.disabled = true;
            setTimeout(() => {
                statusDiv.classList.add('hidden');
            }, 4000);
        } catch (error) {
            statusDiv.textContent = t('upload.save-failed', { message: error.message });
        } finally {
            // Es rehabilita sempre: si fallava, el botó quedava bloquejat.
            confirmButton.disabled = false;
        }
    });

    cancelButton.addEventListener('click', () => {
        reviewSection.classList.add('hidden');
        currentReviewData = [];
        reviewBody.innerHTML = '';
        document.getElementById('review-search').value = '';
        statusDiv.textContent = t('upload.cancelled');
        statusDiv.classList.remove('hidden');
        confirmButton.disabled = false;
        uploadButton.disabled = !fileInput.files.length;
    });

    // Arrossegar i deixar anar: la zona ho anunciava però no estava implementat.
    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, (event) => {
            event.preventDefault();
            event.stopPropagation();
        });
    });

    ['dragenter', 'dragover'].forEach(eventName => {
        dropZone.addEventListener(eventName, () => dropZone.classList.add('drag-over'));
    });

    ['dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, () => dropZone.classList.remove('drag-over'));
    });

    dropZone.addEventListener('drop', (event) => {
        const file = event.dataTransfer?.files?.[0];
        if (!file) return;

        if (!file.name.toLowerCase().endsWith('.csv')) {
            statusDiv.textContent = t('upload.csv-only');
            statusDiv.classList.remove('hidden');
            return;
        }

        fileInput.files = event.dataTransfer.files;
        fileInput.dispatchEvent(new Event('change'));
    });
}
