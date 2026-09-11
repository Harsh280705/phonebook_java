package com.phonebook;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class MongoIndexConfig {
    @Bean
    public ApplicationRunner ensureIndexes(MongoTemplate mongoTemplate) {
        return args -> {
            try { mongoTemplate.indexOps("users").ensureIndex(new Index().on("username", Sort.Direction.ASC).unique()); } catch (Exception ignored) {}
            try { mongoTemplate.indexOps("users").ensureIndex(new Index().on("email", Sort.Direction.ASC).unique()); } catch (Exception ignored) {}
            try { mongoTemplate.indexOps("sessions").ensureIndex(new Index().on("tokenHash", Sort.Direction.ASC).unique()); } catch (Exception ignored) {}
            try { mongoTemplate.indexOps("contacts").ensureIndex(new Index().on("userId", Sort.Direction.ASC)); } catch (Exception ignored) {}
            try { mongoTemplate.indexOps("contacts").ensureIndex(new Index().on("phoneNumber", Sort.Direction.ASC).unique()); } catch (Exception ignored) {}
            try { mongoTemplate.indexOps("contacts").ensureIndex(new Index().on("email", Sort.Direction.ASC).unique().sparse()); } catch (Exception ignored) {}
            try { mongoTemplate.indexOps("tags").ensureIndex(new Index().on("userId", Sort.Direction.ASC)); } catch (Exception ignored) {}
            try { mongoTemplate.indexOps("tags").ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("lowerName", Sort.Direction.ASC).unique()); } catch (Exception ignored) {}
        };
    }
}
