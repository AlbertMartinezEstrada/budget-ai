package com.budgetai.backend.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * L'assistent amb Spring AI: de moment, només la comprovació que el model de
 * xat respon.
 *
 * Rep el model amb un ObjectProvider i no directament: sense clau de Gemini
 * no n'hi ha cap (ChatModelWithoutKey l'apaga), i el backend ha d'arrencar
 * igual. Llavors no es crida res i es torna buit.
 */
@Service
public class AssistantService {

    private final ObjectProvider<ChatModel> chatModelProvider;

    public AssistantService(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    /** El que respon el model a una pregunta fixa; buit si no hi ha model configurat. */
    public Optional<String> checkConnection() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return Optional.empty();
        }
        String answer = ChatClient.create(chatModel)
                .prompt()
                .user("Respon només amb la paraula: funciona")
                .call()
                .content();
        return Optional.ofNullable(answer);
    }
}
