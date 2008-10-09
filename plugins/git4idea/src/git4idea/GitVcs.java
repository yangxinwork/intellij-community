package git4idea;
/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 *
 * Copyright 2007 Decentrix Inc
 * Copyright 2007 Aspiro AS
 * Copyright 2008 MQSoftware
 * Authors: gevession, Erlend Simonsen & Mark Scott
 *
 * This code was originally derived from the MKS & Mercurial IDEA VCS plugins
 */

import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.diff.DiffProvider;
import com.intellij.openapi.vcs.diff.RevisionSelector;
import com.intellij.openapi.vcs.history.VcsHistoryProvider;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import com.intellij.openapi.vcs.update.UpdateEnvironment;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.annotate.GitAnnotationProvider;
import git4idea.changes.GitChangeProvider;
import git4idea.checkin.GitCheckinEnvironment;
import git4idea.config.GitVcsConfigurable;
import git4idea.config.GitVcsSettings;
import git4idea.diff.GitDiffProvider;
import git4idea.history.GitHistoryProvider;
import git4idea.i18n.GitBundle;
import git4idea.rollback.GitRollbackEnvironment;
import git4idea.update.GitUpdateEnvironment;
import git4idea.vfs.GitVFSListener;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Date;
import java.util.List;

/**
 * Git VCS implementation
 */
public class GitVcs extends AbstractVcs {
  @NonNls public static final String NAME = "Git";
  private static final String UNUNDEXED_FILES_CHANGELIST_NAME = GitBundle.getString("unindexed.files.changlelist.name");
  private final ChangeProvider changeProvider;
  private final VcsShowConfirmationOption addConfirmation;
  private final VcsShowConfirmationOption delConfirmation;

  private final CheckinEnvironment checkinEnvironment;
  private final RollbackEnvironment rollbackEnvironment;
  private final GitUpdateEnvironment updateEnvironment;

  private final GitAnnotationProvider annotationProvider;
  private final DiffProvider diffProvider;
  private final VcsHistoryProvider historyProvider;

  private final ProjectLevelVcsManager vcsManager;
  private final GitVcsSettings settings;
  private final EditorColorsScheme editorColorsScheme;
  private final Configurable configurable;
  private final RevisionSelector revSelector;
  /**
   * a vfs listener
   */
  private GitVFSListener myVFSListener;

  public static GitVcs getInstance(@NotNull Project project) {
    return (GitVcs)ProjectLevelVcsManager.getInstance(project).findVcsByName(NAME);
  }

  public GitVcs(@NotNull Project project,
                @NotNull final GitChangeProvider gitChangeProvider,
                @NotNull final GitCheckinEnvironment gitCheckinEnvironment,
                @NotNull final ProjectLevelVcsManager gitVcsManager,
                @NotNull final GitAnnotationProvider gitAnnotationProvider,
                @NotNull final GitDiffProvider gitDiffProvider,
                @NotNull final GitHistoryProvider gitHistoryProvider,
                @NotNull final GitRollbackEnvironment gitRollbackEnvironment,
                @NotNull final GitVcsSettings gitSettings) {
    super(project);

    vcsManager = gitVcsManager;
    settings = gitSettings;
    addConfirmation = gitVcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, this);
    delConfirmation = gitVcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.REMOVE, this);
    changeProvider = gitChangeProvider;
    checkinEnvironment = gitCheckinEnvironment;
    annotationProvider = gitAnnotationProvider;
    diffProvider = gitDiffProvider;
    editorColorsScheme = EditorColorsManager.getInstance().getGlobalScheme();
    historyProvider = gitHistoryProvider;
    rollbackEnvironment = gitRollbackEnvironment;
    revSelector = new GitRevisionSelector();
    configurable = new GitVcsConfigurable(settings, myProject);
    updateEnvironment = new GitUpdateEnvironment(myProject, settings, configurable);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  @NotNull
  public CheckinEnvironment getCheckinEnvironment() {
    return checkinEnvironment;
  }

  @Override
  @NotNull
  public RollbackEnvironment getRollbackEnvironment() {
    return rollbackEnvironment;
  }

  @Override
  @NotNull
  public VcsHistoryProvider getVcsHistoryProvider() {
    return historyProvider;
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return NAME;
  }

  @Override
  @Nullable
  public UpdateEnvironment getUpdateEnvironment() {
    return updateEnvironment;
  }

  @Override
  @Nullable
  public UpdateEnvironment getStatusEnvironment() {
    return getUpdateEnvironment();
  }

  @Override
  @NotNull
  public GitAnnotationProvider getAnnotationProvider() {
    return annotationProvider;
  }

  @Override
  @NotNull
  public DiffProvider getDiffProvider() {
    return diffProvider;
  }

  @Override
  @Nullable
  public RevisionSelector getRevisionSelector() {
    return revSelector;
  }

  @Override
  public UpdateEnvironment getIntegrateEnvironment() {
    return getUpdateEnvironment();
  }

  @SuppressWarnings({"deprecation"})
  @Override
  @Nullable
  public VcsRevisionNumber parseRevisionNumber(String revision) {
    if (revision == null || revision.length() == 0) return null;

    if (revision.length() > 40) {    // date & revision-id encoded string
      String datestr = revision.substring(0, revision.indexOf("["));
      String rev = revision.substring(revision.indexOf("[") + 1, 40);
      Date d = new Date(Date.parse(datestr));
      return new GitRevisionNumber(rev, d);
    }

    return new GitRevisionNumber(revision);
  }

  @Override
  public boolean isVersionedDirectory(VirtualFile dir) {
    final VirtualFile versionFile = dir.findChild(".git");
    return versionFile != null && versionFile.isDirectory();
  }

  @Override
  public void activate() {
    super.activate();
    if (myVFSListener == null) {
      myVFSListener = new GitVFSListener(myProject, this);
    }
  }

  @Override
  public void deactivate() {
    if (myVFSListener != null) {
      myVFSListener.dispose();
      myVFSListener = null;
    }
    super.deactivate();
  }

  @NotNull
  public VcsShowConfirmationOption getAddConfirmation() {
    return addConfirmation;
  }

  @NotNull
  public VcsShowConfirmationOption getDeleteConfirmation() {
    return delConfirmation;
  }

  @NotNull
  @Override
  public synchronized Configurable getConfigurable() {
    return configurable;
  }

  @Nullable
  public ChangeProvider getChangeProvider() {
    return changeProvider;
  }

  public void showErrors(@NotNull List<VcsException> list, @NotNull String action) {
    if (list.size() > 0) {
      StringBuffer buffer = new StringBuffer();
      buffer.append("\n");
      buffer.append(GitBundle.message("error.list.title", action));
      for (final VcsException exception : list) {
        buffer.append("\n");
        buffer.append(exception.getMessage());
      }
      String msg = buffer.toString();
      showMessage(msg, CodeInsightColors.ERRORS_ATTRIBUTES);
      Messages.showErrorDialog(myProject, msg, GitBundle.getString("error.dialog.title"));
    }
  }

  public void showMessages(@NotNull String message) {
    if (message.length() == 0) return;
    showMessage(message, HighlighterColors.TEXT);
  }

  @NotNull
  public GitVcsSettings getSettings() {
    return settings;
  }

  private void showMessage(@NotNull String message, final TextAttributesKey text) {
    vcsManager.addMessageToConsoleWindow(message, editorColorsScheme.getAttributes(text));
  }
}