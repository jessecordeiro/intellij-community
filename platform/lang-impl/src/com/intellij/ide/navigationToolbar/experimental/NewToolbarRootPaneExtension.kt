// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.navigationToolbar.experimental

import com.intellij.ide.ActivityTracker
import com.intellij.ide.ui.ToolbarSettings
import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.ide.ui.experimental.toolbar.RunWidgetAvailabilityManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeRootPaneNorthExtension
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBSwingUtilities
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Graphics
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

interface NewToolbarPaneListener {
  companion object {
    val TOPIC: Topic<NewToolbarPaneListener> = Topic(
      NewToolbarPaneListener::class.java,
      Topic.BroadcastDirection.NONE,
      true,
    )
  }

  fun stateChanged()
}

class NewToolbarRootPaneExtension(private val myProject: Project) : IdeRootPaneNorthExtension(), Disposable {
  companion object {
    private const val NEW_TOOLBAR_KEY = "NEW_TOOLBAR_KEY"

    private val logger = logger<NewToolbarRootPaneExtension>()
  }

  private val runWidgetAvailabilityManager = RunWidgetAvailabilityManager.getInstance(myProject)
  private val runWidgetListener = object : RunWidgetAvailabilityManager.RunWidgetAvailabilityListener {
    override fun availabilityChanged(value: Boolean) {
      repaint()
    }
  }

  private val myPanel: JPanel = object : JPanel(NewToolbarBorderLayout()) {
    init {
      isOpaque = true
      border = BorderFactory.createEmptyBorder(0, JBUI.scale(4), 0, JBUI.scale(4))
    }

    override fun getComponentGraphics(graphics: Graphics?): Graphics {
      return JBSwingUtilities.runGlobalCGTransform(this, super.getComponentGraphics(graphics))
    }
  }

  init {
    Disposer.register(myProject, this)

    myProject.messageBus
      .connect(this)
      .subscribe(NewToolbarPaneListener.TOPIC, object : NewToolbarPaneListener {
        override fun stateChanged() {
          repaint()
        }
      })

    runWidgetAvailabilityManager.addListener(runWidgetListener)
  }

  override fun getKey() = NEW_TOOLBAR_KEY

  override fun revalidate() {
    ActivityTracker.getInstance().inc()

    myPanel.removeAll()
    if (myPanel.isVisible) {
      val actionsSchema = CustomActionsSchema.getInstance()
      for ((actionId, layoutConstrains) in mapOf(
        (if (runWidgetAvailabilityManager.isAvailable()) "RightToolbarSideGroupNoRunWidget" else "RightToolbarSideGroup") to BorderLayout.EAST,
        "CenterToolbarSideGroup" to BorderLayout.CENTER,
        "LeftToolbarSideGroup" to BorderLayout.WEST,
      )) {
        val action = actionsSchema.getCorrectedAction(actionId)
        val actionGroup = action as? ActionGroup
                          ?: throw IllegalArgumentException("Action group '$actionId' not found; actual action: $action")
        val toolbar = ActionManager.getInstance().createActionToolbar(
          ActionPlaces.RUN_TOOLBAR,
          actionGroup,
          true,
        )
        toolbar.targetComponent = myPanel

        myPanel.add(toolbar as JComponent, layoutConstrains)
      }
    }

    myPanel.revalidate();
  }

  override fun getComponent(): JComponent = myPanel

  override fun uiSettingsChanged(settings: UISettings) {
    logger.info("Show old main toolbar: ${settings.showMainToolbar}; show old navigation bar: ${settings.showNavigationBar}")

    val toolbarSettings = ToolbarSettings.Instance
    myPanel.isEnabled = toolbarSettings.isEnabled
    myPanel.isVisible = toolbarSettings.isVisible && !settings.presentationMode

    repaint()
  }

  private fun repaint() {
    revalidate()
    myPanel.repaint()
  }

  override fun copy() = NewToolbarRootPaneExtension(myProject)

  override fun dispose() {
    runWidgetAvailabilityManager.removeListener(runWidgetListener)
  }
}