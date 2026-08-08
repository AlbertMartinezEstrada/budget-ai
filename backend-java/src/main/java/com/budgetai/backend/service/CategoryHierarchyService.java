package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Resol l'arbre de categories.
 *
 * Totes les operacions parteixen d'una sola lectura de la taula i treballen
 * en memòria. La taula té desenes de files, no milers: recórrer l'arbre amb
 * una consulta per nivell seria més codi i més lent.
 */
@Service
public class CategoryHierarchyService {

    private final CategoryRepository categoryRepository;

    public CategoryHierarchyService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /** Vista de l'arbre en memòria, per no repetir consultes dins d'un càlcul. */
    public record Tree(Map<Long, Category> byId, Map<Long, List<Category>> childrenByParent) {

        public boolean isGroup(Long categoryId) {
            return !childrenByParent.getOrDefault(categoryId, List.of()).isEmpty();
        }

        public List<Category> roots() {
            return childrenByParent.getOrDefault(null, List.of());
        }

        /**
         * Fulles que pengen d'una categoria, a qualsevol profunditat.
         *
         * Si la categoria ja és una fulla, es retorna ella mateixa: així el
         * càlcul d'un pressupost és el mateix tant si apunta a un grup com a
         * una fulla, i no cal duplicar la lògica.
         */
        public List<Category> leavesOf(Long categoryId) {
            Category root = byId.get(categoryId);
            if (root == null) return List.of();

            List<Category> leaves = new ArrayList<>();
            Deque<Category> pending = new ArrayDeque<>(List.of(root));
            // Un parent_id mal informat podria formar un cicle i penjar el
            // servidor; visited talla el recorregut.
            Set<Long> visited = new HashSet<>();

            while (!pending.isEmpty()) {
                Category current = pending.pop();
                if (!visited.add(current.getId())) continue;

                List<Category> children = childrenByParent.getOrDefault(current.getId(), List.of());
                if (children.isEmpty()) {
                    leaves.add(current);
                } else {
                    pending.addAll(children);
                }
            }
            return leaves;
        }

        public Set<Long> leafIdsOf(Long categoryId) {
            Set<Long> ids = new HashSet<>();
            for (Category leaf : leavesOf(categoryId)) ids.add(leaf.getId());
            return ids;
        }
    }

    public Tree loadTree() {
        List<Category> all = categoryRepository.findAll();

        Map<Long, Category> byId = new HashMap<>();
        Map<Long, List<Category>> childrenByParent = new HashMap<>();

        for (Category category : all) {
            byId.put(category.getId(), category);
        }
        for (Category category : all) {
            // Un parent_id que apunti a una categoria esborrada es tracta com
            // si fos de primer nivell, en comptes de desaparèixer de l'arbre.
            Long parentId = category.getParentId() != null && byId.containsKey(category.getParentId())
                    ? category.getParentId()
                    : null;
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(category);
        }

        childrenByParent.values().forEach(list -> list.sort(Comparator.comparing(Category::getName)));

        return new Tree(byId, childrenByParent);
    }

    /** Una categoria amb fills és un grup i no pot rebre transaccions. */
    public boolean isGroup(Long categoryId) {
        return categoryId != null && categoryRepository.existsByParentId(categoryId);
    }
}
