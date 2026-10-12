import { getUnmarkedTransfers, joinTransfer, formatCurrency, escapeHtml } from '../../api.js';

/**
 * L'avís de traspassos sense marcar, a Transaccions.
 *
 * Quan s'importen els extractes dels dos comptes abans de marcar el traspàs,
 * el mateix diner queda desat dues vegades: una despesa a Principal i un
 * ingrés a Trade Republic. Tots dos compten a l'anàlisi, com si fossin una
 * despesa i un ingrés de debò. Aquí es llisten i s'ajunten en un sol traspàs;
 * els saldos dels comptes no canvien.
 *
 * Els que l'usuari diu que no són cap traspàs es recorden en aquest navegador:
 * és una comoditat per no tornar-los a veure, no una dada.
 */

const DISMISSED_KEY = 'budget-ai.traspassos-descartats';

function dismissedPairs() {
    try {
        return new Set(JSON.parse(localStorage.getItem(DISMISSED_KEY) || '[]'));
    } catch {
        return new Set();
    }
}

function dismiss(key) {
    try {
        const dismissed = dismissedPairs();
        dismissed.add(key);
        localStorage.setItem(DISMISSED_KEY, JSON.stringify([...dismissed]));
    } catch {
        // Sense emmagatzematge, simplement tornarà a sortir.
    }
}

const pairKey = (pair) => `${pair.sortida.id}-${pair.entrada.id}`;

function formatDate(isoDate) {
    if (!isoDate || !/^\d{4}-\d{2}-\d{2}$/.test(isoDate)) return isoDate || '';
    const [year, month, day] = isoDate.split('-').map(Number);
    return new Date(year, month - 1, day).toLocaleDateString('ca-ES');
}

/**
 * @param container el bloc on va l'avís; s'amaga si no hi ha res a dir
 * @param onJoined  per tornar a carregar la llista després d'ajuntar
 * @returns refresh(), per tornar a buscar-los
 */
export function setUpUnmarkedTransfers(container, { onJoined }) {
    let pairs = [];
    let expanded = false;

    const render = () => {
        const dismissed = dismissedPairs();
        const visible = pairs.filter(pair => !dismissed.has(pairKey(pair)));
        container.classList.toggle('hidden', visible.length === 0);
        if (visible.length === 0) {
            container.innerHTML = '';
            return;
        }

        container.innerHTML = `
            <div class="flex flex-wrap items-center justify-between gap-2">
                <div class="text-sm">
                    <strong>⇄ ${visible.length} ${visible.length === 1 ? 'parell de moviments sembla' : 'parells de moviments semblen'}
                    traspassos entre els teus comptes.</strong>
                    Ara compten a l'anàlisi com a despesa i com a ingrés. Ajuntar-los en deixa un sol traspàs, que només
                    mou els saldos.
                </div>
                <div class="flex gap-2">
                    ${expanded && visible.length > 1
                        ? '<button type="button" class="btn btn-sm btn-primary" data-action="join-all-transfers">Ajuntar-los tots</button>'
                        : ''}
                    <button type="button" class="btn btn-sm btn-outline" data-action="toggle-unmarked-transfers">
                        ${expanded ? 'Amagar' : 'Revisar'}
                    </button>
                </div>
            </div>
            ${expanded ? `
                <div class="mt-3 divide-y divide-slate-200 dark:divide-slate-700">
                    ${visible.map(pair => `
                        <div class="py-2 flex flex-wrap items-center justify-between gap-2">
                            <div class="min-w-0 text-sm">
                                <div class="font-medium">
                                    ${escapeHtml(pair.sortida.compte)} → ${escapeHtml(pair.entrada.compte)} · ${formatCurrency(pair.import)}
                                </div>
                                <div class="text-xs text-slate-500 dark:text-slate-400">
                                    Surt el ${escapeHtml(formatDate(pair.sortida.data))}: «${escapeHtml(pair.sortida.descripcio)}»
                                </div>
                                <div class="text-xs text-slate-500 dark:text-slate-400">
                                    Entra el ${escapeHtml(formatDate(pair.entrada.data))}: «${escapeHtml(pair.entrada.descripcio)}»
                                </div>
                            </div>
                            <div class="flex gap-2 shrink-0">
                                <button type="button" class="btn btn-sm btn-primary" data-action="join-transfer"
                                        data-out="${pair.sortida.id}" data-in="${pair.entrada.id}">Ajuntar</button>
                                <button type="button" class="btn btn-sm btn-outline" data-action="dismiss-transfer"
                                        data-key="${escapeHtml(pairKey(pair))}">No és un traspàs</button>
                            </div>
                        </div>`).join('')}
                </div>
                <p class="text-xs text-slate-500 dark:text-slate-400 mt-2">
                    Es queda el moviment que surt, com a traspàs, i el que entra s'esborra: és el mateix diner. Si va cap
                    a un compte d'estalvi, segueix comptant al pressupost com a estalvi.
                </p>
                <p class="text-error text-sm hidden mt-2" data-role="error"></p>` : ''}
        `;
    };

    const refresh = async () => {
        try {
            pairs = await getUnmarkedTransfers();
        } catch (error) {
            // Sense l'avís, la llista funciona igual.
            console.error('Error buscant traspassos sense marcar:', error);
            pairs = [];
        }
        render();
    };

    const showError = (message) => {
        const error = container.querySelector('[data-role="error"]');
        if (!error) return;
        error.textContent = message;
        error.classList.remove('hidden');
    };

    container.addEventListener('click', async (event) => {
        const button = event.target.closest('button[data-action]');
        if (!button) return;

        if (button.dataset.action === 'toggle-unmarked-transfers') {
            expanded = !expanded;
            render();
            return;
        }
        if (button.dataset.action === 'dismiss-transfer') {
            dismiss(button.dataset.key);
            render();
            return;
        }
        if (button.dataset.action === 'join-transfer' || button.dataset.action === 'join-all-transfers') {
            const dismissed = dismissedPairs();
            const toJoin = button.dataset.action === 'join-transfer'
                ? [{ sortida: { id: Number(button.dataset.out) }, entrada: { id: Number(button.dataset.in) } }]
                : pairs.filter(pair => !dismissed.has(pairKey(pair)));
            container.querySelectorAll('button').forEach(other => { other.disabled = true; });
            try {
                for (const pair of toJoin) {
                    await joinTransfer(pair.sortida.id, pair.entrada.id);
                }
                await refresh();
                await onJoined();
            } catch (error) {
                await refresh();
                showError(error.message || 'No s\'han pogut ajuntar.');
            }
        }
    });

    return refresh;
}
