import { login, escapeHtml, updateSettings } from '../../api.js';
import { t, LANGUAGES, getLanguage, useLanguage, rememberLanguage, translatePage } from '../../i18n.js';

/**
 * Pantalla d'entrada. Ocupa tota la finestra i tapa l'aplicació: mentre no hi
 * hagi sessió, no s'arriba a carregar cap vista ni a demanar cap dada.
 *
 * Té el seu selector d'idioma: abans d'entrar no es poden llegir els ajustos, i
 * sense ell qui no entén l'idioma del navegador no sabria com entrar.
 */
export function showLogin({ onSuccess, message } = {}, { languageChosen = false } = {}) {
    document.getElementById('login-screen')?.remove();

    const screen = document.createElement('div');
    screen.id = 'login-screen';
    screen.className = 'login-screen';
    screen.innerHTML = `
        <form class="login-card" id="login-form" autocomplete="on">
            <div class="login-brand">
                <span class="material-symbols-outlined">account_balance_wallet</span>
                <span>Budget AI</span>
            </div>
            <p class="login-subtitle">${t('login.subtitle')}</p>

            <label class="login-label" for="login-username">${t('login.username')}</label>
            <input class="login-input" id="login-username" name="username"
                   type="text" autocomplete="username" required autofocus>

            <label class="login-label" for="login-password">${t('login.password')}</label>
            <input class="login-input" id="login-password" name="password"
                   type="password" autocomplete="current-password" required>

            <button class="login-button" type="submit" id="login-submit">${t('login.submit')}</button>
            <p class="login-error" id="login-error" role="alert">${escapeHtml(message || '')}</p>

            <label class="login-label" for="login-language">${t('login.language')}</label>
            <select class="login-input login-language" id="login-language">
                ${LANGUAGES.map(language => `
                    <option value="${language.code}" lang="${language.code}" ${language.code === getLanguage() ? 'selected' : ''}>${escapeHtml(language.name)}</option>
                `).join('')}
            </select>
        </form>
    `;

    document.body.appendChild(screen);

    const form = screen.querySelector('#login-form');
    const errorElement = screen.querySelector('#login-error');
    const submit = screen.querySelector('#login-submit');

    screen.querySelector('#login-language').addEventListener('change', async (event) => {
        try {
            const language = await useLanguage(event.target.value);
            rememberLanguage(language);
            translatePage();
            // El missatge d'abans era en l'altre idioma: es torna a pintar sense.
            showLogin({ onSuccess }, { languageChosen: true });
        } catch (error) {
            errorElement.textContent = error.message;
        }
    });

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        errorElement.textContent = '';
        submit.disabled = true;
        submit.textContent = t('login.submitting');

        try {
            const user = await login(
                screen.querySelector('#login-username').value,
                screen.querySelector('#login-password').value
            );
            // L'idioma triat aquí passa als ajustos: si no, en entrar
            // loadAppState posaria el que hi havia desat.
            if (languageChosen) {
                await updateSettings({ language: getLanguage() })
                    .catch(error => console.warn('No s\'ha pogut desar l\'idioma:', error));
            }
            screen.remove();
            onSuccess?.(user);
        } catch (error) {
            // El backend no diu mai si ha fallat l'usuari o la contrasenya.
            errorElement.textContent = error.message || t('login.failed');
            screen.querySelector('#login-password').value = '';
            screen.querySelector('#login-password').focus();
        } finally {
            submit.disabled = false;
            submit.textContent = t('login.submit');
        }
    });
}
