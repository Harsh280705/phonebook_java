package com.phonebook.web;

import com.phonebook.dto.Dtos;
import com.phonebook.model.Tag;
import com.phonebook.model.User;
import com.phonebook.repo.TagRepository;
import com.phonebook.service.AuthService;
import com.phonebook.service.SequenceService;
import com.phonebook.service.Validators;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TagController {
    private final TagRepository tags;
    private final AuthService auth;
    private final SequenceService sequences;
    private final MongoTemplate mongoTemplate;

    public TagController(TagRepository tags, AuthService auth, SequenceService sequences, MongoTemplate mongoTemplate) {
        this.tags = tags;
        this.auth = auth;
        this.sequences = sequences;
        this.mongoTemplate = mongoTemplate;
    }

    private User requireUser(HttpServletRequest http) {
        return auth.currentUser(http).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required."));
    }

    @GetMapping({"/tags", "/tags/"})
    public List<Dtos.TagResponse> list(HttpServletRequest http) {
        User user = requireUser(http);
        return tags.findByUserIdOrderByNameAsc(user.getId()).stream()
                .map(t -> new Dtos.TagResponse(t.getId(), t.getName())).toList();
    }

    @PostMapping({"/tags", "/tags/"})
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw, HttpServletRequest http) {
        User user = requireUser(http);
        String name = raw.get("name") == null ? null : String.valueOf(raw.get("name"));
        String err = Validators.validateTagName(name);
        if (err != null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, err);
        String cleaned = name.trim();
        if (tags.existsByUserIdAndLowerName(user.getId(), cleaned.toLowerCase())) {
            throw new ApiException(HttpStatus.CONFLICT, "A tag with this name already exists.");
        }
        Tag tag = new Tag();
        tag.setId(sequences.next("tags"));
        tag.setUserId(user.getId());
        tag.setName(cleaned);
        try {
            tags.save(tag);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.CONFLICT, "A tag with this name already exists.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new Dtos.TagResponse(tag.getId(), tag.getName()));
    }

    @PutMapping({"/tags/{id}"})
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> raw, HttpServletRequest http) {
        User user = requireUser(http);
        Tag tag = tags.findByIdAndUserId(id, user.getId()).orElse(null);
        if (tag == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Dtos.ErrorResponse("Tag not found."));
        }
        String name = raw.get("name") == null ? null : String.valueOf(raw.get("name"));
        String err = Validators.validateTagName(name);
        if (err != null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, err);
        String cleaned = name.trim();
        var existing = tags.findByUserIdAndLowerName(user.getId(), cleaned.toLowerCase()).orElse(null);
        if (existing != null && !existing.getId().equals(tag.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "A tag with this name already exists.");
        }
        tag.setName(cleaned);
        try {
            tags.save(tag);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.CONFLICT, "A tag with this name already exists.");
        }
        return ResponseEntity.ok(new Dtos.TagResponse(tag.getId(), tag.getName()));
    }

    @DeleteMapping({"/tags/{id}"})
    public ResponseEntity<?> delete(@PathVariable long id, HttpServletRequest http) {
        User user = requireUser(http);
        Tag tag = tags.findByIdAndUserId(id, user.getId()).orElse(null);
        if (tag == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Dtos.ErrorResponse("Tag not found."));
        }
        tags.delete(tag);
        // remove tag from all contacts of this user
        var contacts = mongoTemplate.find(new Query(Criteria.where("userId").is(user.getId()).and("tagIds").in(id)),
                com.phonebook.model.Contact.class);
        for (var c : contacts) {
            c.getTagIds().removeIf(t -> t == id);
            mongoTemplate.save(c);
        }
        return ResponseEntity.ok(new Dtos.MessageResponse("Tag deleted successfully."));
    }
}
