// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.commit

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.actions.impl.OpenInEditorAction
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.DiffUtil
import com.intellij.internal.statistic.StructuredIdeActivity
import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangesViewManager
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.TreeActions
import com.intellij.util.ui.update.Activatable
import com.intellij.util.ui.update.UiNotifyConnector
import java.awt.event.MouseEvent
import javax.swing.JTree

class CommitSessionCounterUsagesCollector : CounterUsagesCollector() {
  companion object {
    val GROUP = EventLogGroup("commit.interactions", 1)

    val FILES_TOTAL = EventFields.Int("files_total")
    val FILES_INCLUDED = EventFields.Int("files_included")
    val UNVERSIONED_TOTAL = EventFields.Int("unversioned_total")
    val UNVERSIONED_INCLUDED = EventFields.Int("unversioned_included")

    val SESSION = GROUP.registerIdeActivity("session",
                                            startEventAdditionalFields = arrayOf(FILES_TOTAL, FILES_INCLUDED,
                                                                                 UNVERSIONED_TOTAL, UNVERSIONED_INCLUDED),
                                            finishEventAdditionalFields = arrayOf())

    val COMMIT_MESSAGE_TYPING = GROUP.registerEvent("typing")
    val EXCLUDE_FILE = GROUP.registerEvent("exclude.file", EventFields.InputEventByAnAction, EventFields.InputEventByMouseEvent)
    val INCLUDE_FILE = GROUP.registerEvent("include.file", EventFields.InputEventByAnAction, EventFields.InputEventByMouseEvent)
    val SELECT_FILE = GROUP.registerEvent("select.item", EventFields.InputEventByAnAction, EventFields.InputEventByMouseEvent)
    val SHOW_DIFF = GROUP.registerEvent("show.diff")
    val CLOSE_DIFF = GROUP.registerEvent("close.diff")
    val JUMP_TO_SOURCE = GROUP.registerEvent("jump.to.source", EventFields.InputEventByAnAction)
    val COMMIT = GROUP.registerEvent("commit", FILES_INCLUDED, UNVERSIONED_INCLUDED)
    val COMMIT_AND_PUSH = GROUP.registerEvent("commit.and.push", FILES_INCLUDED, UNVERSIONED_INCLUDED)
  }

  override fun getGroup(): EventLogGroup = GROUP
}

@Service(Service.Level.PROJECT)
class CommitSessionCollector(val project: Project) {
  companion object {
    @JvmStatic
    fun getInstance(project: Project): CommitSessionCollector = project.service()
  }

  private var activity: StructuredIdeActivity? = null

