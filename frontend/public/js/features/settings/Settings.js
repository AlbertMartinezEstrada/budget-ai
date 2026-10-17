import { getSettings, updateSettings, setTheme, setCurrency, setLanguage, escapeHtml } from '../../api.js';
import { t, LANGUAGES, getLanguage } from '../../i18n.js';

export async function initSettings(container) {
    container.innerHTML = `
        <div class="page-header mb-6">
            <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">${t('nav.settings')}</h2>
            <p class="text-slate-500 dark:text-slate-400 text-sm mt-1">${t('settings.subtitle')}</p>
        </div>
        <div id="settings-loading" class="text-center py-8 text-slate-500 dark:text-slate-400">${t('common.loading')}</div>
        <div id="settings-content" class="grid grid-cols-1 lg:grid-cols-2 gap-6" style="display: none;">
            <!-- Perfil -->
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border">
                <div class="flex items-center gap-3 mb-4">
                    <span class="material-symbols-outlined text-primary">person</span>
                    <h3 class="text-lg font-semibold">${t('settings.profile')}</h3>
                </div>
                <div class="space-y-4">
                    <div>
                        <label class="block text-sm font-medium text-slate-700 mb-1" for="setting-name">${t('settings.name')}</label>
                        <input type="text" id="setting-name" class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium text-slate-700 mb-1" for="setting-email">${t('settings.email')}</label>
                        <input type="email" id="setting-email" class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <button id="save-profile-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 text-sm">
                        ${t('settings.save-profile')}
                    </button>
                </div>
            </div>

            <!-- Idioma -->
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border">
                <div class="flex items-center gap-3 mb-4">
                    <span class="material-symbols-outlined text-primary">translate</span>
                    <h3 class="text-lg font-semibold">${t('settings.language')}</h3>
                </div>
                <div>
                    <label class="block text-sm font-medium text-slate-700 mb-1" for="setting-language">${t('settings.language')}</label>
                    <!-- Cada idioma, amb el seu nom en aquell idioma: qui no entén
                         l'actual ha de poder trobar el seu. -->
                    <select id="setting-language" class="w-full px-3 py-2 border rounded-lg">
                        ${LANGUAGES.map(language => `<option value="${language.code}" lang="${language.code}">${escapeHtml(language.name)}</option>`).join('')}
                    </select>
                    <p class="text-slate-500 dark:text-slate-400 text-sm mt-2">${t('settings.language-hint')}</p>
                </div>
            </div>

            <!-- Moneda -->
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border">
                <div class="flex items-center gap-3 mb-4">
                    <span class="material-symbols-outlined text-primary">attach_money</span>
                    <h3 class="text-lg font-semibold">${t('settings.currency')}</h3>
                </div>
                <div>
                    <label class="block text-sm font-medium text-slate-700 mb-1" for="setting-currency">${t('settings.default-currency')}</label>
                    <select id="setting-currency" class="w-full px-3 py-2 border rounded-lg">
                        <option value="EUR">${t('settings.currency-eur')}</option>
                        <option value="USD">${t('settings.currency-usd')}</option>
                        <option value="GBP">${t('settings.currency-gbp')}</option>
                    </select>
                </div>
            </div>

            <!-- Tema -->
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border">
                <div class="flex items-center gap-3 mb-4">
                    <span class="material-symbols-outlined text-primary">palette</span>
                    <h3 class="text-lg font-semibold">${t('settings.appearance')}</h3>
                </div>
                <div>
                    <span class="block text-sm font-medium text-slate-700 mb-2">${t('settings.theme')}</span>
                    <div class="flex gap-4">
                        <label class="flex items-center gap-2 cursor-pointer">
                            <input type="radio" name="theme" value="light" class="text-primary">
                            <span class="text-sm">${t('settings.theme-light')}</span>
                        </label>
                        <label class="flex items-center gap-2 cursor-pointer">
                            <input type="radio" name="theme" value="dark" class="text-primary">
                            <span class="text-sm">${t('settings.theme-dark')}</span>
                        </label>
                        <label class="flex items-center gap-2 cursor-pointer">
                            <input type="radio" name="theme" value="system" class="text-primary">
                            <span class="text-sm">${t('settings.theme-system')}</span>
                        </label>
                    </div>
                </div>
            </div>

            <!-- Notificacions -->
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border">
                <div class="flex items-center gap-3 mb-4">
                    <span class="material-symbols-outlined text-primary">notifications</span>
                    <h3 class="text-lg font-semibold">${t('settings.notifications')}</h3>
                </div>
                <div class="space-y-3">
                    <label class="flex items-center justify-between cursor-pointer">
                        <span class="text-sm">${t('settings.notify-expenses')}</span>
                        <input type="checkbox" id="notif-expenses" class="w-5 h-5 text-primary rounded">
                    </label>
                    <label class="flex items-center justify-between cursor-pointer">
                        <span class="text-sm">${t('settings.notify-budget')}</span>
                        <input type="checkbox" id="notif-budget" class="w-5 h-5 text-primary rounded">
                    </label>
                    <label class="flex items-center justify-between cursor-pointer">
                        <span class="text-sm">${t('settings.notify-monthly')}</span>
                        <input type="checkbox" id="notif-monthly" class="w-5 h-5 text-primary rounded">
                    </label>
                </div>
                <button id="save-notifications-btn" class="mt-4 bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 text-sm">
                    ${t('settings.save-notifications')}
                </button>
            </div>

            <!-- Desar-ho tot -->
            <div class="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border lg:col-span-2">
                <button id="save-all-btn" class="bg-green-700 text-white px-6 py-3 rounded-lg hover:bg-green-800 font-medium">
                    ${t('settings.save-all')}
                </button>
                <span id="save-status" class="ml-4 text-sm text-slate-500 dark:text-slate-400" role="status"></span>
            </div>
        </div>
    `;

    loadSettings();

    document.getElementById('save-all-btn').addEventListener('click', saveAllSettings);
    document.getElementById('save-profile-btn').addEventListener('click', saveProfile);
    document.getElementById('save-notifications-btn').addEventListener('click', saveNotifications);
    document.getElementById('setting-language').addEventListener('change', saveLanguage);
    document.getElementById('setting-currency').addEventListener('change', () => saveAllSettings());
    document.querySelectorAll('input[name="theme"]').forEach(radio => {
        radio.addEventListener('change', () => saveAllSettings());
    });
}

