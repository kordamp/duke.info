package org.kordamp.duke.info;

import java.net.URI;
import run.duke.ToolFinder;
import run.duke.ToolInstaller;
import run.duke.Workbench;

public record JarVizInstaller(String namespace, String name) implements ToolInstaller {
  public JarVizInstaller() {
    this("org.kordamp", "jarviz");
  }

  @Override
  public ToolFinder install(Workbench workbench, String version) {
    var releases = "https://github.com/kordamp/jarviz/releases/download";
    var tag = version.equals("early-access") ? version : 'v' + version;
    var jar = "jarviz-tool-provider-" + version + ".jar";
    var source = URI.create(releases + "/" + tag + "/" + jar);
    var folder = workbench.folders().tool(namespace, name + "@" + version);
    var target = folder.resolve(jar);
    workbench.browser().copy(source, target);
    return ToolFinder.ofJavaToolbox(folder);
  }
}
