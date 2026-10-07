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

    /** La secció d'ingressos, tal com la declara el bloc al seu tipus_cost. */
    private static final String INCOME = "INCOME";

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
         * Si una categoria és de la secció d'ingressos: si ho declara el bloc
         * de primer nivell on penja. Allà el pressupost mira el que entra.
         */
        public boolean isIncome(Long categoryId) {
            Category current = byId.get(categoryId);
            // visited talla un possible cicle de parent_id mal informats.
            Set<Long> visited = new HashSet<>();
            while (current != null && current.getParentId() != null
                    && byId.containsKey(current.getParentId()) && visited.add(current.getId())) {
                current = byId.get(current.getParentId());
            }
            return current != null && INCOME.equals(current.getCostType());
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
            childrenByParent.computeIfAbsent(parentId, missingParentId -> new ArrayList<>()).add(category);
        }

        childrenByParent.values().forEach(list -> list.sort(Comparator.comparing(Category::getName)));

        return new Tree(byId, childrenByParent);
    }

    /** Una categoria amb fills és un grup i no pot rebre transaccions. */
    public boolean isGroup(Long categoryId) {
        return categoryId != null && categoryRepository.existsByParentId(categoryId);
    }

    /**
     * Comprova que un recurrent pugui comptar al pressupost a la categoria on va.
     *
     * Un recurrent és la previsió de la seva categoria. En un bloc es
     * comptaria dues vegades (per ell i pels fills), i en processar-lo
     * crearia un moviment en un bloc. Una despesa en una categoria
     * d'ingressos, o al revés, no compta enlloc: el pressupost hi mira l'altre
     * sentit. Abans s'acceptava tot, i el recurrent desapareixia del
     * pressupost sense que res ho digués.
     *
     * @param type EXPENSE o INCOME, el del recurrent
     */
    public void requireRecurringCategory(Category category, String type) {
        Tree tree = loadTree();
        String name = category.getName();
        if (tree.isGroup(category.getId())) {
            throw new IllegalArgumentException("«" + name + "» és un bloc: tria una de les seves subcategories. "
                    + "Un recurrent en un bloc es comptaria dues vegades al pressupost.");
        }
        boolean income = tree.isIncome(category.getId());
        if (INCOME.equals(type) && !income) {
            throw new IllegalArgumentException("Un ingrés recurrent ha d'anar a una categoria d'ingressos: "
                    + "a «" + name + "» el pressupost no el comptaria.");
        }
        if (!INCOME.equals(type) && income) {
            throw new IllegalArgumentException("Una despesa recurrent no pot anar a «" + name + "»: "
                    + "és una categoria d'ingressos, i allà el pressupost només mira el que entra.");
        }
    }
}
