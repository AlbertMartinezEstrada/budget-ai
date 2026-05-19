import { getSettings, updateSettings, setTheme, setCurrency, appState } from '../../api.js';

export async function initSettings(container) {
    container.innerHTML = `
        <div class="page-header mb-6">
            <h2 class="text-2xl font-bold text-slate-800">Configuración</h2>
            <p class="text-slate-500 text-sm mt-1">Gestiona la configuración de tu aplicación</p>
        </div>
        <div id="settings-loading" class="text-center py-8 text-slate-500">Cargando...</div>
        <div id="settings-content" class="grid grid-cols-1 lg:grid-cols-2 gap-6" style="display: none;">
            <!-- Perfil -->
            <div class="bg-white rounded-xl p-6 shadow-sm border">
                <div class="flex items-center gap-3 mb-4">
                    <span class="material-symbols-outlined text-primary">person</span>
                    <h3 class="text-lg font-semibold">Perfil</h3>
                </div>
                <div class="space-y-4">
                    <div>
                        <label class="block text-sm font-medium text-slate-700 mb-1">Nombre</label>
                        <input type="text" id="setting-name" class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <div>
                        <label class="block text-sm font-medium text-slate-700 mb-1">Email</label>
                        <input type="email" id="setting-email" class="w-full px-3 py-2 border rounded-lg">
                    </div>
                    <button id="save-profile-btn" class="bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 text-sm">
                        Guardar perfil
                    </button>
                </div>
            </div>

            <!-- Moneda -->
            <div class="bg-white rounded-xl p-6 shadow-sm border">
                <div class="flex items-center gap-3 mb-4">
                    <span class="material-symbols-outlined text-primary">attach_money</span>
                    <h3 class="text-lg font-semibold">Moneda</h3>
                </div>
                <div>
                    <label class="block text-sm font-medium text-slate-700 mb-1">Moneda predeterminada</label>
                    <select id="setting-currency" class="w-full px-3 py-2 border rounded-lg">
                        <option value="EUR">EUR (Euro)</option>
                        <option value="USD">USD (Dólar)</option>
                        <option value="GBP">GBP (Libra)</option>
                    </select>
                </div>
            </div>

            <!-- Tema -->
            <div class="bg-white rounded-xl p-6 shadow-sm border">
                <div class="flex items-center gap-3 mb-4">
                    <span class="material-symbols-outlined text-primary">palette</span>
                    <h3 class="text-lg font-semibold">Apariencia</h3>
                </div>
                <div>
                    <label class="block text-sm font-medium text-slate-700 mb-2">Tema</label>
                    <div class="flex gap-4">
                        <label class="flex items-center gap-2 cursor-pointer">
                            <input type="radio" name="theme" value="light" class="text-primary">
                            <span class="text-sm">Claro</span>
                        </label>
                        <label class="flex items-center gap-2 cursor-pointer">
                            <input type="radio" name="theme" value="dark" class="text-primary">
                            <span class="text-sm">Oscuro</span>
                        </label>
                        <label class="flex items-center gap-2 cursor-pointer">
                            <input type="radio" name="theme" value="system" class="text-primary">
                            <span class="text-sm">Sistema</span>
                        </label>
                    </div>
                </div>
            </div>

            <!-- Notificaciones -->
            <div class="bg-white rounded-xl p-6 shadow-sm border">
                <div class="flex items-center gap-3 mb-4">
                    <span class="material-symbols-outlined text-primary">notifications</span>
                    <h3 class="text-lg font-semibold">Notificaciones</h3>
                </div>
                <div class="space-y-3">
                    <label class="flex items-center justify-between cursor-pointer">
                        <span class="text-sm">Notificaciones de gastos</span>
                        <input type="checkbox" id="notif-expenses" class="w-5 h-5 text-primary rounded">
                    </label>
                    <label class="flex items-center justify-between cursor-pointer">
                        <span class="text-sm">Recordatorios de presupuestos</span>
                        <input type="checkbox" id="notif-budget" class="w-5 h-5 text-primary rounded">
                    </label>
                    <label class="flex items-center justify-between cursor-pointer">
                        <span class="text-sm">Resumen mensual</span>
                        <input type="checkbox" id="notif-monthly" class="w-5 h-5 text-primary rounded">
                    </label>
                </div>
                <button id="save-notifications-btn" class="mt-4 bg-primary text-white px-4 py-2 rounded-lg hover:bg-primary/90 text-sm">
                    Guardar notificaciones
                </button>
            </div>

            <!-- Guardar botón principal -->
            <div class="bg-white rounded-xl p-6 shadow-sm border lg:col-span-2">
                <button id="save-all-btn" class="bg-green-600 text-white px-6 py-3 rounded-lg hover:bg-green-700 font-medium">
                    Guardar todos los cambios
                </button>
                <span id="save-status" class="ml-4 text-sm text-slate-500"></span>
            </div>
        </div>
    `;

    loadSettings();

    document.getElementById('save-all-btn').addEventListener('click', saveAllSettings);
    document.getElementById('save-profile-btn').addEventListener('click', saveProfile);
    document.getElementById('save-notifications-btn').addEventListener('click', saveNotifications);
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
        
        document.querySelectorAll('input[name="theme"]').forEach(radio => {
            radio.checked = radio.value === (settings.theme || 'light');
        });
        
        document.getElementById('notif-expenses').checked = settings.notificationsExpenses !== false;
        document.getElementById('notif-budget').checked = settings.notificationsBudget !== false;
        document.getElementById('notif-monthly').checked = settings.notificationsMonthly === true;

        document.getElementById('settings-loading').style.display = 'none';
        document.getElementById('settings-content').style.display = 'grid';
    } catch (error) {
        console.error('Error cargando settings:', error);
        document.getElementById('settings-loading').textContent = 'Error cargando configuración';
    }
}

