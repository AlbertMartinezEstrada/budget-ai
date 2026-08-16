import { uploadCsv, confirmTransactions, getCategories, formatCurrency, escapeHtml } from '../../api.js';

/**
 * Ingrés o despesa.
 *
 * L'import es desa sempre en positiu i el signe viu només al tipus, així que
 * sense ensenyar-lo un ingrés de 2.000 € i una despesa de 2.000 € es veien
 * exactament igual a la revisió. I no és un detall estètic: el tipus decideix
 * si el saldo del compte puja o baixa en confirmar.
 */
const TYPES = {
    EXPENSE: { etiqueta: 'Despesa', signe: '−', classe: 'text-error' },
    INCOME:  { etiqueta: 'Ingrés',  signe: '+', classe: 'text-success' }
};

const typeOf = (t) => TYPES[t.type] ? t.type : 'EXPENSE';

export async function initUpload(container) {
    container.innerHTML = `
        <div class="card mb-4">
            <div class="card-header">
                <h3 class="card-title">Pujar Extracte Bancari</h3>
                <i class="ph ph-cloud-arrow-up" style="font-size: 1.5rem; color: var(--primary-color);"></i>
            </div>
            <div class="upload-area" id="drop-zone">
                <input type="file" id="file-input" accept=".csv" class="file-input">
                <label for="file-input" class="file-label">
                    <i class="ph ph-file-csv" style="font-size: 3rem; color: var(--text-secondary);"></i>
                    <span class="mt-2 text-sm text-gray-500">Arrossega un fitxer CSV o fes clic per seleccionar-lo</span>
                </label>
                <button id="upload-btn" class="btn btn-primary mt-4" disabled>Pujar i Analitzar</button>
            </div>
            <div id="upload-status" class="mt-4 text-center hidden"></div>
        </div>

        <div id="review-section" class="card hidden">
            <div class="card-header bg-warning-light">
                <h3 class="card-title text-warning-dark">Revisió de Transaccions</h3>
                <div class="flex gap-2">
                    <button id="cancel-review" class="btn btn-outline btn-sm">Cancel·lar</button>
                    <button id="confirm-review" class="btn btn-success btn-sm">Confirmar</button>
                </div>
            </div>

            <div class="flex flex-wrap items-center justify-between gap-3 p-3">
                <input type="search" id="review-search" class="input input-sm"
                       style="min-width: 16rem;"
                       placeholder="Buscar per empresa, concepte, categoria o import…">
                <div class="flex items-center gap-2">
                    <button id="select-all" class="btn btn-outline btn-sm" type="button">Marcar-ho tot</button>
                    <button id="select-none" class="btn btn-outline btn-sm" type="button">Desmarcar-ho tot</button>
                </div>
            </div>
            <p id="review-summary" class="px-3 pb-2 text-sm text-gray-500"></p>

            <div class="table-responsive max-h-96 overflow-y-auto">
                <table class="table table-sm">
                    <thead>
                        <tr>
                            <th style="width: 2.5rem;"></th>
                            <th>Data</th>
                            <th>Tipus</th>
                            <th>Empresa (Editable)</th>
                            <th>Categoria (Seleccionar)</th>
                            <th class="text-right">Import</th>
                        </tr>
                    </thead>
                    <tbody id="review-body"></tbody>
                </table>
            </div>
            <p id="review-empty" class="p-4 text-center text-gray-500 hidden">
                Cap moviment coincideix amb la cerca.
            </p>
        </div>
    `;

    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');
    const uploadBtn = document.getElementById('upload-btn');
    const statusDiv = document.getElementById('upload-status');
    const reviewSection = document.getElementById('review-section');
    const reviewBody = document.getElementById('review-body');
    const confirmBtn = document.getElementById('confirm-review');
    const cancelBtn = document.getElementById('cancel-review');

    let currentReviewData = [];
    let categoriesList = [];

    // Load categories immediately
    try {
        categoriesList = await getCategories();
    } catch (error) {
        console.error('Error loading categories:', error);
    }

    fileInput.addEventListener('change', () => {
        if (fileInput.files.length > 0) {
            uploadBtn.disabled = false;
            statusDiv.textContent = `Fitxer seleccionat: ${fileInput.files[0].name}`;
            statusDiv.classList.remove('hidden');
        } else {
            uploadBtn.disabled = true;
            statusDiv.classList.add('hidden');
        }
    });

    uploadBtn.addEventListener('click', async () => {
        const file = fileInput.files[0];
        if (!file) return;

        uploadBtn.disabled = true;
        statusDiv.innerHTML = '<div class="spinner"></div> Analitzant amb IA...';
        statusDiv.classList.remove('hidden', 'text-error', 'text-success');

        try {
            const result = await uploadCsv(file);
            if (result.status === 'review') {
                // Tot entra marcat: el cas normal és importar-ho sencer i
                // descartar-ne quatre, no al revés.
                currentReviewData = result.data.map(t => ({ ...t, inclos: true }));
                document.getElementById('review-search').value = '';
                renderReviewTable();
                reviewSection.classList.remove('hidden');
                statusDiv.textContent = '✅ Anàlisi completada. Si us plau, revisa les dades.';
                statusDiv.classList.add('text-success');
            } else {
                throw new Error('Resposta inesperada del servidor');
            }
        } catch (error) {
            statusDiv.textContent = `❌ Error: ${error.message}`;
            statusDiv.classList.add('text-error');
            uploadBtn.disabled = false;
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
        const rows = currentReviewData.map((t, index) => ({ t, index }));
        if (!query) return rows;

        return rows.filter(({ t }) => [
            t.empresa, t.concepte_original, t.categoria, t.cost,
            // També pel tipus, per poder aïllar d'un cop tot el que entra.
            TYPES[typeOf(t)].etiqueta
        ].some(field => String(field ?? '').toLowerCase().includes(query)));
    }

    function renderReviewTable() {
        const rows = visibleRows();

        reviewBody.innerHTML = rows.map(({ t, index }) => {
            // Si la categoria que proposa la IA no és a la llista oficial, el
            // navegador seleccionava la primera opció sense dir res i el
            // moviment s'acabava desant com a "Menjar i supermercat". Ara es
            // marca explícitament perquè es vegi que cal revisar-la.
            const known = categoriesList.some(c => c.nom === t.categoria);

            const options = categoriesList.map(c => {
                const isSelected = known && t.categoria === c.nom;
                return `<option value="${escapeHtml(c.nom)}" ${isSelected ? 'selected' : ''}>${escapeHtml(c.nom)}</option>`;
            }).join('');

            const unknownOption = known
                ? ''
                : `<option value="" selected>— Tria una categoria —</option>`;

            // Un moviment descartat no s'ha de revisar: ni cal categoria ni ha
            // de cridar l'atenció com si li faltés alguna cosa.
            const needsReview = t.inclos && !known;

            const type = typeOf(t);
            const style = TYPES[type];

            return `
            <tr data-index="${index}" class="${needsReview ? 'row-needs-review' : ''}"
                style="${t.inclos ? '' : 'opacity: 0.45;'}">
                <td>
                    <input type="checkbox" name="inclos" ${t.inclos ? 'checked' : ''}
                           title="Desmarca'l per no importar aquest moviment">
                </td>
                <td>${escapeHtml(t.data)}</td>
                <td>
                    <select class="input input-sm" name="type"
                            title="El CSV el dedueix del signe de l'import. Si el banc el porta al revés, corregeix-lo aquí.">
                        <option value="EXPENSE" ${type === 'EXPENSE' ? 'selected' : ''}>Despesa</option>
                        <option value="INCOME" ${type === 'INCOME' ? 'selected' : ''}>Ingrés</option>
                    </select>
                </td>
                <td><input type="text" class="input input-sm w-full" value="${escapeHtml(t.empresa || '')}" name="empresa"></td>
                <td>
                    <select class="input input-sm w-full" name="categoria" ${needsReview ? 'required' : ''}>
                        ${unknownOption}${options}
                    </select>
                </td>
                <td class="text-right ${style.classe}" style="white-space: nowrap;">
                    ${style.signe}${formatCurrency(t.cost)}
                </td>
            </tr>
            `;
        }).join('');

        document.getElementById('review-empty').classList.toggle('hidden', rows.length > 0);
        updateSummary();
    }

    function updateSummary() {
        const chosen = currentReviewData.filter(t => t.inclos);
        const hidden = currentReviewData.length - visibleRows().length;

        // Els dos costats van separats: sumar-los en una sola xifra restaria
        // els ingressos de les despeses i no voldria dir res.
        const sumOf = (type) => chosen
            .filter(t => typeOf(t) === type)
            .reduce((sum, t) => sum + (Number.parseFloat(t.cost) || 0), 0);

        const spent = sumOf('EXPENSE');
        const earned = sumOf('INCOME');

        document.getElementById('review-summary').textContent =
            `${chosen.length} de ${currentReviewData.length} moviments seleccionats`
            + ` · −${formatCurrency(spent)} de despesa`
            + (earned > 0 ? ` · +${formatCurrency(earned)} d'ingrés` : '')
            + (hidden > 0 ? ` · ${hidden} amagats per la cerca` : '');

        confirmBtn.textContent = chosen.length === currentReviewData.length
            ? 'Confirmar tot'
            : `Confirmar ${chosen.length}`;
        confirmBtn.disabled = chosen.length === 0;
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
        if (event.target.name !== 'inclos') return;

        const row = event.target.closest('tr[data-index]');
        if (!row) return;

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
        currentReviewData.forEach(t => { t.inclos = true; });
        renderReviewTable();
    });

    document.getElementById('select-none').addEventListener('click', () => {
        currentReviewData.forEach(t => { t.inclos = false; });
        renderReviewTable();
    });

    confirmBtn.addEventListener('click', async () => {
        // Les dades surten de currentReviewData i no del DOM: amb la cerca
        // activa, la taula només ensenya una part i llegir-ne les files
        // n'importaria una part.
        const confirmedData = currentReviewData
            .filter(t => t.inclos)
            // `inclos` és de la pantalla, no del model: el backend no l'espera.
            .map(({ inclos, ...transaction }) => transaction);

        if (confirmedData.length === 0) {
            statusDiv.textContent = '⚠️ No hi ha cap moviment seleccionat.';
            statusDiv.classList.remove('hidden');
            return;
        }

        // Només es valida el que s'importa: un moviment descartat pot quedar
        // sense categoria i no ha de bloquejar la resta.
        const missingCategory = confirmedData.some(t => !t.categoria);
        if (missingCategory) {
            const search = document.getElementById('review-search');
            if (search.value) {
                // Si la cerca amagava la fila incompleta, l'avís semblava fals.
                search.value = '';
                renderReviewTable();
            }
            statusDiv.textContent = '⚠️ Hi ha moviments sense categoria. Revisa les files marcades o desmarca\'ls.';
            statusDiv.classList.remove('hidden');
            return;
        }

        confirmBtn.disabled = true;
        statusDiv.textContent = 'Guardant...';

        try {
            const discarded = currentReviewData.length - confirmedData.length;
            const result = await confirmTransactions(confirmedData);
            // Els descartats es diuen explícitament: si no, veure menys
            // moviments dels que tenia el fitxer sembla que se n'hagin perdut.
            statusDiv.textContent = `✅ ${result.message || 'Transaccions guardades correctament!'}`
                + (discarded > 0 ? ` ${discarded} moviments descartats.` : '');
            reviewSection.classList.add('hidden');
            currentReviewData = [];
            reviewBody.innerHTML = '';
            document.getElementById('review-search').value = '';
            fileInput.value = '';
            uploadBtn.disabled = true;
            setTimeout(() => {
                statusDiv.classList.add('hidden');
            }, 4000);
        } catch (error) {
            statusDiv.textContent = `❌ Error guardant: ${error.message}`;
        } finally {
            // Es rehabilita sempre: si fallava, el botó quedava bloquejat.
            confirmBtn.disabled = false;
        }
    });

    cancelBtn.addEventListener('click', () => {
        reviewSection.classList.add('hidden');
        currentReviewData = [];
        reviewBody.innerHTML = '';
        document.getElementById('review-search').value = '';
        statusDiv.textContent = 'Operació cancel·lada.';
        statusDiv.classList.remove('hidden');
        confirmBtn.disabled = false;
        uploadBtn.disabled = !fileInput.files.length;
    });

    // Arrossegar i deixar anar: la zona ho anunciava però no estava implementat.
    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, (e) => {
            e.preventDefault();
            e.stopPropagation();
        });
    });

    ['dragenter', 'dragover'].forEach(eventName => {
        dropZone.addEventListener(eventName, () => dropZone.classList.add('drag-over'));
    });

    ['dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, () => dropZone.classList.remove('drag-over'));
    });

    dropZone.addEventListener('drop', (e) => {
        const file = e.dataTransfer?.files?.[0];
        if (!file) return;

        if (!file.name.toLowerCase().endsWith('.csv')) {
            statusDiv.textContent = '❌ Només s\'accepten fitxers CSV.';
            statusDiv.classList.remove('hidden');
            return;
        }

        fileInput.files = e.dataTransfer.files;
        fileInput.dispatchEvent(new Event('change'));
    });
}
