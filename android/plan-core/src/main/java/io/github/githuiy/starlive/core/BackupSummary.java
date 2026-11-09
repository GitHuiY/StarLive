package io.github.githuiy.starlive.core;

public final class BackupSummary {
    public final int weekCount;
    public final int draftWeekCount;
    public final int itemCount;
    public final int draftItemCount;
    public final int completedItemCount;

    BackupSummary(int weekCount, int draftWeekCount, int itemCount,
            int draftItemCount, int completedItemCount) {
        this.weekCount = weekCount;
        this.draftWeekCount = draftWeekCount;
        this.itemCount = itemCount;
        this.draftItemCount = draftItemCount;
        this.completedItemCount = completedItemCount;
    }
}