async function saveAllSettings() {
    const statusEl = document.getElementById('save-status');
    statusEl.textContent = 'Guardando...';
    
    const theme = document.querySelector('input[name="theme"]:checked')?.value || 'light';
    const currency = document.getElementById('setting-currency').value;
    
    setTheme(theme);
    setCurrency(currency);
    
    const data = {
        userName: document.getElementById('setting-name').value,
        userEmail: document.getElementById('setting-email').value,
        currency: currency,
        theme: theme,
        notificationsExpenses: document.getElementById('notif-expenses').checked,
        notificationsBudget: document.getElementById('notif-budget').checked,
        notificationsMonthly: document.getElementById('notif-monthly').checked
    };

    try {
        await updateSettings(data);
        statusEl.textContent = '✓ Guardado';
        setTimeout(() => statusEl.textContent = '', 2000);
    } catch (error) {
        statusEl.textContent = 'Error guardando';
        console.error(error);
    }
}

async function saveProfile() {
    const statusEl = document.getElementById('save-status');
    statusEl.textContent = 'Guardando...';
    
    const data = {
        userName: document.getElementById('setting-name').value,
        userEmail: document.getElementById('setting-email').value
    };

    try {
        await updateSettings(data);
        statusEl.textContent = '✓ Perfil guardado';
        setTimeout(() => statusEl.textContent = '', 2000);
    } catch (error) {
        statusEl.textContent = 'Error guardando';
        console.error(error);
    }
}

async function saveNotifications() {
    const statusEl = document.getElementById('save-status');
    statusEl.textContent = 'Guardando...';
    
    const data = {
        notificationsExpenses: document.getElementById('notif-expenses').checked,
        notificationsBudget: document.getElementById('notif-budget').checked,
        notificationsMonthly: document.getElementById('notif-monthly').checked
    };

    try {
        await updateSettings(data);
        statusEl.textContent = '✓ Notificaciones guardadas';
        setTimeout(() => statusEl.textContent = '', 2000);
    } catch (error) {
        statusEl.textContent = 'Error guardando';
        console.error(error);
    }
}