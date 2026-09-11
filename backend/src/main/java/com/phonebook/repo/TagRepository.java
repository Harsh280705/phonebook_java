package com.phonebook.repo;

import com.phonebook.model.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TagRepository extends MongoRepository<Tag, Long> {
    List<Tag> findByUserIdOrderByNameAsc(Long userId);
    Optional<Tag> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserIdAndLowerName(Long userId, String lowerName);
    Optional<Tag> findByUserIdAndLowerName(Long userId, String lowerName);
    long countByUserIdAndIdIn(Long userId, List<Long> ids);
    List<Tag> findByUserIdAndIdIn(Long userId, List<Long> ids);
    List<Tag> findByIdIn(List<Long> ids);
}
