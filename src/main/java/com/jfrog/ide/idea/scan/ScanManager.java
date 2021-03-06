package com.jfrog.ide.idea.scan;

import com.google.common.collect.Sets;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.jfrog.ide.common.log.ProgressIndicator;
import com.jfrog.ide.common.scan.ComponentPrefix;
import com.jfrog.ide.common.scan.ScanManagerBase;
import com.jfrog.ide.common.utils.ProjectsMap;
import com.jfrog.ide.idea.configuration.GlobalSettings;
import com.jfrog.ide.idea.events.ApplicationEvents;
import com.jfrog.ide.idea.events.ProjectEvents;
import com.jfrog.ide.idea.log.Logger;
import com.jfrog.ide.idea.log.ProgressIndicatorImpl;
import com.jfrog.ide.idea.ui.filters.FilterManagerService;
import com.jfrog.ide.idea.ui.issues.IssuesTree;
import com.jfrog.ide.idea.ui.licenses.LicensesTree;
import com.jfrog.ide.idea.utils.Utils;
import com.jfrog.xray.client.services.summary.Components;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfrog.build.extractor.scan.DependenciesTree;
import org.jfrog.build.extractor.scan.License;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by romang on 4/26/17.
 */
public abstract class ScanManager extends ScanManagerBase {

    private static final Path HOME_PATH = Paths.get(System.getProperty("user.home"), ".jfrog-idea-plugin");
    private Project mainProject;
    Project project;

    // Lock to prevent multiple simultaneous scans
    private AtomicBoolean scanInProgress = new AtomicBoolean(false);

    /**
     * @param mainProject - Currently opened IntelliJ project. We'll use this project to retrieve project based services
     *                    like {@link FilterManagerService}, {@link LicensesTree} and {@link IssuesTree}.
     * @param project     - Current working project.
     * @param prefix      - Components prefix for xray scan, e.g. gav:// or npm://.
     */
    ScanManager(@NotNull Project mainProject, @NotNull Project project, ComponentPrefix prefix) throws IOException {
        super(HOME_PATH.resolve("cache"), project.getName(), Logger.getInstance(mainProject), GlobalSettings.getInstance().getXrayConfig(), prefix);
        this.mainProject = mainProject;
        this.project = project;
        Files.createDirectories(HOME_PATH);
        registerOnChangeHandlers();
    }

    /**
     * Refresh project dependencies.
     */
    protected abstract void refreshDependencies(ExternalProjectRefreshCallback cbk, @Nullable Collection<DataNode<LibraryDependencyData>> libraryDependencies);

    /**
     * Collect and return {@link Components} to be scanned by JFrog Xray.
     * Implementation should be project type specific.
     */
    protected abstract void buildTree(@Nullable DataNode<ProjectData> externalProject) throws IOException;

    /**
     * Scan and update dependency components.
     */
    private void scanAndUpdate(boolean quickScan, ProgressIndicator indicator, @Nullable Collection<DataNode<LibraryDependencyData>> libraryDependencies) {
        // Don't scan if Xray is not configured
        if (!GlobalSettings.getInstance().areCredentialsSet()) {
            getLog().error("Xray server is not configured.");
            return;
        }
        // Prevent multiple simultaneous scans
        if (!scanInProgress.compareAndSet(false, true)) {
            if (!quickScan) {
                getLog().info("Scan already in progress");
            }
            return;
        }
        try {
            // Refresh dependencies -> Collect -> Scan and store to cache -> Update view
            refreshDependencies(getRefreshDependenciesCbk(quickScan, indicator), libraryDependencies);
        } finally {
            scanInProgress.set(false);
        }
    }

