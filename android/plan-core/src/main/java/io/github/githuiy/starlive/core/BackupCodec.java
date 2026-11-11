package io.github.githuiy.starlive.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict, bounded version-1 backup format. Parsing never mutates an existing state. */
public final class BackupCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_CATEGORY_LENGTH = 40;
    public static final int MAX_BODY_LENGTH = 2_000;
    private static final int MAX_WEEKS = 5200;
    private static final int MAX_ITEMS = 20_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private BackupCodec() { }

    public static final class Prepared {
        public final AppState data;
        public final BackupSummary summary;
        private Prepared(AppState data, BackupSummary summary) {
            this.data = data;
            this.summary = summary;
        }
    }

    public static String encode(AppState state) {
        JsonObject body = new JsonObject();
        body.add("weeks", encodeWeeks(state.weeks));
        body.add("drafts", encodeWeeks(state.drafts));
        JsonObject settings = new JsonObject();
        settings.addProperty("continuousMinutes", state.settings.continuousMinutes);
        settings.addProperty("perAppDailyMinutes", state.settings.perAppDailyMinutes);
        settings.addProperty("totalDailyMinutes", state.settings.totalDailyMinutes);
        settings.addProperty("continuousEnabled", state.settings.continuousEnabled);
        settings.addProperty("perAppDailyEnabled", state.settings.perAppDailyEnabled);
        settings.addProperty("totalDailyEnabled", state.settings.totalDailyEnabled);
        settings.addProperty("snoozeMinutes", state.settings.snoozeMinutes);
        settings.addProperty("graceSeconds", state.settings.graceSeconds);
        body.add("settings", settings);
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", SCHEMA_VERSION);
        document.add("data", body);
        String encoded = GSON.toJson(document);
        // The same limits and invariants apply to live writes and imported files.
        decode(encoded);
        return encoded;
    }

    public static Prepared decode(String json) {
        if (json == null || json.length() > MAX_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("备份超过 2 MiB 大小上限");
        }
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject document = object(readValue(reader, 0), "备份根对象");
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("JSON 后存在额外内容");
            keys(document, "schemaVersion", "data");
            if (integer(document.get("schemaVersion")) != SCHEMA_VERSION) {
                throw invalid("不支持此备份版本；当前仅支持版本 1");
            }
            JsonObject body = object(document.get("data"), "data");
            keys(body, "weeks", "drafts", "settings");
            int[] counts = new int[3];
            Map<String, WeekPlan> weeks = readWeeks(body.get("weeks"), false, counts);
            Map<String, WeekPlan> drafts = readWeeks(body.get("drafts"), true, counts);
            JsonObject settings = object(body.get("settings"), "settings");
            keys(settings, "continuousMinutes", "perAppDailyMinutes", "totalDailyMinutes",
                    "continuousEnabled", "perAppDailyEnabled", "totalDailyEnabled",
                    "snoozeMinutes", "graceSeconds");
            if (integer(settings.get("snoozeMinutes")) != ReminderSettings.SNOOZE_MINUTES
                    || integer(settings.get("graceSeconds")) != ReminderSettings.GRACE_SECONDS) {
                throw invalid("版本 1 的稍后提醒必须为 30 分钟，宽限为 120 秒");
            }
            ReminderSettings reminders = new ReminderSettings(
                    integer(settings.get("continuousMinutes")),
                    integer(settings.get("perAppDailyMinutes")),
                    integer(settings.get("totalDailyMinutes")),
                    bool(settings.get("continuousEnabled")),
                    bool(settings.get("perAppDailyEnabled")),
                    bool(settings.get("totalDailyEnabled")));
            return new Prepared(new AppState(weeks, drafts, reminders),
                    new BackupSummary(weeks.size(), drafts.size(), counts[0], counts[1], counts[2]));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("备份 JSON 无效：" + failure.getClass().getSimpleName());
        }
    }

    private static JsonObject encodeWeeks(Map<String, WeekPlan> source) {
        JsonObject weeks = new JsonObject();
        for (Map.Entry<String, WeekPlan> entry : source.entrySet()) {
            JsonObject week = new JsonObject();
            JsonArray days = new JsonArray();
            for (List<PlanItem> day : entry.getValue().days) {
                JsonArray items = new JsonArray();
                for (PlanItem item : day) {
                    JsonObject value = new JsonObject();
                    value.addProperty("id", item.id);
                    value.addProperty("title", item.title);
                    value.addProperty("category", item.category);
                    value.addProperty("done", item.done);
                    if (!item.body.isEmpty()) value.addProperty("body", item.body);
                    items.add(value);
                }
                days.add(items);
            }
            week.add("days", days);
            weeks.add(entry.getKey(), week);
        }
        return weeks;
    }

    private static Map<String, WeekPlan> readWeeks(JsonElement element, boolean drafts, int[] counts) {
        JsonObject object = object(element, drafts ? "drafts" : "weeks");
        if (object.size() > MAX_WEEKS) throw invalid("周记录数量超过上限");
        Map<String, WeekPlan> result = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            LocalDate monday;
            try { monday = LocalDate.parse(entry.getKey()); }
            catch (RuntimeException failure) { throw invalid("周起始日期无效"); }
            if (!monday.toString().equals(entry.getKey()) || monday.getDayOfWeek() != DayOfWeek.MONDAY
                    || monday.getYear() < 1900 || monday.getYear() > 9999) {
                throw invalid("周键必须是 1900–9999 年内的 ISO 周一日期");
            }
            JsonObject week = object(entry.getValue(), "week");
            keys(week, "days");
            JsonArray days = array(week.get("days"));
            if (days.size() != 7) throw invalid("一周必须包含七天");
            List<List<PlanItem>> parsedDays = new ArrayList<>();
            for (JsonElement day : days) {
                List<PlanItem> parsed = new ArrayList<>();
                for (JsonElement value : array(day)) {
                    if (counts[0] + counts[1] >= MAX_ITEMS) throw invalid("事项数量超过 20000 上限");
                    JsonObject item = object(value, "item");
                    boolean hasBody = item.has("body");
                    if (hasBody) keys(item, "id", "title", "category", "done", "body");
                    else keys(item, "id", "title", "category", "done");
                    String id = string(item.get("id"));
                    try {
                        if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw invalid("事项 ID 无效");
                    } catch (RuntimeException failure) { throw invalid("事项 ID 必须为规范 UUID"); }
                    id = id.toLowerCase(Locale.ROOT);
                    if (!ids.add(id)) throw invalid("同一计划区内出现重复事项 ID");
                    String title = string(item.get("title"));
                    String category = string(item.get("category"));
                    boolean done = bool(item.get("done"));
                    String body = hasBody ? string(item.get("body")) : "";
                    if (title.length() > MAX_TITLE_LENGTH || (!drafts && title.trim().isEmpty())
                            || category.trim().isEmpty() || category.length() > MAX_CATEGORY_LENGTH
                            || (title.trim().isEmpty() && done)) {
                        throw invalid("事项标题、分类或完成状态无效");
                    }
                    if (body.length() > MAX_BODY_LENGTH) throw invalid("事项正文超过长度上限");
                    parsed.add(new PlanItem(id, title, category, done, body));
                    counts[drafts ? 1 : 0]++;
                    if (!drafts && done) counts[2]++;
                }
                parsedDays.add(parsed);
            }
            result.put(entry.getKey(), new WeekPlan(parsedDays));
        }
        return result;
    }

    /** A strict reader also rejects duplicate JSON property names, before tree parsing loses them. */
    private static JsonElement readValue(JsonReader reader, int depth) throws java.io.IOException {
        if (depth > 16) throw invalid("JSON 嵌套过深");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                reader.beginObject();
                JsonObject object = new JsonObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw invalid("JSON 字段重复：" + name);
                    object.add(name, readValue(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    if (array.size() > MAX_ITEMS) throw invalid("JSON 数组过大");
                    array.add(readValue(reader, depth + 1));
                }
                reader.endArray();
                return array;
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw invalid("JSON 值无效");
        }
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) throw invalid(name + " 必须为对象");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value) {
        if (value == null || !value.isJsonArray()) throw invalid("日期和事项必须为数组");
        return value.getAsJsonArray();
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid("文字字段类型错误");
        }
        return value.getAsString();
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid("数字字段类型错误");
        }
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException failure) { throw invalid("数字必须为范围内整数"); }
    }

    private static boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid("开关字段必须为布尔值");
        }
        return value.getAsBoolean();
    }

    private static void keys(JsonObject value, String... expected) {
        Set<String> wanted = new HashSet<>(Arrays.asList(expected));
        if (!wanted.equals(value.keySet())) throw invalid("备份字段缺失或包含未知字段");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
