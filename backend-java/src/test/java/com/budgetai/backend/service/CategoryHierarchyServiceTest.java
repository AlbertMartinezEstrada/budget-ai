package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.repository.CategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryHierarchyServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @InjectMocks private CategoryHierarchyService service;

    private Category category(long id, String name, Long parentId) {
        Category c = new Category(name);
        c.setId(id);
        c.setParentId(parentId);
        return c;
    }

    /**
     *  Cotxe (1)
     *    ├─ Assegurança (2)
     *    └─ Manteniment (3)
     *         └─ Rodes (5)      ← tercer nivell
     *  Solta (4)                ← fulla de primer nivell
     */
    private void givenTree() {
        when(categoryRepository.findAll()).thenReturn(List.of(
                category(1, "Cotxe", null),
                category(2, "Assegurança", 1L),
                category(3, "Manteniment", 1L),
                category(4, "Solta", null),
                category(5, "Rodes", 3L)
        ));
    }

    @Test
    @DisplayName("Les arrels són les categories sense grup")
    void roots() {
        givenTree();

        assertThat(service.loadTree().roots())
                .extracting(Category::getName)
                .containsExactly("Cotxe", "Solta");
    }

    @Test
    @DisplayName("Un grup resol totes les seves fulles, a qualsevol profunditat")
    void leavesOfAGroup() {
        givenTree();

        assertThat(service.loadTree().leavesOf(1L))
                .extracting(Category::getName)
                // "Manteniment" no hi surt: té fills, o sigui que no és fulla.
                .containsExactlyInAnyOrder("Assegurança", "Rodes");
    }

    @Test
    @DisplayName("Una fulla es resol a ella mateixa")
    void leafResolvesToItself() {
        givenTree();

        // Així el càlcul d'un pressupost és el mateix tant si apunta a un grup
        // com a una fulla, sense duplicar lògica.
        assertThat(service.loadTree().leavesOf(4L))
                .extracting(Category::getName)
                .containsExactly("Solta");
    }

    @Test
    @DisplayName("Es distingeix un grup d'una fulla")
    void groupDetection() {
        givenTree();
        var tree = service.loadTree();

        assertThat(tree.isGroup(1L)).isTrue();   // Cotxe
        assertThat(tree.isGroup(3L)).isTrue();   // Manteniment, grup intermedi
        assertThat(tree.isGroup(2L)).isFalse();  // Assegurança
        assertThat(tree.isGroup(4L)).isFalse();  // Solta
    }

    @Test
    @DisplayName("Una categoria inexistent no dona fulles ni peta")
    void unknownCategory() {
        givenTree();

        assertThat(service.loadTree().leavesOf(999L)).isEmpty();
        assertThat(service.loadTree().leafIdsOf(999L)).isEmpty();
    }

    @Test
    @DisplayName("Un cicle de parentescs no penja el recorregut")
    void cyclesAreSurvivable() {
        // parent_id manipulat a mà a la base de dades: A és pare de B i B de A.
        when(categoryRepository.findAll()).thenReturn(List.of(
                category(1, "A", 2L),
                category(2, "B", 1L)
        ));

        // Sense protecció, això no acabaria mai.
        assertThat(service.loadTree().leavesOf(1L)).isNotNull();
    }

    @Test
    @DisplayName("Un pare esborrat es tracta com si la categoria fos de primer nivell")
    void danglingParentIsTreatedAsRoot() {
        when(categoryRepository.findAll()).thenReturn(List.of(
                category(1, "Òrfena", 999L)
        ));

        // Si no, la categoria desapareixeria de l'arbre i el seu gasto no
        // sortiria a cap resum.
        assertThat(service.loadTree().roots())
                .extracting(Category::getName)
                .containsExactly("Òrfena");
    }
}
