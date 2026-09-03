package dev.elide.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.compile.JavaCompile;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ElideGradlePlugin implements Plugin<Project> {
    // Plugin ID for Gradle's built-in Java support.
    private static final String javaPluginId = "java";

    // Binary name for Elide.
    private static final String elideBinName = "elide";

    // Extension name for configuring Elide's Gradle plugin.
    private static final String elideExtensionName = elideBinName;

    // Project where the plugin is installed.
    private final Project activeProject;

    @Inject
    public ElideGradlePlugin(Project project) {
        this.activeProject = project;
    }

    // Configure a Java compile task to use Elide instead of the standard compiler API.
    private Task configureJavaCompileToUseElide(Path elide, Project project, JavaCompile task, ElideExtension ext) {
        project.getLogger().info(
                "Installing Elide's javac support for task '{}' within project '{}'",
                task.getName(),
                activeProject.getName());

        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path resolvedElide = null;
        if (ext.enableShim()) {
            var javaHomeShim = javaHome
                    .resolve("bin")
                    .resolve("elide-javac");

            if (!Files.exists(javaHomeShim)) {
                if (Files.isWritable(javaHomeShim.getParent())) {
                    // we can create the shim; if we are configured to do, we should do so now.
                    try(var writer = Files.newBufferedWriter(javaHomeShim)) {
                        // write the shim to the file
                        writer.write("#!/bin/sh\n");
                        writer.write("exec " + resolvePathToElide().toAbsolutePath() + " javac -- \"$@\"\n");
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to write Elide javac shim", e);
                    }
                    if (!javaHomeShim.toFile().setExecutable(true)) {
                        project.getLogger().warn("Elide's javac shim was created at '{}', but could not be made " +
                                "executable. Falling back to stock javac.", javaHomeShim.toAbsolutePath());
                        return task;
                    }
                    resolvedElide = javaHomeShim;
                } else {
                    // we can't write the shim, and it's not there, and we need it, so we should warn and fall back.
                    project.getLogger().warn("Elide's javac shim was not found at '{}'; falling back to stock javac.",
                            javaHomeShim.toAbsolutePath());
                    return task;
                }
            } else if (!Files.isExecutable(javaHomeShim)) {
                // the shim is there, but it's not executable.
                var result = javaHomeShim.toFile().setExecutable(true);
                if (result) {
                    // we're good to go
                    resolvedElide = javaHomeShim;
                } else {
                    // we can't write the shim, and it's not there, and we need it, so we should warn and fall back.
                    project.getLogger().warn("Elide's javac shim isn't executable, and can't be made executable Please " +
                                    "run 'chmod +x {}' to fix this.",
                            javaHomeShim.toAbsolutePath());
                    return task;
                }
            } else {
                // the shim is there and executable, so we can use it.
                resolvedElide = javaHomeShim;
            }
        } else {
            // if the shim is not enabled, we use the Elide binary directly.
            resolvedElide = elide;
        }
        if (resolvedElide == null) {
            project.getLogger().error("Failed to resolve Elide javac shim, and Java Home is not writable.");
            throw new RuntimeException("Failed to resolve Elide javac shim; is your Java Home writable?");
        }

        Objects.requireNonNull(resolvedElide);
        var pathAsString = resolvedElide.toString();
        var options = task.getOptions();
        var forkOptions = options.getForkOptions();
        options.setFork(true);
        forkOptions.setExecutable(pathAsString);

        // if the shim is not enabled, we need to pass the `javac` flag and the separator (`--`) so that the binary can
        // resolve the arguments correctly.
        if (!ext.enableShim()) {
            forkOptions.setJavaHome(javaHome.toFile());
            var allArgs = forkOptions.getJvmArgs();
            if (allArgs == null) {
                allArgs = Collections.emptyList();
            }
            var prefixed = new ArrayList<String>(allArgs.size() + 2);
            prefixed.add("javac");
            prefixed.add("--");
            prefixed.addAll(allArgs);
            forkOptions.setJvmArgs(prefixed);
        }
        return task;
    }

    // Install integration with Gradle's Java plugin; this prefers Elide's Java Compiler support.
    private Collection<Task> installJavacSupport(Path path, Project project) {
        var elideExtension = project.getExtensions().getByType(ElideExtension.class);
        var compileTasks = project.getTasks().withType(JavaCompile.class);
        project.getLogger().info(
                "Installing Elide's javac support for {} Gradle tasks (path: '{}')",
                compileTasks.size(),
                path.toString());

        return compileTasks.stream()
            .map(compileTask -> configureJavaCompileToUseElide(path, project, compileTask, elideExtension))
            .collect(Collectors.toList());
    }

    // Install integration with Gradle's Maven root support.
    private Collection<Task> installMavenDepsSupport(Project project, ElideExtension ext, boolean generateManifest) {
        var repos = project.getRepositories();
        var localDepsPath = ext.resolveLocalDepsPath();
        repos.mavenLocal(it -> {
            it.setName("elide");
            it.setUrl(URI.create("file://" + localDepsPath.toAbsolutePath()));
        });
        return Collections.emptyList();
    }

    // Resolve the path to use when invoking the Elide binary.
    private Path resolvePathToElide() {
        var path = System.getenv("PATH");
        var pathSplit = path.split(File.pathSeparator);
        var elideViaPath = Arrays.stream(pathSplit)
                .map(Paths::get)
                .map(p -> p.resolve(elideBinName))
                .filter(Files::exists)
                .filter(Files::isExecutable)
                .findFirst();

        // prefer elide on the user's path
        if (elideViaPath.isPresent()) {
            return elideViaPath.get().toAbsolutePath();
        }

        // try the user's home?
        var elideWithinHome = Paths.get(System.getProperty("user.home"))
                .resolve("elide")
                .resolve(elideBinName);
        if (Files.exists(elideWithinHome) && Files.isExecutable(elideWithinHome)) {
            return elideWithinHome.toAbsolutePath();
        }
        throw new RuntimeException("Failed to find `elide` on your PATH; is it installed?");

        // otherwise, we should resolve from the root project's layout. the plugin will download elide and install it
        // in `<rootProject>/build/elide-runtime`.
        //
        // var elideBuildRoot = activeProject.getRootProject().getLayout().getBuildDirectory().dir("elide-runtime");
        // return elideBuildRoot.get().dir("bin").file(elideBinName).getAsFile().toPath();
    }

    // Determine whether Elide's Maven installer integration is enabled.
    private boolean enableMavenInstaller(Project project, ElideExtension ext) {
        var disableInstallerProp = project.findProperty("elide.builder.maven.install.enable");
        if (disableInstallerProp != null) {
            return Boolean.parseBoolean(disableInstallerProp.toString());
        }
        return ext.getEnableInstall().get() && ext.getEnableMavenIntegration().get();
    }

    // Detect any extant or configured project manifest file. If one is present, `elide install` is run unconditionally.
    private boolean detectProjectManifest(Project project, ElideExtension ext) {
        return (
          (ext.getManifest().isPresent() && ext.getManifest().getAsFile().get().exists()) ||
          project.getLayout().getProjectDirectory().file("elide.pkl").getAsFile().exists()
        );
    }

    // Determine whether Elide's javac shim is enabled.
    private boolean enableJavacShim(Project project, ElideExtension ext) {
        var disableShimProp = project.findProperty("elide.builder.javac.enable");
        if (disableShimProp != null) {
            return Boolean.parseBoolean(disableShimProp.toString());
        }
        return ext.getEnableJavaCompiler().get();
    }

    // Call Elide in a subprocess at the provided path, and with the provided args; capture output and return it as a
    // string to the caller.
    private String callElideCaptured(Path path, String[] args) {
        var allArgs = new String[args.length + 1];
        allArgs[0] = path.toAbsolutePath().toString();
        var i = 1;
        for (var arg : args) {
            allArgs[i] = arg;
            i += 1;
        }

        var cwd = activeProject.getLayout().getProjectDirectory().getAsFile();
        var subproc = new ProcessBuilder().command(allArgs).directory(cwd);
        try {
            var proc = subproc.start();
            var builder = new StringBuilder();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    builder.append(line).append(System.lineSeparator());
                }
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    builder.append(line).append(System.lineSeparator());
                }
            }
            var exit = proc.waitFor();
            if (exit != 0) {
                // print output
                activeProject.getLogger().error("Elide process exited with code {}: {}", exit, builder);
                throw new RuntimeException("Elide failed with exit code " + exit);
            }
            return builder.toString().trim();
        } catch (InterruptedException ixr) {
            throw new RuntimeException("Failed to wait for Elide process", ixr);
        } catch (IOException ioe) {
            throw new RuntimeException("Failed to start Elide captured process", ioe);
        }
    }

    @SuppressWarnings({"deprecation", "UnstableApiUsage"})
    public void apply(Project project) {
        var elideResolved = resolvePathToElide();
        project.getLogger().debug("Elide resolved to '{}'", elideResolved);
        if (!project.getGradle().getStartParameter().isConfigurationCacheRequested()) {
            var versionPrinted = callElideCaptured(elideResolved, new String[]{"--version"});
            var version = versionPrinted.replace("\n", "");
            project.getLogger().lifecycle("Using Elide " + version);
        }

        var objectUtil = project.getObjects();
        var extension = new ElideExtension(project, objectUtil);
        project.getExtensions().add(elideExtensionName, extension);

        project.afterEvaluate(_ -> {
            var pluginManager = project.getPluginManager();
            var javaPluginActive = pluginManager.hasPlugin(javaPluginId);
            var javacSupportActive = enableJavacShim(project, extension);
            var mavenInstallerActive = enableMavenInstaller(project, extension);
            var hasProjectManifest = detectProjectManifest(project, extension);

            project.getLogger().info(
                    "Elide Java support: (pluginActive={}, javacSupport={})",
                    javaPluginActive,
                    javacSupportActive);

            Collection<Task> javacTasks = null;
            if (javaPluginActive && javacSupportActive) {
                javacTasks = installJavacSupport(elideResolved, project);
            }

            boolean mustGenerateManifest = false;  // @TODO implement
            boolean installerEnabled = extension.getEnableInstall().get();
            boolean shouldRunInstallWithOrWithoutMaven = installerEnabled;
            LinkedList<Task> allPrepTasks = new LinkedList<>();

            if (mavenInstallerActive) {
                // to enable integration with Maven dependency installation, we need to inject a local dependency root
                // path, and we need to run `elide install` before compilation runs. dependencies must also be gathered
                // if there is no project manifest file to read from. this comes first so that Gradle is configured to
                // be aware of our Maven dependencies before `elide install` is run.
                allPrepTasks.addAll(installMavenDepsSupport(project, extension, mustGenerateManifest));
                installerEnabled = true;
            }
            if (installerEnabled && hasProjectManifest) {
                // if a project manifest is present, we should run `elide install` regardless of other criteria, as it
                // may install non-JVM dependencies on the user's behalf.
                shouldRunInstallWithOrWithoutMaven = true;
            }
            if (shouldRunInstallWithOrWithoutMaven) {
                // add a precursor task to run `elide install`.
                allPrepTasks.add(project.getTasks().create(ElideTaskName.ELIDE_TASK_INSTALL, Task.class, task -> {
                    task.setGroup("Elide");
                    task.setDescription("Runs `elide install` to prepare the project for compilation.");
                    task.dependsOn(allPrepTasks.stream().filter(it -> it != task).collect(Collectors.toList()));
                    task.doLast(_ -> {
                        var start = System.currentTimeMillis();
                        project.getLogger().info("Running `elide install`");
                        var result = callElideCaptured(elideResolved, new String[]{"install"});
                        var end = System.currentTimeMillis();
                        project.getLogger().info(result);
                        project.getLogger().lifecycle("`elide install` completed in {}ms", (end - start));
                    });
                }));
            }
            if (!allPrepTasks.isEmpty() && javacTasks != null && !javacTasks.isEmpty()) {
                javacTasks.forEach(javacTask -> {
                    javacTask.dependsOn(allPrepTasks);
                });
            }
        });
    }
}
