package com.budgetai.backend.repository;

import com.budgetai.backend.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByName(String name);

    List<Category> findByParentId(Long parentId);

    /** Categories de primer nivell: grups i fulles sense grup. */
    List<Category> findByParentIdIsNull();

    /** Si en té algun, la categoria és un grup i no pot rebre transaccions. */
    boolean existsByParentId(Long parentId);
}
