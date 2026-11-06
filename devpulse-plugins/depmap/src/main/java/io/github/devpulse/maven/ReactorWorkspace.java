package io.github.devpulse.maven;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceRepository;

final class ReactorWorkspace implements MavenWorkspaceReader {
    private final WorkspaceRepository repository = new WorkspaceRepository("depmap-reactor");
    private final Map<String, MavenProject> projects = new LinkedHashMap<>();

    ReactorWorkspace(List<MavenProject> projects) {
        for (var project : projects) {
            this.projects.put(project.getGroupId() + ":" + project.getArtifactId()
                    + ":" + project.getVersion(), project);
        }
    }

    private MavenProject project(Artifact artifact) {
        return projects.get(artifact.getGroupId() + ":" + artifact.getArtifactId()
                + ":" + artifact.getVersion());
    }

    @Override
    public WorkspaceRepository getRepository() {
        return repository;
    }

    @Override
    public Model findModel(Artifact artifact) {
        var project = project(artifact);
        return project == null ? null : project.getModel();
    }

    @Override
    public File findArtifact(Artifact artifact) {
        var project = project(artifact);
        if (project == null) {
            return null;
        }
        if ("pom".equals(artifact.getExtension()) && artifact.getClassifier().isEmpty()) {
            return project.getFile();
        }
        var candidates = new java.util.ArrayList<>(project.getAttachedArtifacts());
        candidates.add(project.getArtifact());
        for (var candidate : candidates) {
            var converted = RepositoryUtils.toArtifact(candidate);
            if (artifact.getExtension().equals(converted.getExtension())
                    && artifact.getClassifier().equals(converted.getClassifier())
                    && converted.getFile() != null && converted.getFile().isFile()) {
                return converted.getFile();
            }
        }
        return null;
    }

    @Override
    public List<String> findVersions(Artifact artifact) {
        return projects.values().stream()
                .filter(p -> p.getGroupId().equals(artifact.getGroupId())
                        && p.getArtifactId().equals(artifact.getArtifactId()))
                .map(MavenProject::getVersion).distinct().sorted().toList();
    }
}
