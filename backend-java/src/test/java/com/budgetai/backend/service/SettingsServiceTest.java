package com.budgetai.backend.service;

import com.budgetai.backend.model.Settings;
import com.budgetai.backend.repository.SettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * L'idioma de la interfície es desa als ajustos, com el tema.
 *
 * Buit vol dir el del navegador, i desar una altra cosa (el nom o el tema) no
 * l'ha de tocar: és una actualització parcial.
 */
@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock private SettingsRepository settingsRepository;

    private SettingsService service;
    private Settings stored;

    @BeforeEach
    void setUp() {
        stored = new Settings();
        stored.setUserName("Persona de prova");
        lenient().when(settingsRepository.findFirstBy()).thenReturn(Optional.of(stored));
        lenient().when(settingsRepository.save(any(Settings.class))).thenAnswer(call -> call.getArgument(0));
        service = new SettingsService(settingsRepository);
    }

    /** El cos d'un PUT tal com el construeix Jackson: el que no s'envia queda a null. */
    private static Settings changes(String json) throws Exception {
        return new ObjectMapper().readValue(json, Settings.class);
    }

    @Test
    @DisplayName("Una instal·lació que ja existia no té idioma: es fa servir el del navegador")
    void storedSettingsHaveNoLanguageUntilChosen() {
        assertThat(service.getSettings().getLanguage()).isNull();
    }

    @Test
    @DisplayName("Triar un idioma el desa")
    void savesTheChosenLanguage() throws Exception {
        assertThat(service.updateSettings(changes("{\"language\": \"pt\"}")).getLanguage()).isEqualTo("pt");
        assertThat(stored.getLanguage()).isEqualTo("pt");
    }

    @Test
    @DisplayName("Desar només l'idioma (o el perfil) no torna el tema fosc a clar")
    void partialUpdatesKeepTheThemeAndNotifications() throws Exception {
        // Amb theme = "light" i les notificacions inicialitzades als camps de
        // l'entitat, qualsevol PUT parcial portava aquests valors i els desava.
        stored.setTheme("dark");
        stored.setCurrency("GBP");
        stored.setNotificationsMonthly(true);

        service.updateSettings(changes("{\"language\": \"es\"}"));
        service.updateSettings(changes("{\"userName\": \"Un altre nom\"}"));

        assertThat(stored.getTheme()).isEqualTo("dark");
        assertThat(stored.getCurrency()).isEqualTo("GBP");
        assertThat(stored.getNotificationsMonthly()).isTrue();
    }

    @Test
    @DisplayName("Desar el perfil no esborra l'idioma triat")
    void otherChangesKeepTheLanguage() throws Exception {
        stored.setLanguage("en");

        Settings result = service.updateSettings(changes("{\"userName\": \"Un altre nom\"}"));

        assertThat(result.getLanguage()).isEqualTo("en");
        assertThat(result.getUserName()).isEqualTo("Un altre nom");
    }

    @Test
    @DisplayName("Un idioma que el frontend no té es rebutja i no es desa res")
    void rejectsUnknownLanguages() throws Exception {
        Settings changes = changes("{\"language\": \"fr\"}");

        assertThatThrownBy(() -> service.updateSettings(changes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fr")
                .hasMessageContaining("ca, es, pt o en");
        verify(settingsRepository, never()).save(any(Settings.class));
        assertThat(stored.getLanguage()).isNull();
    }

    @Test
    @DisplayName("Els idiomes acceptats són els quatre que té el frontend")
    void acceptsTheFourFrontendLanguages() {
        assertThat(SettingsService.LANGUAGES).containsExactlyInAnyOrder("ca", "es", "pt", "en");
    }
}