    /**
     * Launch async dependency scan.
     */
    void asyncScanAndUpdateResults(boolean quickScan, @Nullable Collection<DataNode<LibraryDependencyData>> libraryDependencies) {
        if (DumbService.isDumb(mainProject)) { // If intellij is still indexing the project
            return;
        }
        Task.Backgroundable scanAndUpdateTask = new Task.Backgroundable(null, "Xray: Scanning for Vulnerabilities...") {
            @Override
            public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                if (project.isDisposed()) {
                    return;
                }
                scanAndUpdate(quickScan, new ProgressIndicatorImpl(indicator), libraryDependencies);
                indicator.finishNonCancelableSection();
            }
        };
        // The progress manager is only good for foreground threads.
        if (SwingUtilities.isEventDispatchThread()) {
            ProgressManager.getInstance().run(scanAndUpdateTask);
        } else {
            // Run the scan task when the thread is in the foreground.
            ApplicationManager.getApplication().invokeLater(() -> ProgressManager.getInstance().run(scanAndUpdateTask));
        }
    }

    /**
     * Returns all project modules locations as Paths.
     * Other scanners such as npm will use this paths in order to find modules.
     *
     * @return all project modules locations as Paths
     */
    public Set<Path> getProjectPaths() {
        return Sets.newHashSet(Utils.getProjectBasePath(project));
    }

    /**
     * Launch async dependency scan.
     */
    void asyncScanAndUpdateResults() {
        asyncScanAndUpdateResults(true, null);
    }

    private ExternalProjectRefreshCallback getRefreshDependenciesCbk(boolean quickScan, ProgressIndicator indicator) {
        return new ExternalProjectRefreshCallback() {
            @Override
            public void onSuccess(@Nullable DataNode<ProjectData> externalProject) {
                try {
                    buildTree(externalProject);
                    scanAndCacheArtifacts(indicator, quickScan);
                    addXrayInfoToTree(getScanResults());
                    setScanResults();
                } catch (ProcessCanceledException e) {
                    getLog().info("Xray scan was canceled");
                } catch (Exception e) {
                    getLog().error("", e);
                }
            }

            @Override
            public void onFailure(@NotNull String errorMessage, @Nullable String errorDetails) {
                getLog().error(StringUtils.defaultIfEmpty(errorDetails, errorMessage));
            }
        };
    }

    private void registerOnChangeHandlers() {
        MessageBusConnection busConnection = ApplicationManager.getApplication().getMessageBus().connect();
        busConnection.subscribe(ApplicationEvents.ON_CONFIGURATION_DETAILS_CHANGE, this::asyncScanAndUpdateResults);
    }

    /**
     * @return all licenses available from the current scan results.
     */
    public Set<License> getAllLicenses() {
        Set<License> allLicenses = Sets.newHashSet();
        if (getScanResults() == null) {
            return allLicenses;
        }
        DependenciesTree node = (DependenciesTree) getScanResults().getRoot();
        collectAllLicenses(node, allLicenses);
        return allLicenses;
    }

    private void collectAllLicenses(DependenciesTree node, Set<License> allLicenses) {
        allLicenses.addAll(node.getLicenses());
        node.getChildren().forEach(child -> collectAllLicenses(child, allLicenses));
    }

    /**
     * filter scan components tree model according to the user filters and sort the issues tree.
     */
    private void setScanResults() {
        DependenciesTree scanResults = getScanResults();
        if (scanResults == null) {
            return;
        }
        if (!scanResults.isLeaf()) {
            addFilterManagerLicenses(FilterManagerService.getInstance(mainProject));
        }
        ProjectsMap.ProjectKey projectKey = ProjectsMap.createKey(getProjectName(),
                scanResults.getGeneralInfo());
        MessageBus projectMessageBus = mainProject.getMessageBus();

        IssuesTree issuesTree = IssuesTree.getInstance(mainProject);
        issuesTree.addScanResults(getProjectName(), scanResults);
        projectMessageBus.syncPublisher(ProjectEvents.ON_SCAN_PROJECT_ISSUES_CHANGE).update(projectKey);

        LicensesTree licensesTree = LicensesTree.getInstance(mainProject);
        licensesTree.addScanResults(getProjectName(), scanResults);
        projectMessageBus.syncPublisher(ProjectEvents.ON_SCAN_PROJECT_LICENSES_CHANGE).update(projectKey);
    }

    @Override
    protected void checkCanceled() {
        if (project.isOpen()) {
            // The project is closed if we are in test mode.
            // In tests we can't check if the user canceled the scan, since we don't have the ProgressManager service.
            ProgressManager.checkCanceled();
        }
    }

    boolean isScanInProgress() {
        return this.scanInProgress.get();
    }

    public String getProjectPath() {
        return project.getBasePath();
    }

    /**
     * Subscribe ScanManager for VFS-change events.
     * Perform dependencies scan and update tree after the provided file has changed.
     * @param fileName - file to track for changes.
     */
    protected void subscribeLaunchDependencyScanOnFileChangedEvents(String fileName) {
        String fileToSubscribe = Paths.get(Utils.getProjectBasePath(project).toString(), fileName).toString();

        // Register for file change event of go.sum file.
        mainProject.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    String filePath = event.getPath();
                    if (StringUtils.equals(filePath, fileToSubscribe)) {
                        asyncScanAndUpdateResults();
                    }
                }
            }
        });
    }
}
