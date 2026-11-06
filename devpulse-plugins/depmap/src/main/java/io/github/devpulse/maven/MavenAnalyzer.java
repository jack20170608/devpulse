package io.github.devpulse.maven;

import io.github.devpulse.maven.DependencyReport.Coordinates;
import io.github.devpulse.maven.DependencyReport.Issue;
import io.github.devpulse.maven.DependencyReport.Node;
import io.github.devpulse.maven.DependencyReport.Tree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulationException;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.plugin.LifecycleBindingsInjector;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.internal.PluginDependenciesResolver;
import org.apache.maven.plugin.version.DefaultPluginVersionRequest;
import org.apache.maven.plugin.version.PluginVersionResolutionException;
import org.apache.maven.plugin.version.PluginVersionResolver;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.apache.maven.properties.internal.EnvironmentUtils;
import org.apache.maven.rtinfo.internal.DefaultRuntimeInformation;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactProperties;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;

public final class MavenAnalyzer {
    public static final String MAVEN_VERSION = new DefaultRuntimeInformation().getMavenVersion();

    public DependencyReport analyze(AnalysisOptions options)
            throws PlexusContainerException, ComponentLookupException,
            SettingsBuildingException, MavenExecutionRequestPopulationException {
        var issues = new ArrayList<Issue>();
        var modules = new ArrayList<DependencyReport.Module>();
        var configuration = new DefaultContainerConfiguration()
                .setName("devpulse-depmap")
                .setClassPathScanning(PlexusConstants.SCANNING_INDEX)
                .setAutoWiring(true);
        var container = new DefaultPlexusContainer(configuration);
        try {
            var system = new Properties();
            system.putAll(System.getProperties());
            EnvironmentUtils.addEnvVars(system);
            system.setProperty("maven.version", MAVEN_VERSION);
            var user = new Properties();
            user.putAll(options.properties());
            var settingsRequest = new DefaultSettingsBuildingRequest()
                    .setSystemProperties(system).setUserProperties(user);
            var settingsFile = options.settings() == null
                    ? Path.of(System.getProperty("user.home"), ".m2", "settings.xml")
                    : options.settings();
            settingsRequest.setUserSettingsFile(settingsFile.toFile());
            if (options.globalSettings() != null) {
                settingsRequest.setGlobalSettingsFile(options.globalSettings().toFile());
            }
            var settingsResult = container.lookup(SettingsBuilder.class).build(settingsRequest);
            for (var problem : settingsResult.getProblems()) {
                issues.add(new Issue("settings", "settings.xml",
                        "Settings problem at line " + problem.getLineNumber()
                                + " (" + problem.getSeverity() + ")."));
            }
            var settings = settingsResult.getEffectiveSettings();
            var decrypted = container.lookup(SettingsDecrypter.class)
                    .decrypt(new DefaultSettingsDecryptionRequest(settings));
            for (var ignored : decrypted.getProblems()) {
                issues.add(new Issue("settings", "credentials",
                        "Cannot decrypt repository credentials. Check settings-security.xml."));
            }
            settings.setServers(decrypted.getServers());
            settings.setProxies(decrypted.getProxies());
            if (!issues.isEmpty()) {
                return report(options, modules, issues);
            }

            var request = new DefaultMavenExecutionRequest();
            request.setPom(options.pom().toFile());
            request.setBaseDirectory(options.pom().getParent().toFile());
            request.setSystemProperties(system).setUserProperties(user);
            request.setStartTime(new Date()).setInteractiveMode(false);
            var populator = container.lookup(MavenExecutionRequestPopulator.class);
            populator.populateFromSettings(request, settings);
            var active = new LinkedHashSet<>(request.getActiveProfiles());
            var inactive = new LinkedHashSet<String>();
            for (var profile : options.profiles()) {
                if (profile.startsWith("!") || profile.startsWith("-")) {
                    inactive.add(profile.substring(1));
                } else {
                    active.add(profile);
                }
            }
            active.removeAll(inactive);
            request.setActiveProfiles(new ArrayList<>(active));
            request.setInactiveProfiles(new ArrayList<>(inactive));
            request.setOffline(options.offline() || settings.isOffline());
            request.setRecursive(options.recursive());
            var local = options.localRepository();
            if (local == null && user.containsKey("maven.repo.local")) {
                local = Path.of(user.getProperty("maven.repo.local")).toAbsolutePath().normalize();
            }
            if (local != null) {
                request.setLocalRepositoryPath(local.toFile());
            }
            populator.populateDefaults(request);
            var repositoryPath = request.getLocalRepositoryPath().toPath();
            if (Files.exists(repositoryPath) && !Files.isDirectory(repositoryPath)) {
                issues.add(new Issue("settings", "localRepository",
                        "The configured local repository is not a directory."));
                return report(options, modules, issues);
            }
            var session = container.lookup(DefaultRepositorySystemSessionFactory.class)
                    .newRepositorySession(request);
            session.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(false, false));
            session.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, false);
            var building = request.getProjectBuildingRequest()
                    .setRepositorySession(session)
                    .setResolveDependencies(false)
                    // Extension realms can execute third-party code during model building.
                    .setProcessPlugins(false);
            List<ProjectBuildingResult> results;
            try {
                results = container.lookup(ProjectBuilder.class)
                        .build(List.of(options.pom().toFile()), options.recursive(), building);
            } catch (ProjectBuildingException exception) {
                results = exception.getResults();
                if (results == null || results.isEmpty()) {
                    issues.add(new Issue("model", options.pom().toString(),
                            "Cannot build the effective Maven model. Check the POM, parents,"
                                    + " imported BOMs and repository settings."));
                    return report(options, modules, issues);
                }
            }
            var projects = results.stream().map(ProjectBuildingResult::getProject)
                    .filter(java.util.Objects::nonNull).toList();
            session.setWorkspaceReader(new ReactorWorkspace(projects));
            var dependencies = container.lookup(ProjectDependenciesResolver.class);
            var pluginDependencies = container.lookup(PluginDependenciesResolver.class);
            var versions = container.lookup(PluginVersionResolver.class);
            var lifecycles = container.lookup(LifecycleBindingsInjector.class);
            for (var result : results) {
                var modelIssues = new ArrayList<Issue>();
                for (var problem : result.getProblems()) {
                    if (problem.getSeverity() != ModelProblem.Severity.WARNING) {
                        var target = result.getPomFile() == null
                                ? options.pom().toString() : result.getPomFile().toString();
                        modelIssues.add(new Issue("model", target,
                                "Invalid or unresolved effective model at line "
                                        + problem.getLineNumber() + " ("
                                        + problem.getSeverity() + "). Check POM and repositories."));
                    }
                }
                var project = result.getProject();
                if (project == null) {
                    if (modelIssues.isEmpty()) {
                        modelIssues.add(new Issue("model", result.getProjectId(),
                                "No effective project model is available."));
                    }
                    issues.addAll(modelIssues);
                    continue;
                }
                modules.add(analyzeModule(project, session, dependencies,
                        pluginDependencies, versions, lifecycles, modelIssues));
            }
            for (var profile : options.profiles()) {
                if (active.contains(profile)
                        && projects.stream().noneMatch(p -> p.getInjectedProfileIds()
                                .values().stream().anyMatch(ids -> ids.contains(profile)))) {
                    issues.add(new Issue("profile", profile,
                            "Requested profile was not activated in any analyzed module."));
                }
            }
            return report(options, modules, issues);
        } finally {
            container.dispose();
        }
    }

    private DependencyReport.Module analyzeModule(
            MavenProject project, DefaultRepositorySystemSession session,
            ProjectDependenciesResolver dependencies,
            PluginDependenciesResolver pluginDependencies, PluginVersionResolver versions,
            LifecycleBindingsInjector lifecycles, List<Issue> issues) {
        var pom = project.getFile().toPath().toAbsolutePath().normalize();
        for (var directory = pom.getParent(); directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve(".mvn").resolve("extensions.xml"))) {
                issues.add(new Issue("unsupported", pom.toString(),
                        "Core extensions (.mvn/extensions.xml) are not supported or loaded."));
                break;
            }
        }
        var model = project.getModel();
        if (!model.getBuild().getExtensions().isEmpty()
                || project.getBuildPlugins().stream().anyMatch(Plugin::isExtensions)) {
            issues.add(new Issue("unsupported", project.getId(),
                    "Custom build extensions are not supported or loaded."));
        }
        var declared = new LinkedHashSet<String>();
        project.getBuildPlugins().forEach(plugin -> declared.add(plugin.getKey()));
        // Inject lifecycle defaults without constructing or loading plugin realms.
        lifecycles.injectLifecycleBindings(model, new DefaultModelBuildingRequest(),
                problem -> issues.add(new Issue("lifecycle", project.getId(),
                        "Cannot determine lifecycle bindings for packaging "
                                + project.getPackaging() + ".")));
        var tree = projectTree(project, session, dependencies);
        var plugins = new LinkedHashMap<String, Plugin>();
        var kinds = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var plugin : model.getBuild().getPlugins()) {
            plugins.put(plugin.getKey(), plugin.clone());
            kinds.computeIfAbsent(plugin.getKey(), _ -> new LinkedHashSet<>())
                    .add(declared.contains(plugin.getKey()) ? "build" : "lifecycle");
        }
        for (var report : project.getReportPlugins()) {
            var plugin = new Plugin();
            plugin.setGroupId(report.getGroupId());
            plugin.setArtifactId(report.getArtifactId());
            plugin.setVersion(report.getVersion());
            var key = "report:" + report.getKey();
            plugins.put(key, plugin);
            kinds.computeIfAbsent(key, _ -> new LinkedHashSet<>()).add("report");
        }
        var pluginReports = new ArrayList<DependencyReport.Plugin>();
        for (var entry : plugins.entrySet()) {
            var plugin = entry.getValue();
            var pluginIssues = new ArrayList<Issue>();
            DependencyNode root = null;
            try {
                if (plugin.getVersion() == null || plugin.getVersion().isBlank()) {
                    plugin.setVersion(versions.resolve(new DefaultPluginVersionRequest(
                            plugin, session, project.getRemotePluginRepositories())
                            .setPom(model)).getVersion());
                }
                var artifact = pluginDependencies.resolve(
                        plugin, project.getRemotePluginRepositories(), session);
                root = pluginDependencies.resolve(
                        plugin, artifact, null, project.getRemotePluginRepositories(), session);
            } catch (PluginVersionResolutionException | PluginResolutionException exception) {
                pluginIssues.add(new Issue("plugin", plugin.getId(),
                        "Cannot resolve plugin or its dependencies ("
                                + exception.getClass().getSimpleName()
                                + "). Check versions, settings and repository availability."));
                addArtifactIssues(exception, "plugin", pluginIssues);
                root = partialPluginTree(exception);
            }
            pluginReports.add(new DependencyReport.Plugin(
                    new Coordinates(plugin.getGroupId(), plugin.getArtifactId(), "maven-plugin",
                            "", plugin.getVersion() == null ? "" : plugin.getVersion()),
                    List.copyOf(kinds.get(entry.getKey())),
                    new Tree(pluginIssues.isEmpty(), copyTree(root), List.copyOf(pluginIssues))));
        }
        var complete = issues.isEmpty() && tree.complete()
                && pluginReports.stream().allMatch(p -> p.dependencies().complete());
        return new DependencyReport.Module(pom.toString(),
                coordinates(RepositoryUtils.toArtifact(project.getArtifact())),
                complete, tree, List.copyOf(pluginReports), List.copyOf(issues));
    }

    private Tree projectTree(MavenProject project, DefaultRepositorySystemSession session,
                             ProjectDependenciesResolver resolver) {
        var issues = new ArrayList<Issue>();
        org.apache.maven.project.DependencyResolutionResult result;
        try {
            result = resolver.resolve(new DefaultDependencyResolutionRequest(project, session)
                    .setResolutionFilter((_, _) -> false));
        } catch (DependencyResolutionException exception) {
            result = exception.getResult();
            issues.add(new Issue("project", project.getId(),
                    "Cannot collect all project dependency metadata. Check dependency versions,"
                            + " imported BOMs and repositories."));
        }
        if (result != null && !result.getCollectionErrors().isEmpty() && issues.isEmpty()) {
            issues.add(new Issue("project", project.getId(),
                    "Dependency collection returned incomplete metadata."));
        }
        if (result != null) {
            for (var error : result.getCollectionErrors()) {
                addArtifactIssues(error, "project", issues);
            }
        }
        return new Tree(issues.isEmpty(),
                result == null ? null : copyTree(result.getDependencyGraph()), List.copyOf(issues));
    }

    private static void addArtifactIssues(Throwable failure, String stage, List<Issue> issues) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ArtifactDescriptorException descriptor) {
                var issue = new Issue(stage,
                        coordinates(descriptor.getResult().getRequest().getArtifact()).toString(),
                        "Cannot read dependency POM metadata. Check version and repository access.");
                if (!issues.contains(issue)) {
                    issues.add(issue);
                }
            }
            if (cause instanceof ArtifactResolutionException resolution) {
                for (var result : resolution.getResults()) {
                    if (!result.isResolved()) {
                        var issue = new Issue(stage, coordinates(result.getRequest().getArtifact()).toString(),
                                "Cannot resolve artifact. Check version and repository access.");
                        if (!issues.contains(issue)) {
                            issues.add(issue);
                        }
                    }
                }
            }
            if (cause instanceof DependencyCollectionException collection) {
                for (var error : collection.getResult().getExceptions()) {
                    addArtifactIssues(error, stage, issues);
                }
            }
        }
    }

    private static DependencyNode partialPluginTree(Throwable exception) {
        for (var cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof org.eclipse.aether.resolution.DependencyResolutionException failure) {
                return failure.getResult().getRoot();
            }
            if (cause instanceof DependencyCollectionException failure) {
                return failure.getResult().getRoot();
            }
        }
        return null;
    }

    private static Node copyTree(DependencyNode root) {
        return root == null ? null : copyTree(root,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Node copyTree(DependencyNode node, Set<DependencyNode> ancestors) {
        var children = new ArrayList<Node>();
        if (ancestors.add(node)) {
            for (var child : node.getChildren()) {
                if (child.getData().get(ConflictResolver.NODE_DATA_WINNER) == null
                        && !ancestors.contains(child)) {
                    children.add(copyTree(child, ancestors));
                }
            }
            ancestors.remove(node);
        }
        var dependency = node.getDependency();
        return new Node(coordinates(node.getArtifact()),
                dependency == null ? "" : dependency.getScope(),
                dependency != null && dependency.isOptional(), List.copyOf(children));
    }

    private static Coordinates coordinates(Artifact artifact) {
        return new Coordinates(artifact.getGroupId(), artifact.getArtifactId(),
                artifact.getProperty(ArtifactProperties.TYPE, artifact.getExtension()),
                artifact.getClassifier(), artifact.getVersion());
    }

    private static DependencyReport report(AnalysisOptions options,
                                          List<DependencyReport.Module> modules,
                                          List<Issue> issues) {
        return new DependencyReport(1, MAVEN_VERSION, options.pom().toString(),
                issues.isEmpty() && !modules.isEmpty()
                        && modules.stream().allMatch(DependencyReport.Module::complete),
                List.copyOf(modules), List.copyOf(issues));
    }
}
