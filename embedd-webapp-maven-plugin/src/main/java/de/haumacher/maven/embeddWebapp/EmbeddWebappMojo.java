package de.haumacher.maven.embeddWebapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Goal to embed dependencies of type <code>war</code> to the
 * <code>META-INF/resources</code> folder of the <code>jar</code> file being built.
 */
@Mojo(name = "embeddWebapp", 
	defaultPhase = LifecyclePhase.GENERATE_RESOURCES, 
	requiresDependencyCollection = ResolutionScope.COMPILE, 
	requiresDependencyResolution = ResolutionScope.COMPILE)
public class EmbeddWebappMojo extends AbstractMojo {

	/**
	 * Output directory path where the <code>war</code> contents is being copied to.
	 */
	@Parameter(
		defaultValue = "${project.build.outputDirectory}/META-INF/resources", 
		property = "webappOutputDirectory", 
		required = true)
	private File outputDirectory;

    @Parameter(
    	defaultValue = "${project}", 
    	readonly = true, 
    	required = true )
    private MavenProject project;
    
	@Override
	public void execute() throws MojoExecutionException {
		byte[] buffer = new byte[8096];

		@SuppressWarnings("unchecked")
		Set<Artifact> artifacts = project.getArtifacts();
		for (Artifact artifact : artifacts) {
			String type = artifact.getType();
			if (! "war".equals(type)) {
				continue;
			}
			getLog().info("Embedding '" + artifact + "'");
			
			File file = artifact.getFile();
			try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
				while (true) {
					ZipEntry entry = zip.getNextEntry();
					if (entry == null) {
						break;
					}
					
					if (entry.isDirectory()) {
						continue;
					}
					
					String entryName = normalize(entry.getName());
					if (entryName.startsWith("WEB-INF/")) {
						continue;
					}
					if (entryName.startsWith("META-INF/")) {
						continue;
					}
					
					File target = new File(outputDirectory, entryName);
					File parent = target.getParentFile();
					parent.mkdirs();
					
					try (FileOutputStream out = new FileOutputStream(target)) {
						while (true) {
							int direct = zip.read(buffer);
							if (direct < 0) {
								break;
							}
							out.write(buffer, 0, direct);
						}
					}
				}
			} catch (IOException ex) {
				getLog().error("Error reading '" + file.getPath() + "'.", ex);
			}
		}
	}

	private static String normalize(String name) {
		name = name.replace('\\', '/');
		if (name.startsWith("/")) {
			name = name.substring(1);
		}
		return name;
	}

}