  private fun shouldTrackEvents(): Boolean {
    val mode = CommitModeManager.getInstance(project).getCurrentCommitMode()
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ChangesViewContentManager.COMMIT_TOOLWINDOW_ID)
    return toolWindow != null && mode is CommitMode.NonModalCommitMode && !mode.isToggleMode
  }

  private fun updateToolWindowState() {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ChangesViewContentManager.COMMIT_TOOLWINDOW_ID)
    val isSessionActive = shouldTrackEvents() && toolWindow?.isVisible == true
    if (!isSessionActive) {
      finishActivity()
    }
    else if (activity == null) {
      val changesViewManager = ChangesViewManager.getInstance(project) as ChangesViewManager
      val commitUi = changesViewManager.commitWorkflowHandler?.ui ?: return
      activity = CommitSessionCounterUsagesCollector.SESSION.started(project) {
        listOf(
          CommitSessionCounterUsagesCollector.FILES_TOTAL.with(commitUi.getDisplayedChanges().size),
          CommitSessionCounterUsagesCollector.FILES_INCLUDED.with(commitUi.getIncludedChanges().size),
          CommitSessionCounterUsagesCollector.UNVERSIONED_TOTAL.with(commitUi.getDisplayedUnversionedFiles().size),
          CommitSessionCounterUsagesCollector.UNVERSIONED_INCLUDED.with(commitUi.getIncludedUnversionedFiles().size)
        )
      }
    }
  }

  private fun finishActivity() {
    activity?.finished()
    activity = null
  }

  fun logFileSelected(event: MouseEvent) {
    if (!shouldTrackEvents()) return
    CommitSessionCounterUsagesCollector.SELECT_FILE.log(project, null, event)
  }

  fun logFileSelected(event: AnActionEvent) {
    if (!shouldTrackEvents()) return
    CommitSessionCounterUsagesCollector.SELECT_FILE.log(project, event, null)
  }

  fun logCommitMessageTyped() {
    if (!shouldTrackEvents()) return
    CommitSessionCounterUsagesCollector.COMMIT_MESSAGE_TYPING.log(project)
  }

  fun logInclusionToggle(excluded: Boolean, event: AnActionEvent) {
    if (!shouldTrackEvents()) return
    if (excluded) {
      CommitSessionCounterUsagesCollector.EXCLUDE_FILE.log(project, event, null)
    }
    else {
      CommitSessionCounterUsagesCollector.INCLUDE_FILE.log(project, event, null)
    }
  }

  fun logInclusionToggle(excluded: Boolean, event: MouseEvent) {
    if (!shouldTrackEvents()) return
    if (excluded) {
      CommitSessionCounterUsagesCollector.EXCLUDE_FILE.log(project, null, event)
    }
    else {
      CommitSessionCounterUsagesCollector.INCLUDE_FILE.log(project, null, event)
    }
  }

  fun logCommit(executorId: String?, includedChanges: Int, includedUnversioned: Int) {
    if (!shouldTrackEvents()) return
    if (executorId == "Git.Commit.And.Push.Executor") {
      CommitSessionCounterUsagesCollector.COMMIT_AND_PUSH.log(project, includedChanges, includedUnversioned)
    }
    else {
      CommitSessionCounterUsagesCollector.COMMIT.log(project, includedChanges, includedUnversioned)
    }

    finishActivity()
    updateToolWindowState()
  }

  fun logDiffViewer(isShown: Boolean) {
    if (!shouldTrackEvents()) return
    if (isShown) {
      CommitSessionCounterUsagesCollector.SHOW_DIFF.log(project)
    }
    else {
      CommitSessionCounterUsagesCollector.CLOSE_DIFF.log(project)
    }
  }

  fun logJumpToSource(event: AnActionEvent) {
    if (!shouldTrackEvents()) return
    CommitSessionCounterUsagesCollector.JUMP_TO_SOURCE.log(project, event)
  }


  internal class MyToolWindowManagerListener(val project: Project) : ToolWindowManagerListener {
    override fun stateChanged(toolWindowManager: ToolWindowManager) {
      getInstance(project).updateToolWindowState()
    }
  }

  internal class MyDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
      val project = context.project ?: return
      if (!DiffUtil.isUserDataFlagSet(DiffUserDataKeysEx.LAST_REVISION_WITH_LOCAL, context)) return
      if (request is MessageDiffRequest) return

      UiNotifyConnector(viewer.component, object : Activatable {
        override fun showNotify() {
          getInstance(project).logDiffViewer(true)
        }

        override fun hideNotify() {
          getInstance(project).logDiffViewer(false)
        }
      }, false)
    }
  }

  internal class MyAnActionListener : AnActionListener {
    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
      if (!result.isPerformed) return
      val project = event.project ?: return
      if (action is OpenInEditorAction) {
        val context = event.getData(DiffDataKeys.DIFF_CONTEXT) ?: return
        if (!DiffUtil.isUserDataFlagSet(DiffUserDataKeysEx.LAST_REVISION_WITH_LOCAL, context)) return
        getInstance(project).logJumpToSource(event)
      }
      if (action is TreeActions) {
        val component = event.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) as? JTree ?: return
        if (component.getClientProperty(ChangesTree.LOG_COMMIT_SESSION_EVENTS) != true) return
        getInstance(project).logFileSelected(event)
      }
    }
  }
}
