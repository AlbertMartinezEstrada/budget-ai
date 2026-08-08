package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryHierarchyService hierarchyService;

    public CategoryService(CategoryRepository categoryRepository,
                           TransactionRepository transactionRepository,
                           CategoryHierarchyService hierarchyService) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.hierarchyService = hierarchyService;
    }

    public List<Category> getAll() {
        return categoryRepository.findAll();
    }

    public Optional<Category> getById(Long id) {
        return categoryRepository.findById(id);
    }

    @Transactional
    public Category create(Category category) {
        validateParent(category, null);
        return categoryRepository.save(category);
    }

    /** Actualització parcial: un camp absent no esborra el valor desat. */
    @Transactional
    public Category update(Long id, Category changes) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Categoria no trobada: " + id));

        if (changes.getName() != null) category.setName(changes.getName());
        if (changes.getCostType() != null) category.setCostType(changes.getCostType());

        // El pare sí que es pot buidar explícitament, per treure una categoria
        // d'un grup: per això es distingeix "no enviat" de "enviat a null" amb
        // un valor sentinella negatiu.
        if (changes.getParentId() != null) {
            Long newParent = changes.getParentId() < 0 ? null : changes.getParentId();
            category.setParentId(newParent);
            validateParent(category, id);
        }

        return categoryRepository.save(category);
    }

    /**
     * Impedeix cicles i pares inexistents.
     *
     * Sense això, posar-se un descendent com a pare deixaria una branca
     * desconnectada de l'arrel: no sortiria enlloc i el recorregut de l'arbre
     * podria no acabar mai.
     */
    private void validateParent(Category category, Long ownId) {
        Long parentId = category.getParentId();
        if (parentId == null) return;

        if (ownId != null && parentId.equals(ownId)) {
            throw new IllegalArgumentException("Una categoria no pot ser el seu propi grup");
        }
        if (categoryRepository.findById(parentId).isEmpty()) {
            throw new IllegalArgumentException("El grup indicat no existeix: " + parentId);
        }
        if (ownId != null && hierarchyService.loadTree().leafIdsOf(ownId).contains(parentId)) {
            throw new IllegalArgumentException(
                    "No es pot posar una subcategoria com a grup de la seva pròpia categoria");
        }
    }

    @Transactional
    public void delete(Long id) {
        long used = transactionRepository.countByCategoryId(id);
        if (used > 0) {
            throw new IllegalStateException(
                    "No es pot esborrar la categoria: té " + used + " moviments associats");
        }
        if (!categoryRepository.findByParentId(id).isEmpty()) {
            throw new IllegalStateException(
                    "No es pot esborrar la categoria: té subcategories. Mou-les o esborra-les abans.");
        }
        categoryRepository.deleteById(id);
    }
}
