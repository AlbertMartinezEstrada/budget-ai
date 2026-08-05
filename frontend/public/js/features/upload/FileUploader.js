import { uploadCsv, confirmTransactions, getCategories, formatCurrency, escapeHtml } from '../../api.js';

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
                    <button id="confirm-review" class="btn btn-success btn-sm">Confirmar Tot</button>
                </div>
            </div>
            <div class="table-responsive max-h-96 overflow-y-auto">
                <table class="table table-sm">
                    <thead>
                        <tr>
                            <th>Data</th>
                            <th>Empresa (Editable)</th>
                            <th>Categoria (Seleccionar)</th>
                            <th>Import</th>
                        </tr>
                    </thead>
                    <tbody id="review-body"></tbody>
                </table>
            </div>
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
                currentReviewData = result.data;
                renderReviewTable(currentReviewData);
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

    function renderReviewTable(data) {
        reviewBody.innerHTML = data.map((t, index) => {
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

            return `
            <tr data-index="${index}" class="${known ? '' : 'row-needs-review'}">
                <td>${escapeHtml(t.data)}</td>
                <td><input type="text" class="input input-sm w-full" value="${escapeHtml(t.empresa || '')}" name="empresa"></td>
                <td>
                    <select class="input input-sm w-full" name="categoria" ${known ? '' : 'required'}>
                        ${unknownOption}${options}
                    </select>
                </td>
                <td class="text-right">${formatCurrency(t.cost)}</td>
            </tr>
            `;
        }).join('');
    }

    confirmBtn.addEventListener('click', async () => {
        // Gather data from table
        const rows = reviewBody.querySelectorAll('tr');
        const confirmedData = Array.from(rows).map(row => {
            const index = row.dataset.index;
            const original = currentReviewData[index];
            return {
                ...original,
                empresa: row.querySelector('input[name="empresa"]').value,
                categoria: row.querySelector('select[name="categoria"]').value
            };
        });

        const missingCategory = confirmedData.some(t => !t.categoria);
        if (missingCategory) {
            statusDiv.textContent = '⚠️ Hi ha moviments sense categoria. Revisa les files marcades.';
            statusDiv.classList.remove('hidden');
            return;
        }

        confirmBtn.disabled = true;
        statusDiv.textContent = 'Guardant...';

        try {
            const result = await confirmTransactions(confirmedData);
            statusDiv.textContent = `✅ ${result.message || 'Transaccions guardades correctament!'}`;
            reviewSection.classList.add('hidden');
            currentReviewData = [];
            reviewBody.innerHTML = '';
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
