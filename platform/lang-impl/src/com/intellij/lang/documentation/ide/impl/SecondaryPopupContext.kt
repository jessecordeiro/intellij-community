// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.lang.documentation.ide.impl

import com.intellij.lang.documentation.ide.ui.DocumentationPopupUI
import com.intellij.openapi.ui.popup.ComponentPopupBuilder
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.ui.popup.AbstractPopup
import com.intellij.ui.popup.PopupPositionManager
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

internal abstract class SecondaryPopupContext : PopupContext {

  abstract val referenceComponent: Component

  override fun preparePopup(builder: ComponentPopupBuilder) {
    builder.setRequestFocus(false) // otherwise, it won't be possible to continue interacting with lookup/SE
    builder.setCancelOnClickOutside(false) // otherwise, selecting lookup items by mouse, or resizing SE would close the popup
  }

  override fun setUpPopup(popup: AbstractPopup, popupUI: DocumentationPopupUI) {
    val resized = popupUI.useStoredSize()
    popupUI.updatePopup {
      if (!resized.get()) {
        resizePopup(popup)
      }
      // don't reposition the popup, it sticks to the reference component
    }
  }

  override fun showPopup(popup: AbstractPopup) {
    val component = referenceComponent
    repositionPopup(popup, component) // also shows the popup
    installPositionAdjuster(popup, component) // move popup when reference component changes its width
    // this is needed so that unfocused popup could still become focused
    popup.popupWindow.focusableWindowState = true
  }
}

private fun installPositionAdjuster(popup: JBPopup, anchor: Component) {
  val listener = object : ComponentAdapter() {
    override fun componentResized(e: ComponentEvent) {
      repositionPopup(popup, anchor)
    }
  }
  anchor.addComponentListener(listener)
  Disposer.register(popup) {
    anchor.removeComponentListener(listener)
  }
}

private fun repositionPopup(popup: JBPopup, anchor: Component) {
  PopupPositionManager.PositionAdjuster(anchor).adjust(popup, PopupPositionManager.Position.RIGHT, PopupPositionManager.Position.LEFT)
}
