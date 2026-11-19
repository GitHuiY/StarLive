package io.github.githuiy.starlive

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.githuiy.starlive.data.WithinFontFamily
import io.github.githuiy.starlive.data.WithinListStyle
import io.github.githuiy.starlive.data.WithinParagraphAlignment
import io.github.githuiy.starlive.data.WithinRepository
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WithinUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    @Test fun sixStarsStayReachableAndFocusReturnsOnNarrowScreenWithLargerText() {
        RuntimeEnvironment.setFontScale(1.6f)
        openWithin()
        val music = node("music_toggle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val nav = node("nav_ability").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val labels = mapOf("SELF" to "自我", "WORLD" to "世界", "MOMENT" to "瞬间",
            "ECHO" to "回响", "FOOTPRINT" to "足迹", "BEYOND" to "远方")
        labels.forEach { (name, label) ->
            list().performScrollToIndex(0)
            node("within_gemini_sky").assertIsDisplayed()
            val star = node("within_star_$name").assertIsDisplayed().assertHasClickAction()
            val bounds = star.fetchSemanticsNode().boundsInRoot
            assertTrue("star $name must be below the top controls", bounds.top >= music.bottom)
            assertTrue("star $name must stay above the bottom navigation", bounds.bottom <= nav.top)
            star.performClick()
            node("within_focused_star").assertIsDisplayed()
            node("within_all_stars").assertDoesNotExist()
            listNode("within_list_title").assertTextContains(label)
            listNode("within_empty").assertIsDisplayed()
            selectAll()
        }
        selectStar("SELF")
        pressBack()
        node("within_focused_star").assertDoesNotExist()
        listNode("within_list_title").assertTextContains("全部随笔")
        node("star_0").assertDoesNotExist()
        node("music_toggle").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "zh-rCN-w800dp-h360dp-land-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
    @Test fun shortLandscapeShowsBothEndsOfSkyAndCanScrollToEssays() {
        openWithin()
        list().performScrollToIndex(0)
        for (name in listOf("SELF", "WORLD", "MOMENT", "ECHO", "FOOTPRINT", "BEYOND")) {
            node("within_star_$name").assertIsDisplayed().assertHasClickAction()
        }
        listNode("within_add").assertIsDisplayed()
        listNode("within_empty").assertIsDisplayed()
        node("music_toggle").assertIsDisplayed()
        node("nav_ability").assertIsDisplayed()
    }

    @Test fun essaysCanBeUnclassifiedOrMultiCategoryEditedReopenedAndDeleted() {
        openWithin()
        add()
        node("within_editor_save").assertIsNotEnabled()
        node("within_editor_open_categories").performClick()
        node("within_editor_category_SELF").reveal().assertIsNotSelected()
        node("within_editor_apply_categories").performClick()
        editorText("body", "验收 · 无分类")
        node("within_editor_save").performClick()
        waitForEditorToClose()
        preview("验收 · 无分类").assertIsDisplayed()

        selectStar("WORLD")
        listNode("within_empty").assertIsDisplayed()
        add()
        node("within_editor_open_categories").performClick()
        node("within_editor_category_WORLD").reveal().assertIsSelected()
        node("within_editor_category_MOMENT").reveal().performClick().assertIsSelected()
        node("within_editor_apply_categories").performClick()
        editorText("body", "验收 · 两个分类")
        node("within_editor_open_date").performClick()
        editorText("date", "2026-02-30")
        node("within_editor_apply_date").performClick()
        node("within_editor_date_error").reveal().assertIsDisplayed()
        editorText("date", "2026-11-15")
        node("within_editor_apply_date").performClick()
        node("within_editor_date").assertDoesNotExist()
        node("within_editor_save").performClick()
        waitForEditorToClose()
        preview("验收 · 两个分类").assertIsDisplayed()

        selectAll()
        selectStar("MOMENT")
        openEssay("验收 · 两个分类")
        node("within_essay_body").assertTextContains("验收 · 两个分类")
        compose.onNodeWithText("2026-11-15").assertIsDisplayed()
        node("within_edit").performClick()
        editorText("body", "验收 · 放弃的修改")
        node("within_editor_cancel").performClick()
        node("within_editor_discard").performClick()
        waitForEditorToClose()
        node("within_essay_body").assertTextContains("验收 · 两个分类")

        node("within_edit").performClick()
        node("within_editor_open_categories").performClick()
        node("within_editor_category_WORLD").reveal().assertIsSelected().performClick().assertIsNotSelected()
        node("within_editor_apply_categories").performClick()
        editorText("body", "验收 · 已编辑")
        node("within_editor_save").performClick()
        waitForEditorToClose()
        node("within_essay_body").assertTextContains("验收 · 已编辑")
        node("within_reader_back").performClick()
        selectAll()
        selectStar("WORLD")
        listNode("within_empty").assertIsDisplayed()
        pressBack()
        listNode("within_list_title").assertTextContains("全部随笔")
        preview("验收 · 已编辑").assertIsDisplayed()
        compose.onAllNodesWithText("验收 · 已编辑", useUnmergedTree = true).assertCountEquals(1)

        checkNotNull(scenario).close()
        scenario = null
        openWithin()
        openEssay("验收 · 已编辑")
        node("within_remove").performClick()
        node("within_delete_cancel").performClick()
        node("within_essay_body").assertTextContains("验收 · 已编辑")
        node("within_remove").performClick()
        node("within_delete_confirm").performClick()
        compose.waitUntil(10_000) { !hasTag("within_reader") }
        preview("验收 · 无分类").assertIsDisplayed()
        compose.onAllNodesWithText("验收 · 已编辑", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun essayFormattingPreservesSelectiveStylesWhenAnotherPropertyChangesForAllText() {
        openWithin()
        add()
        val text = "星空第一行\n这是第二行"
        editorText("body", text)
        val selectionEnd = text.indexOf('\n')
        selectBody(0, selectionEnd)
        formatTool("within_font_SERIF").performClick()
        formatTool("within_size_24").performClick()
        formatTool("within_color_1").performClick()
        formatTool("within_format_bold").performClick()
        formatTool("within_format_strikethrough").performClick()
        formatTool("within_format_highlight").performClick()
        node("within_editor_save").performClick()
        waitForEditorToClose()

        val repository = WithinRepository(ApplicationProvider.getApplicationContext<Application>())
        val before = runBlocking { repository.load().single().draft.spans.single() }
        assertEquals(0, before.start)
        assertEquals(selectionEnd, before.end)
        assertEquals(0xFFDFC38D.toInt(), before.style.colorArgb)

        openEssay(text)
        node("within_edit").performClick()
        selectBody(text.length, text.length)
        formatTool("within_color_2").performClick()
        node("within_editor_save").performClick()
        waitForEditorToClose()

        val draft = runBlocking { repository.load().single().draft }
        assertEquals(2, draft.spans.size)
        val (selected, remaining) = draft.spans
        assertEquals(text, draft.body)
        assertEquals(0, selected.start)
        assertEquals(selectionEnd, selected.end)
        assertEquals(WithinFontFamily.SERIF, selected.style.fontFamily)
        assertEquals(24, selected.style.fontSizeSp)
        assertTrue(selected.style.bold)
        assertTrue(selected.style.strikethrough && selected.style.highlight)
        assertEquals(selectionEnd, remaining.start)
        assertEquals(text.length, remaining.end)
        assertFalse(remaining.style.bold)
        assertFalse(remaining.style.strikethrough || remaining.style.highlight)
        assertNull(remaining.style.fontFamily)
        assertNull(remaining.style.fontSizeSp)
        assertEquals(0xFFB9DEFF.toInt(), selected.style.colorArgb)
        assertEquals(selected.style.colorArgb, remaining.style.colorArgb)
        node("within_essay_body").assertTextContains(text)
    }

    @Test fun paragraphToolsKeepTheSelectionAndSaveFormattingWithoutChangingTheBody() {
        openWithin()
        add()
        val text = "第一段\n第二段\n第三段"
        val third = text.indexOf("第三段")
        editorText("body", text)
        selectBody(0, third)
        node("within_editor_format_tools").performClick().assertIsSelected()
        node("within_editor_paragraph_tools").performClick().assertIsSelected()
        node("within_editor_format_tools").assertIsNotSelected()
        node("within_format_bold").assertDoesNotExist()
        node("within_paragraph_CENTER").performClick()
        node("within_list_DECIMAL").performClick()
        pressBack()
        node("within_editor_tool_panel").assertDoesNotExist()
        node("within_editor_paragraph_tools").assertIsNotSelected()
        selectBody(text.length, text.length)
        node("within_editor_paragraph_tools").performClick()
        node("within_paragraph_END").performClick()
        node("within_list_LETTERS").performClick()
        node("within_editor_save").performClick()
        waitForEditorToClose()

        val repository = WithinRepository(ApplicationProvider.getApplicationContext<Application>())
        val record = runBlocking { repository.load().single() }
        assertEquals(text, record.draft.body)
        assertEquals(listOf(0, text.indexOf("第二段"), third), record.draft.paragraphs.map { it.offset })
        assertEquals(listOf(WithinParagraphAlignment.CENTER, WithinParagraphAlignment.CENTER, WithinParagraphAlignment.END),
            record.draft.paragraphs.map { it.alignment })
        assertEquals(listOf(WithinListStyle.DECIMAL, WithinListStyle.DECIMAL, WithinListStyle.LETTERS),
            record.draft.paragraphs.map { it.list })
        listNode("within_essay_" + record.id).performClick()
        node("within_essay_body").assertTextContains("1. 第一段\n2. 第二段\na. 第三段")
    }

    @Test fun writerUndoAndRedoRestoreFormattingAndSelectionAcrossRecreation() {
        openWithin()
        add()
        val undo = node("within_editor_undo").assertIsNotEnabled()
        val redo = node("within_editor_redo").assertIsNotEnabled()
        val phrase = "星光\n计划"
        editorText("body", phrase)
        selectBody(0, 2)
        formatTool("within_format_bold").performClick().assertIsSelected()
        undo.performClick()
        node("within_format_bold").assertIsNotSelected()
        val selection = node("within_editor_body").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(0, 2), selection)

        checkNotNull(scenario).recreate()
        compose.waitUntil(10_000) { hasTag("within_editor") && hasTag("within_format_bold") }
        redo.assertIsEnabled().performClick()
        node("within_format_bold").assertIsSelected()
        redo.assertIsNotEnabled()
        node("within_editor_paragraph_tools").performClick()
        node("within_paragraph_CENTER").performClick().assertIsSelected()
        undo.performClick()
        node("within_paragraph_START").assertIsSelected()
        node("within_paragraph_END").performClick().assertIsSelected()
        redo.assertIsNotEnabled()
        node("within_editor_save").performClick()
        waitForEditorToClose()

        val repository = WithinRepository(ApplicationProvider.getApplicationContext<Application>())
        val draft = runBlocking { repository.load().single().draft }
        assertEquals(phrase, draft.body)
        assertTrue(draft.spans.single().style.bold)
        assertEquals(2, draft.spans.single().end)
        assertEquals(WithinParagraphAlignment.END, draft.paragraphs.single().alignment)
    }

    @Test fun enterContinuesTheListAndExitsAnEmptyItemAsOneUndoableStep() {
        openWithin()
        add()
        val text = "第一项"
        editorText("body", text)
        selectBody(text.length, text.length)
        node("within_editor_paragraph_tools").performClick()
        val list = node("within_list_DECIMAL").performClick().assertIsSelected()
        val body = node("within_editor_body")
        body.performTextInput("\n")
        list.assertIsSelected()
        body.performTextInput("\n")
        list.assertIsNotSelected()
        node("within_editor_undo").performClick()
        list.assertIsSelected()
        node("within_editor_redo").performClick()
        list.assertIsNotSelected()
        node("within_editor_save").performClick()
        waitForEditorToClose()
        val repository = WithinRepository(ApplicationProvider.getApplicationContext<Application>())
        val draft = runBlocking { repository.load().single().draft }
        assertEquals("$text\n", draft.body)
        assertEquals(listOf(0), draft.paragraphs.map { it.offset })
        assertEquals(WithinListStyle.DECIMAL, draft.paragraphs.single().list)
    }

    @Config(shadows = [PosixAtomicFileShadow::class, FileProviderPathShadow::class])
    @Test fun doodleCanBeUndoneCanceledAndReopenedAsAnEssayAttachment() {
        openWithin()
        add()
        val text = "随笔涂鸦测试"
        editorText("body", text)
        selectBody(text.length, text.length)
        node("within_editor_more_tools").performClick()
        node("within_editor_doodle").performClick()
        node("within_doodle_done").assertIsNotEnabled()
        node("within_doodle_canvas").performTouchInput { swipeRight() }
        node("within_doodle_undo").performClick()
        node("within_doodle_done").assertIsNotEnabled()
        node("within_doodle_cancel").performClick()
        node("within_doodle").assertDoesNotExist()
        node("within_editor_doodle").performClick()
        node("within_doodle_canvas").performTouchInput { swipeRight() }
        node("within_doodle_done").performClick()
        compose.waitUntil(10_000) { !hasTag("within_doodle") && hasDraftImage("涂鸦") }
        node("within_editor_save").performClick()
        waitForEditorToClose()

        val repository = WithinRepository(ApplicationProvider.getApplicationContext<Application>())
        val draft = runBlocking { repository.load().single().draft }
        val image = draft.images.single()
        assertEquals(text, draft.body)
        assertEquals(text.length, image.offset)
        assertEquals("image/png", image.mimeType)
        openEssay(text)
        compose.waitUntil(10_000) { hasTag("within_image_" + image.id) }
        node("within_image_" + image.id).assertIsDisplayed().assertContentDescriptionContains("涂鸦")
    }

    @Test fun immersiveWriterKeepsControlsFixedAndConfirmsDiscardForSystemBack() {
        openWithin()
        add()
        node("nav_ability").assertDoesNotExist()
        node("music_toggle").assertDoesNotExist()
        val controls = listOf("within_editor_cancel", "within_editor_save",
            "within_editor_format_tools", "within_editor_image_tools")
        val initialBounds = controls.associateWith { node(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        val text = (1..55).joinToString("\n") { "第 ${it} 行 · 星空随笔" }
        editorText("body", text)
        selectBody(text.length - 4, text.length)
        val body = node("within_editor_body").assertIsDisplayed()
        assertEquals(TextRange(text.length - 4, text.length),
            body.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        body.performTouchInput { swipeDown() }
        controls.forEach {
            assertEquals(initialBounds.getValue(it), node(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        }

        pressBack()
        node("within_editor_discard").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        body.assertTextContains(text)
        pressBack()
        node("within_editor_discard").performClick()
        waitForEditorToClose()
        listNode("within_empty").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "zh-rCN-w800dp-h360dp-land-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
    @Test fun landscapeWriterKeepsItsBodyAndToolsReachableWithLargerText() {
        RuntimeEnvironment.setFontScale(1.6f)
        openWithin()
        add()
        node("within_editor_open_date").assertIsDisplayed()
        node("within_editor_open_categories").assertIsDisplayed()
        node("within_editor_cancel").assertIsDisplayed()
        node("within_editor_save").assertIsDisplayed()
        node("within_editor_format_tools").performClick()
        node("within_format_bold").reveal().assertIsDisplayed()
        val body = node("within_editor_body").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val toolbar = node("within_editor_toolbar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(body.height > 0 && body.bottom <= toolbar.top)
        node("within_editor_image_tools").performClick()
        node("within_editor_pick_image").reveal().assertIsDisplayed()
        node("within_editor_paste_image").reveal().assertIsDisplayed()
    }

    @Test fun clipboardImagePersistsAtTheCursorAndAppearsBetweenTextSections() {
        openWithin()
        add()
        val before = "剪贴板前"
        val after = "剪贴板后"
        val text = before + after
        editorText("body", text)
        selectBody(before.length, before.length)
        val png = ByteArrayOutputStream().use { bytes ->
            val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            bytes.toByteArray()
        }
        val uri = Uri.parse("content://starlive-within-test/clipboard.png")
        checkNotNull(scenario).onActivity { activity ->
            shadowOf(activity.applicationContext.contentResolver).registerInputStreamSupplier(uri) { png.inputStream() }
            checkNotNull(activity.getSystemService(ClipboardManager::class.java))
                .setPrimaryClip(ClipData.newRawUri("图片", uri))
        }
        node("within_editor_image_tools").performClick()
        node("within_editor_pick_image").reveal().assertIsDisplayed()
        node("within_editor_paste_image").reveal().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("图片", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        node("within_editor_undo").performClick()
        compose.waitUntil(10_000) { !hasDraftImage("图片") }
        node("within_editor_redo").performClick()
        compose.waitUntil(10_000) { hasDraftImage("图片") }
        compose.onNodeWithText("移除").reveal().performClick()
        compose.waitUntil(10_000) { !hasDraftImage("图片") }
        node("within_editor_undo").performClick()
        compose.waitUntil(10_000) { hasDraftImage("图片") }
        node("within_editor_save").performClick()
        waitForEditorToClose()

        val repository = WithinRepository(ApplicationProvider.getApplicationContext<Application>())
        val draft = runBlocking { repository.load().single().draft }
        val image = draft.images.single()
        assertEquals(text, draft.body)
        assertEquals(before.length, image.offset)
        assertEquals("image/png", image.mimeType)

        openEssay(text)
        val imageTag = "within_image_${image.id}"
        compose.waitUntil(10_000) { hasTag(imageTag) }
        val picture = node(imageTag).assertIsDisplayed().assertContentDescriptionContains(image.name)
            .fetchSemanticsNode().boundsInRoot
        val body = hasAnyAncestor(hasTestTag("within_essay_body"))
        val preceding = compose.onNode(hasText(before) and body).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val following = compose.onNode(hasText(after) and body).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(preceding.bottom <= picture.top && picture.bottom <= following.top)
    }

    private fun openWithin() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The framework caches this factory across Robolectric application instances.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { hasTag("nav_ability") }
        compose.onNodeWithTag("nav_ability").performClick().assertIsSelected().assertTextContains("Within")
        listNode("within_list_title").assertTextContains("全部随笔")
        listNode("within_add")
        compose.waitUntil(10_000) {
            runCatching { !node("within_add").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
                .getOrDefault(false)
        }
    }

    private fun add() {
        listNode("within_add").performClick()
        compose.waitUntil(10_000) { hasTag("within_editor") }
    }

    private fun selectStar(name: String) {
        list().performScrollToIndex(0)
        node("within_star_$name").performClick()
        node("within_focused_star").assertIsDisplayed()
    }

    private fun selectAll() {
        list().performScrollToIndex(0)
        val sky = node("within_sky_background").assertIsDisplayed().assertHasClickAction()
        sky.performTouchInput { click(Offset(width * .5f, height * .43f)) }
        node("within_focused_star").assertIsDisplayed()
        sky.performTouchInput { click(Offset(width * .06f, height * .12f)) }
        node("within_focused_star").assertDoesNotExist()
        listNode("within_list_title").assertTextContains("全部随笔")
    }

    private fun openEssay(text: String) {
        list().performScrollToNode(hasText(text))
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("within_essays"))).performClick()
        node("within_reader").assertIsDisplayed()
    }

    private fun preview(text: String): SemanticsNodeInteraction {
        list().performScrollToNode(hasText(text))
        return compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("within_essays"))).reveal()
    }

    private fun editorText(field: String, text: String) {
        node("within_editor_$field").reveal().performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString(text))
        }
    }

    private fun selectBody(start: Int, end: Int) {
        node("within_editor_body").reveal().performSemanticsAction(SemanticsActions.SetSelection) {
            it(start, end, true)
        }
    }

    private fun formatTool(tag: String): SemanticsNodeInteraction {
        if (!hasTag(tag)) node("within_editor_format_tools").performClick()
        return node(tag).reveal()
    }

    private fun hasDraftImage(description: String) =
        compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun pressBack() {
        checkNotNull(scenario).onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    private fun list() = node("within_essays")
    private fun listNode(tag: String): SemanticsNodeInteraction {
        list().performScrollToNode(hasTestTag(tag))
        return node(tag).reveal()
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction =
        if (isDisplayed()) this else performScrollTo()

    private fun hasTag(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForEditorToClose() = compose.waitUntil(10_000) { !hasTag("within_editor") }
}
