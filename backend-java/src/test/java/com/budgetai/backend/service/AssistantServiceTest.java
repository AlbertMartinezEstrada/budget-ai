package com.budgetai.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'assistent, amb un model de xat fals: el CI no ha de cridar Google ni
 * necessitar cap clau.
 */
class AssistantServiceTest {

    /** Un model que sempre respon el mateix i es guarda què li han preguntat. */
    private static final class FixedAnswerModel implements ChatModel {
        private final String answer;
        private final List<Prompt> prompts = new ArrayList<>();

        FixedAnswerModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }
    }

    private static AssistantService assistantWith(ChatModel chatModel) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (chatModel != null) beans.addBean("chatModel", chatModel);
        return new AssistantService(beans.getBeanProvider(ChatModel.class));
    }

    @Test
    @DisplayName("Amb model, torna el que respon i li fa la pregunta de comprovació")
    void answersWithTheModel() {
        FixedAnswerModel model = new FixedAnswerModel("funciona");

        assertThat(assistantWith(model).checkConnection()).contains("funciona");
        assertThat(model.prompts).hasSize(1);
        assertThat(model.prompts.get(0).getContents()).contains("Respon només amb la paraula: funciona");
    }

    @Test
    @DisplayName("Sense model (sense clau de Gemini) torna buit en lloc de petar")
    void emptyWithoutModel() {
        assertThat(assistantWith(null).checkConnection()).isEmpty();
    }
}
