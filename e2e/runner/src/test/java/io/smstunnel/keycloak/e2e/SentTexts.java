package io.smstunnel.keycloak.e2e;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Sorts.descending;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bson.Document;

/**
 * Reads, never writes, the texts the SMSTunnel test server accepted (collection {@code messages}): the scripted sign-in
 * takes each code from there, as a person would from the phone.
 */
final class SentTexts implements AutoCloseable {

    private final MongoClient client;
    private final MongoCollection<Document> messages;

    SentTexts(String url) {
        this.client = MongoClients.create(url);
        this.messages = client.getDatabase(new ConnectionString(url).getDatabase()).getCollection("messages");
    }

    /** The newest text to this number since {@code since}, waiting up to 20 seconds for it. */
    Document latestTo(String number, Date since) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            Document text = messages.find(and(eq("phoneNumber", number), gte("createdAt", since)))
                    .sort(descending("createdAt")).first();
            if (text != null) {
                return text;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("No text to " + number + " since " + since);
            }
            Thread.sleep(300);
        }
    }

    /** Every text to this number since {@code since}, oldest first. */
    List<Document> allTo(String number, Date since) {
        List<Document> all = new ArrayList<>();
        messages.find(and(eq("phoneNumber", number), gte("createdAt", since))).sort(descending("createdAt")).into(all);
        java.util.Collections.reverse(all);
        return all;
    }

    /** The 6-digit code in a text. */
    static String codeIn(Document text) {
        Matcher m = Pattern.compile("(?<![\\w+])(\\d{6})(?!\\w)").matcher(text.getString("message"));
        if (!m.find()) {
            throw new AssertionError("No code in: " + text.getString("message"));
        }
        return m.group(1);
    }

    @Override
    public void close() {
        client.close();
    }
}
