package com.qwerys.qwerys_backend.history;

import com.qwerys.qwerys_backend.model.QueryHistoryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface QueryHistoryRepository extends JpaRepository<QueryHistoryEntry, Long> {

    Page<QueryHistoryEntry> findByUserIdOrderByAnalyzedAtDesc(Long userId, Pageable pageable);

    Optional<QueryHistoryEntry> findByIdAndUserId(Long id, Long userId);

    List<QueryHistoryEntry> findByUserIdAndFavoriteTrueOrderByAnalyzedAtDesc(Long userId);

    List<QueryHistoryEntry> findByUserIdAndQueryContainingIgnoreCaseOrderByAnalyzedAtDesc(
            Long userId, String keyword);

    List<QueryHistoryEntry> findByUserIdAndValidTrueOrderByAnalyzedAtDesc(Long userId);

    List<QueryHistoryEntry> findByUserIdAndValidFalseOrderByAnalyzedAtDesc(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    void deleteByUserId(Long userId);
}