async function loadSettings() {
    try {
        const settings = await getSettings();

        document.getElementById('setting-name').value = settings.userName || '';
        document.getElementById('setting-email').value = settings.userEmail || '';
        document.getElementById('setting-currency').value = settings.currency || 'EUR';
        // Sense idioma desat, el que s'està fent servir (el del navegador).
        document.getElementById('setting-language').value = getLanguage();

        document.querySelectorAll('input[name="theme"]').forEach(radio => {
            radio.checked = radio.value === (settings.theme || 'light');
        });

        document.getElementById('notif-expenses').checked = settings.notificationsExpenses !== false;
        document.getElementById('notif-budget').checked = settings.notificationsBudget !== false;
        document.getElementById('notif-monthly').checked = settings.notificationsMonthly === true;

        document.getElementById('settings-loading').style.display = 'none';
        document.getElementById('settings-content').style.display = 'grid';
    } catch (error) {
        console.error('Error carregant la configuració:', error);
        document.getElementById('settings-loading').textContent = t('settings.load-failed');
    }
}

/** Mostra com ha anat desar i, si ha anat bé, ho esborra al cap de dos segons. */
async function saveWithStatus(data, savedMessageKey) {
    const statusElement = document.getElementById('save-status');
    statusElement.textContent = t('common.saving');
    try {
        await updateSettings(data);
        statusElement.textContent = t(savedMessageKey);
        setTimeout(() => statusElement.textContent = '', 2000);
        return true;
    } catch (error) {
        statusElement.textContent = t('settings.save-failed', { message: error.message });
        console.error(error);
        return false;
    }
}

/**
 * L'idioma es desa als ajustos —així el PC i el mòbil fan servir el mateix— i
 * després es canvia: canviar-lo torna a pintar aquesta vista ja traduïda.
 */
async function saveLanguage(event) {
    const language = event.target.value;
    if (await saveWithStatus({ language }, 'settings.saved')) {
        await setLanguage(language);
    }
}

async function saveAllSettings() {
    const theme = document.querySelector('input[name="theme"]:checked')?.value || 'light';
    const currency = document.getElementById('setting-currency').value;

    setTheme(theme);
    setCurrency(currency);

    await saveWithStatus({
        userName: document.getElementById('setting-name').value,
        userEmail: document.getElementById('setting-email').value,
        currency,
        theme,
        notificationsExpenses: document.getElementById('notif-expenses').checked,
        notificationsBudget: document.getElementById('notif-budget').checked,
        notificationsMonthly: document.getElementById('notif-monthly').checked
    }, 'settings.saved');
}

async function saveProfile() {
    await saveWithStatus({
        userName: document.getElementById('setting-name').value,
        userEmail: document.getElementById('setting-email').value
    }, 'settings.profile-saved');
}

async function saveNotifications() {
    await saveWithStatus({
        notificationsExpenses: document.getElementById('notif-expenses').checked,
        notificationsBudget: document.getElementById('notif-budget').checked,
        notificationsMonthly: document.getElementById('notif-monthly').checked
    }, 'settings.notifications-saved');
}
