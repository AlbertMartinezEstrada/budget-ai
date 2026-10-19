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

        /** El bloc de primer nivell d'on penja una categoria; ella mateixa si ho és. */
        public Category rootOf(Long categoryId) {
            Category current = byId.get(categoryId);
            Set<Long> visited = new HashSet<>();
            while (current != null && current.getParentId() != null
                    && byId.containsKey(current.getParentId()) && visited.add(current.getId())) {
                current = byId.get(current.getParentId());
            }
            return current;
        }

        /**
         * A quina secció del pressupost va una categoria: la del bloc d'on penja.
         *
         * @return FIXED, VARIABLE, SAVINGS o INCOME. Sense categoria, VARIABLE:
         *         un moviment sense classificar és despesa.
         */
        public String sectionOf(Long categoryId) {
            Category root = categoryId != null ? rootOf(categoryId) : null;
            return root != null ? sectionOfRoot(root) : Category.VARIABLE;
        }

        /**
         * A quina secció cau un bloc de primer nivell.
         *
         * Són dues preguntes diferents i per això es responen en dos llocs:
         *
         *   ON VIU EL BLOC    el declara el bloc amb el seu tipus_cost
         *   COM ES MESURA     el declara cada fulla amb el seu
         *
         * "Llar" és una despesa fixa —el sostre no es negocia cada mes— però la
         * llum i l'aigua de dins es mesuren pel consum real. Sense separar-ho,
         * dues fulles variables se n'enduien el bloc sencer, lloguer inclòs, a
         * la secció de variables.
         *
         * Un bloc que no declara res es dedueix de les fulles: és fix quan tot
         * el que hi penja ho és. Un bloc que barreja compta com a variable,
         * perquè partir-lo pel mig deixaria el mateix bloc a les dues seccions i
         * no es podria repartir ni en un lloc ni en l'altre.
         */
        public String sectionOfRoot(Category root) {
            String declared = root.getCostType();
            if (Category.FIXED.equals(declared) || Category.VARIABLE.equals(declared)
                    || INCOME.equals(declared) || Category.SAVINGS.equals(declared)) {
                return declared;
            }
            List<Category> leaves = leavesOf(root.getId());
            if (leaves.isEmpty()) return Category.VARIABLE;
            return leaves.stream().allMatch(Category::isFixed) ? Category.FIXED : Category.VARIABLE;
        }
    }

    public Tree loadTree() {
        return treeOf(categoryRepository.findAll());
    }

    /** L'arbre d'unes categories qualssevol: el de la base de dades, o el d'una prova. */
    public static Tree treeOf(List<Category> all) {
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
            throw new InvalidInputException("recurring.category-is-block", "name", name);
        }
        boolean income = tree.isIncome(category.getId());
        if (INCOME.equals(type) && !income) {
            throw new InvalidInputException("recurring.income-needs-income-category", "name", name);
        }
        if (!INCOME.equals(type) && income) {
            throw new InvalidInputException("recurring.expense-in-income-category", "name", name);
        }
    }
}
