package ru.ventra.javaflow.maven;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.javaflow.bytecode.transformation.asm.AsmClassTransformer;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 *
 * @author Alexey Andreev
 */
@Mojo(name = "transform", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class TransformMojo extends AbstractMojo {
    private static Set<String> compileScopes = new HashSet<String>(Arrays.asList(
            Artifact.SCOPE_COMPILE, Artifact.SCOPE_PROVIDED, Artifact.SCOPE_SYSTEM));

    @Component
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File classFiles;

    public void setProject(MavenProject project) {
        this.project = project;
    }

    public void setClassFiles(File classFiles) {
        this.classFiles = classFiles;
    }

    @Override
    public void execute() throws MojoExecutionException {
        Log log = getLog();
        try {
            List<File> classFilesToTransform = getClassFilesToTransform();
            if (classFilesToTransform == null) {
                log.info("No workflow metainformation found. Skipping bytecode transformation");
                return;
            }
            ClassLoader classLoader = prepareClassLoader();
            for (File classFile : classFilesToTransform) {
                transformClassFile(classLoader, classFile);
            }
        } catch (RuntimeException e) {
            throw new MojoExecutionException("Unexpected error occured", e);
        }
    }

    private List<File> getClassFilesToTransform() throws MojoExecutionException {
        File metaFile = new File(classFiles, "META-INF/workflow");
        if (!metaFile.exists()) {
            return null;
        }
        String[] classNames;
        InputStream input = null;
        try {
            input = new FileInputStream(metaFile);
            classNames = IOUtils.toString(input, "UTF-8").split("\\r\\n|\\r|\\n");
        } catch (IOException e) {
            throw new MojoExecutionException("Error reading meta information", e);
        } finally {
            IOUtils.closeQuietly(input);
        }
        List<File> files = new ArrayList<File>();
        for (String className : classNames) {
            File file = new File(classFiles, className.replace('.', '/') + ".class");
            if (!file.exists()) {
                throw new MojoExecutionException("Class " + className + " is specified " +
                        "to be transformed but no corresponding class file found (" +
                        file.getPath() + ")");
            }
            files.add(file);
            File parent = file.getParentFile();
            final String prefix = file.getName()
                    .substring(0, file.getName().length() - ".class".length()) + "$";
            File[] innerFiles = parent.listFiles(new FileFilter() {
                @Override
                public boolean accept(File pathname) {
                    return pathname.getName().endsWith(".class") &&
                            pathname.getName().startsWith(prefix);
                }
            });
            files.addAll(Arrays.asList(innerFiles));
        }
        return files;
    }

    private ClassLoader prepareClassLoader() throws MojoExecutionException {
        try {
            Log log = getLog();
            log.info("Preparing classpath for bytecode transformation");
            List<URL> urls = new ArrayList<URL>();
            StringBuilder classpath = new StringBuilder();
            for (Artifact artifact : project.getArtifacts()) {
                if (!compileScopes.contains(artifact.getScope())) {
                    continue;
                }
                File file = artifact.getFile();
                if (classpath.length() > 0) {
                    classpath.append(':');
                }
                classpath.append(file.getPath());
                    urls.add(file.toURI().toURL());
            }
            log.info("Using the following classpath for bytecode transformation: " + classpath);
            urls.add(classFiles.toURI().toURL());
            return new URLClassLoader(urls.toArray(new URL[urls.size()]));
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Error gathering classpath information", e);
        }
    }

    private void transformClassFile(ClassLoader classLoader, File file)
            throws MojoExecutionException {
        Log log = getLog();
        log.info("Transforming file " + file.getPath());

        InputStream input = null;
        byte[] bytecode;
        try {
            input = new FileInputStream(file);
            bytecode = IOUtils.toByteArray(input);
        } catch (IOException e) {
            throw new MojoExecutionException("Error reading bytecode from " +
                    file.getPath(), e);
        } finally {
            IOUtils.closeQuietly(input);
        }

        AsmClassTransformer transformer = new AsmClassTransformer();
        if (transformer.isTransformed(bytecode)) {
            log.info("Skipping file being already transformed: " + file.getPath());
            return;
        }
        transformer.setClassLoader(classLoader);
        bytecode = transformer.transform(bytecode);

        OutputStream output = null;
        try {
            output = new FileOutputStream(file);
            output.write(bytecode);
        } catch (IOException e) {
            throw new MojoExecutionException("Error writing bytecode to " +
                    file.getAbsolutePath(), e);
        } finally {
            IOUtils.closeQuietly(output);
        }
    }
}
