package com.phonebook.repo;

import com.phonebook.model.Contact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ContactRepository extends MongoRepository<Contact, Long> {
    Optional<Contact> findByIdAndUserId(Long id, Long userId);
    List<Contact> findByUserIdOrderByNameAsc(Long userId);
    long countByUserId(Long userId);
    long count();
    boolean existsByPhoneNumber(String phoneNumber);
    boolean existsByEmail(String email);
    Optional<Contact> findByPhoneNumber(String phoneNumber);
    Optional<Contact> findByEmail(String email);
}
