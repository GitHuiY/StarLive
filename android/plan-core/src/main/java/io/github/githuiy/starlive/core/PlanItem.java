package io.github.githuiy.starlive.core;

import java.util.Objects;

public final class PlanItem {
    public final String id;
    public final String title;
    public final String category;
    public final boolean done;

    public PlanItem(String id, String title, String category, boolean done) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = Objects.requireNonNull(title, "title");
        this.category = Objects.requireNonNull(category, "category");
        this.done = done;
    }
}
