// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.intention.impl.preview

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.preview.IntentionPreviewComponent.Companion.HTML_PREVIEW
import com.intellij.codeInsight.intention.impl.preview.IntentionPreviewComponent.Companion.LOADING_PREVIEW
import com.intellij.codeInsight.intention.impl.preview.IntentionPreviewComponent.Companion.NO_PREVIEW
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.SoftWrapChangeListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiFile
import com.intellij.ui.ScreenUtil
import com.intellij.ui.popup.PopupPositionManager
import com.intellij.ui.popup.PopupPositionManager.Position.LEFT
import com.intellij.ui.popup.PopupPositionManager.Position.RIGHT
import com.intellij.ui.popup.PopupUpdateProcessor
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.annotations.TestOnly
import java.awt.Dimension
import java.awt.LayoutManager2
import kotlin.math.min

class IntentionPreviewPopupUpdateProcessor(private val project: Project,
                                           private val originalFile: PsiFile,
                                           private val originalEditor: Editor) : PopupUpdateProcessor(project) {
  private var index: Int = LOADING_PREVIEW
  private var show = false
  private val editorsToRelease = mutableListOf<EditorEx>()

  private lateinit var popup: JBPopup
  private lateinit var component: IntentionPreviewComponent

  override fun updatePopup(intentionAction: Any?) {
    if (!show) return

    if (!::popup.isInitialized || popup.isDisposed) {
      component = IntentionPreviewComponent(project)

      component.multiPanel.select(LOADING_PREVIEW, true)

      popup = JBPopupFactory.getInstance().createComponentPopupBuilder(component, null)
        .setCancelCallback { cancel() }
        .setCancelKeyEnabled(false)
        .setShowBorder(false)
        .addUserData(IntentionPreviewPopupKey())
        .createPopup()

      positionPreview()
    }

    val value = component.multiPanel.getValue(index, false)
    if (value != null) {
      select(index)
      return
    }

    val action = intentionAction as IntentionAction

    component.startLoading()

    ReadAction.nonBlocking(
      IntentionPreviewComputable(project, action, originalFile, originalEditor))
      .expireWith(popup)
      .coalesceBy(this)
      .finishOnUiThread(ModalityState.defaultModalityState()) { renderPreview(it)}
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun renderPreview(result: IntentionPreviewContent) {
    when (result) {
      is IntentionPreviewDiffResult -> {
        val editors = IntentionPreviewModel.createEditors(project, result)
        if (editors.isEmpty()) {
          select(NO_PREVIEW)
          return
        }

        editorsToRelease.addAll(editors)
        select(index, editors)
      }
      is IntentionPreviewHtmlResult -> {
        select(HTML_PREVIEW, html = result.html)
      }
      else -> {
        select(NO_PREVIEW)
      }
    }
  }

  fun setup(parentIndex: Int) {
    index = parentIndex
  }

  fun isShown() = show

  fun hide() {
    if (::popup.isInitialized && !popup.isDisposed) {
      popup.cancel()
    }
  }

  fun show() {
    show = true
  }

  private fun cancel(): Boolean {
    editorsToRelease.forEach { EditorFactory.getInstance().releaseEditor(it) }
    editorsToRelease.clear()
    component.removeAll()
    show = false
    return true
  }

  private fun select(index: Int, editors: List<EditorEx> = emptyList(), @NlsSafe html: String = "") {
    component.stopLoading()
    component.editors = editors
    component.htmlContent.text = html
    component.multiPanel.select(index, true)

    val size = component.preferredSize
    val location = popup.locationOnScreen
    val screen = ScreenUtil.getScreenRectangle(location)

    if (screen != null) {
      val delta = screen.width + screen.x - location.x
      if (size.width > delta) {
        size.width = delta
      }
    }

    component.editors.forEach {
      it.softWrapModel.addSoftWrapChangeListener(object : SoftWrapChangeListener {
        override fun recalculationEnds() {
          val height = (it as EditorImpl).offsetToXY(it.document.textLength).y + it.lineHeight + 6
          it.component.preferredSize = Dimension(it.component.preferredSize.width, min(height, MAX_HEIGHT))
          val parent = it.component.parent
          (parent.layout as LayoutManager2).invalidateLayout(parent)
          popup.pack(true, true)
        }

        override fun softWrapsChanged() {}
      })

      it.component.preferredSize = Dimension(size.width, min(it.component.preferredSize.height, MAX_HEIGHT))
    }

    popup.pack(true, true)
  }

  private fun positionPreview() {
    PopupPositionManager.positionPopupInBestPosition(popup, originalEditor, null, RIGHT, LEFT)
  }

  companion object {
    private const val MAX_HEIGHT = 300

    fun getShortcutText(): String = KeymapUtil.getPreferredShortcutText(getShortcutSet().shortcuts)
    fun getShortcutSet(): ShortcutSet = KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_QUICK_JAVADOC)

    @TestOnly
    fun getPreviewText(project: Project,
                       action: IntentionAction,
                       originalFile: PsiFile,
                       originalEditor: Editor): String? {
      val preview = IntentionPreviewComputable(project, action, originalFile, originalEditor).generatePreview()
      return preview?.psiFile?.text
    }
  }

  internal class IntentionPreviewPopupKey
}