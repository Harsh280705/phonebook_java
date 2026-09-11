package com.phonebook;

import com.phonebook.model.Contact;
import com.phonebook.repo.ContactRepository;
import com.phonebook.repo.UserRepository;
import com.phonebook.service.SequenceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PopulateConfig {
    private static final String[] FIRST = {"Aarav","Ananya","Arjun","Diya","Harsh","Ishaan","Kavya","Krishna","Meera","Nikhil","Priya","Rahul","Raj","Riya","Rohan","Sanya","Vikram","Zoya","Amit","Neha"};
    private static final String[] LAST = {"Sharma","Verma","Patel","Iyer","Khan","Gupta","Mehta","Nair","Singh","Joshi","Kulkarni","Reddy","Chopra","Malhotra","Desai","Pillai","Agarwal","Bose","Chavan","Pawar"};
    private static final String[] STREETS = {"MG Road","Linking Road","Park Street","Gandhi Nagar","Nehru Avenue","Lake View Road","Station Road","Market Street","Hill View","Green Park"};

    @Bean
    public ApplicationRunner populateRunner(ContactRepository contacts, UserRepository users, SequenceService sequences,
                                            org.springframework.context.ApplicationContext context) {
        return args -> {
            boolean populate = false;
            for (String a : args.getSourceArgs()) {
                if ("populate".equalsIgnoreCase(a)) { populate = true; break; }
            }
            if (!populate) return;
            final int target = 1000;
            long existing = contacts.count();
            long needed = Math.max(target - existing, 0);
            if (needed == 0) {
                System.out.println("Database already contains " + existing + " contacts.");
                System.out.flush();
                int exitCode = org.springframework.boot.SpringApplication.exit(context, () -> 0);
                System.exit(exitCode);
                return;
            }
            var owner = users.findAll().stream().sorted((a, b) -> Long.compare(a.getId(), b.getId())).findFirst().orElse(null);
            Set<String> phones = new HashSet<>(contacts.findAll().stream().map(Contact::getPhoneNumber).toList());
            Set<String> emails = new HashSet<>(contacts.findAll().stream().filter(c -> c.getEmail() != null).map(Contact::getEmail).toList());
            var random = ThreadLocalRandom.current();
            List<Contact> batch = new ArrayList<>();
            int guard = 0;
            while (batch.size() < needed && guard < needed * 20 + 500) {
                guard++;
                String phone = "+1" + (2000000000L + Math.abs(random.nextLong()) % 8000000000L);
                String email = ("user" + Math.abs(random.nextLong()) + "@example.com").toLowerCase();
                if (phones.contains(phone) || emails.contains(email)) continue;
                String name = FIRST[random.nextInt(FIRST.length)] + " " + LAST[random.nextInt(LAST.length)];
                String address = (100 + random.nextInt(900)) + " " + STREETS[random.nextInt(STREETS.length)] + ", Mumbai";
                phones.add(phone);
                emails.add(email);
                Contact c = new Contact();
                c.setId(sequences.next("contacts"));
                c.setUserId(owner != null ? owner.getId() : null);
                c.setName(name);
                c.setPhoneNumber(phone);
                c.setEmail(email);
                c.setAddress(address);
                c.setCreatedAt(Instant.now());
                batch.add(c);
            }
            contacts.saveAll(batch);
            System.out.println("Added " + batch.size() + " contacts. Total contacts: " + target + ".");
            System.out.flush();
            int exitCode = org.springframework.boot.SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        };
    }
}
