package org.kordamp.duke.info;

import java.net.URI;
import run.duke.ToolFinder;
import run.duke.ToolInstaller;
import run.duke.Workbench;

public record JReleaserInstaller(String namespace, String name) implements ToolInstaller {
  public JReleaserInstaller() {
    this("org.jreleaser", "jreleaser");
  }

  @Override
  public ToolFinder install(Workbench workbench, String version) {
    var releases = "https://github.com/jreleaser/jreleaser/releases/download";
    var tag = version.equals("early-access") ? version : 'v' + version;
    var jar = "jreleaser-tool-provider-" + version + ".jar";
    var source = URI.create(releases + "/" + tag + "/" + jar);
    var folder = workbench.folders().tool(namespace, name + "@" + version);
    var target = folder.resolve(jar);
    workbench.browser().copy(source, target);
    return ToolFinder.ofJavaToolbox(folder);
  }
}
