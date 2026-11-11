package io.github.githuiy.starlive.core;

import java.util.Objects;

public final class PlanItem {
    public final String id;
    public final String title;
    public final String category;
    public final boolean done;
    public final String body;

    public PlanItem(String id, String title, String category, boolean done) {
        this(id, title, category, done, "");
    }

    public PlanItem(String id, String title, String category, boolean done, String body) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = Objects.requireNonNull(title, "title");
        this.category = Objects.requireNonNull(category, "category");
        this.done = done;
        this.body = Objects.requireNonNull(body, "body");
    }
}
