package com.phonebook.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "tags")
public class Tag {
    @Id
    private Long id;
    private Long userId;
    private String name;
    private String lowerName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) {
        this.name = name;
        this.lowerName = name != null ? name.toLowerCase() : null;
    }
    public String getLowerName() { return lowerName; }
    public void setLowerName(String lowerName) { this.lowerName = lowerName; }
}
