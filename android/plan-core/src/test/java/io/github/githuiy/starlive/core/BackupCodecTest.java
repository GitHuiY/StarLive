package io.github.githuiy.starlive.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BackupCodecTest {
    private static final String WEEK = "2026-11-02";
    private final PlanEngine engine = new PlanEngine(LocalDate.of(2026, 11, 8));

    private AppState sample() {
        AppState state = engine.addDraftItem(AppState.empty(), WEEK, 0, "和 AI 交流思考", "交流思考");
        state = engine.confirmDraft(state, WEEK);
        String id = state.weeks.get(WEEK).days.get(0).get(0).id;
        state = engine.toggleCompletion(state, WEEK, 0, id);
        state = engine.addDraftItem(state, "2026-11-09", 4, "出门探景", "探索");
        return engine.updateSettings(state, new ReminderSettings(60, 60, 180, false, true, false));
    }

    private JsonObject json(AppState state) {
        return JsonParser.parseString(BackupCodec.encode(state)).getAsJsonObject();
    }

    private JsonArray firstDay(JsonObject root, String section) {
        return root.getAsJsonObject("data").getAsJsonObject(section).getAsJsonObject(WEEK)
                .getAsJsonArray("days").get(0).getAsJsonArray();
    }

    @Test public void roundTripPreservesAllPersonalStateWithoutDemoOrUsage() {
        AppState original = sample();
        String encoded = BackupCodec.encode(original);
        BackupCodec.Prepared preview = BackupCodec.decode(encoded);
        assertEquals(1, preview.summary.weekCount);
        assertEquals(2, preview.summary.draftWeekCount);
        assertEquals(1, preview.summary.itemCount);
        assertEquals(2, preview.summary.draftItemCount);
        assertEquals(1, preview.summary.completedItemCount);
        assertTrue(preview.data.weeks.get(WEEK).days.get(0).get(0).done);
        assertFalse(preview.data.settings.continuousEnabled);
        assertTrue(preview.data.settings.perAppDailyEnabled);
        assertFalse(preview.data.settings.totalDailyEnabled);
        assertEquals(30, preview.data.settings.snoozeMinutes);
        assertEquals(120, preview.data.settings.graceSeconds);
        assertEquals(encoded, BackupCodec.encode(preview.data));
        assertFalse(encoded.contains("demo"));
        assertFalse(encoded.contains("usage"));
    }

    @Test public void previewNeverMutatesTheCurrentStateOrAdoptsImport() {
        AppState current = sample();
        String original = BackupCodec.encode(current);
        BackupCodec.Prepared preview = BackupCodec.decode(BackupCodec.encode(AppState.empty()));
        assertTrue(preview.data.weeks.isEmpty());
        assertEquals(original, BackupCodec.encode(current));
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode("{malformed"));
        assertEquals(original, BackupCodec.encode(current));
    }

    @Test public void unsupportedVersionsAndUnexpectedPrivateFieldsAreRejected() {
        JsonObject root = json(sample());
        root.addProperty("schemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(root.toString()));
        root.addProperty("schemaVersion", 1);
        root.getAsJsonObject("data").add("usage", new JsonObject());
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(root.toString()));
    }

    @Test public void duplicateIdsAreRejectedAcrossDaysButExpectedDraftCopiesAreAllowed() {
        JsonObject root = json(sample());
        JsonArray days = root.getAsJsonObject("data").getAsJsonObject("weeks")
                .getAsJsonObject(WEEK).getAsJsonArray("days");
        days.get(1).getAsJsonArray().add(firstDay(root, "weeks").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(root.toString()));
        assertNotNull(BackupCodec.decode(BackupCodec.encode(sample())));
    }

    @Test public void duplicateIdsInsideDraftsAreRejectedCaseInsensitively() {
        JsonObject root = json(sample());
        JsonObject duplicate = firstDay(root, "drafts").get(0).getAsJsonObject().deepCopy();
        duplicate.addProperty("id", duplicate.get("id").getAsString().toUpperCase(java.util.Locale.ROOT));
        firstDay(root, "drafts").add(duplicate);
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(root.toString()));
    }

    @Test public void validCaseVariantsBecomeOneStableIdentityBeforeDoneMerge() {
        AppState state = sample();
        JsonObject root = json(state);
        JsonObject draft = firstDay(root, "drafts").get(0).getAsJsonObject();
        draft.addProperty("id", draft.get("id").getAsString().toUpperCase(java.util.Locale.ROOT));
        AppState imported = BackupCodec.decode(root.toString()).data;
        assertEquals(imported.weeks.get(WEEK).days.get(0).get(0).id,
                imported.drafts.get(WEEK).days.get(0).get(0).id);
        AppState confirmed = engine.confirmDraft(imported, WEEK);
        assertTrue(confirmed.weeks.get(WEEK).days.get(0).get(0).done);
    }

    @Test public void malformedFieldsMissingDaysAndNonMondayKeysAreRejected() {
        JsonObject wrongType = json(sample());
        firstDay(wrongType, "weeks").get(0).getAsJsonObject().addProperty("done", "false");
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(wrongType.toString()));
        JsonObject missingDay = json(sample());
        missingDay.getAsJsonObject("data").getAsJsonObject("weeks").getAsJsonObject(WEEK)
                .getAsJsonArray("days").remove(6);
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(missingDay.toString()));
        JsonObject wrongMonday = json(sample());
        JsonObject weeks = wrongMonday.getAsJsonObject("data").getAsJsonObject("weeks");
        weeks.add("2026-11-03", weeks.remove(WEEK));
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(wrongMonday.toString()));
    }

    @Test public void invalidUuidBlankConfirmedAndFractionalMinutesAreRejected() {
        JsonObject invalidId = json(sample());
        firstDay(invalidId, "weeks").get(0).getAsJsonObject().addProperty("id", "1-1-1-1-1");
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(invalidId.toString()));
        JsonObject blank = json(sample());
        firstDay(blank, "weeks").get(0).getAsJsonObject().addProperty("title", "  ");
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(blank.toString()));
        JsonObject fraction = json(sample());
        fraction.getAsJsonObject("data").getAsJsonObject("settings").addProperty("continuousMinutes", 1.5);
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(fraction.toString()));
    }

    @Test public void wrongSnoozeGraceAndOutOfRangeMinutesAreRejected() {
        JsonObject root = json(sample());
        JsonObject settings = root.getAsJsonObject("data").getAsJsonObject("settings");
        settings.addProperty("snoozeMinutes", 10);
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(root.toString()));
        settings.addProperty("snoozeMinutes", 30);
        settings.addProperty("graceSeconds", 2);
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(root.toString()));
        settings.addProperty("graceSeconds", 120);
        settings.addProperty("continuousMinutes", 0);
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(root.toString()));
    }

    @Test public void oversizedInputDuplicateJsonFieldsAndTrailingContentAreRejected() {
        char[] huge = new char[BackupCodec.MAX_BYTES + 1];
        java.util.Arrays.fill(huge, 'x');
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(new String(huge)));
        String good = BackupCodec.encode(AppState.empty());
        String duplicate = good.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1");
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(duplicate));
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode(good + " {}"));
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.decode("/*comment*/" + good));
    }
}
