import { login, escapeHtml } from '../../api.js';

/**
 * Pantalla d'entrada. Ocupa tota la finestra i tapa l'aplicació: mentre no hi
 * hagi sessió, no s'arriba a carregar cap vista ni a demanar cap dada.
 */
export function showLogin({ onSuccess, message } = {}) {
    document.getElementById('login-screen')?.remove();

    const screen = document.createElement('div');
    screen.id = 'login-screen';
    screen.className = 'login-screen';
    screen.innerHTML = `
        <form class="login-card" id="login-form" autocomplete="on">
            <div class="login-brand">
                <i class="ph ph-wallet"></i>
                <span>Budget AI</span>
            </div>
            <p class="login-subtitle">Inicia sessió per continuar</p>

            <label class="login-label" for="login-username">Usuari</label>
            <input class="login-input" id="login-username" name="username"
                   type="text" autocomplete="username" required autofocus>

            <label class="login-label" for="login-password">Contrasenya</label>
            <input class="login-input" id="login-password" name="password"
                   type="password" autocomplete="current-password" required>

            <button class="login-button" type="submit" id="login-submit">Entrar</button>
            <p class="login-error" id="login-error" role="alert">${escapeHtml(message || '')}</p>
        </form>
    `;

    document.body.appendChild(screen);

    const form = screen.querySelector('#login-form');
    const errorEl = screen.querySelector('#login-error');
    const submit = screen.querySelector('#login-submit');

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        errorEl.textContent = '';
        submit.disabled = true;
        submit.textContent = 'Entrant…';

        try {
            const user = await login(
                screen.querySelector('#login-username').value,
                screen.querySelector('#login-password').value
            );
            screen.remove();
            onSuccess?.(user);
        } catch (error) {
            // El backend no diu mai si ha fallat l'usuari o la contrasenya.
            errorEl.textContent = error.message || 'No s\'ha pogut iniciar la sessió';
            screen.querySelector('#login-password').value = '';
            screen.querySelector('#login-password').focus();
        } finally {
            submit.disabled = false;
            submit.textContent = 'Entrar';
        }
    });
}
