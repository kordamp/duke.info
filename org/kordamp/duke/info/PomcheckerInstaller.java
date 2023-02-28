package org.kordamp.duke.info;

import java.net.URI;
import run.duke.ToolFinder;
import run.duke.ToolInstaller;
import run.duke.Workbench;

public record PomcheckerInstaller(String namespace, String name) implements ToolInstaller {
  public PomcheckerInstaller() {
    this("org.kordamp", "pomchecker");
  }

  @Override
  public ToolFinder install(Workbench workbench, String version) {
    var releases = "https://github.com/kordamp/pomchecker/releases/download";
    var tag = version.contains("SNAPSHOT") ? "early-access" : 'v' + version;
    var jar = "pomchecker-toolprovider-" + version + ".jar";
    var source = URI.create(releases + "/" + tag + "/" + jar);
    var folder = workbench.folders().tool(namespace, name + "@" + version);
    var target = folder.resolve(jar);
    workbench.browser().copy(source, target);
    return ToolFinder.ofJavaToolbox(folder);
  }
}
