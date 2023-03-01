module org.kordamp.duke.info {
  requires run.duke;

  exports org.kordamp.duke.info;

  provides run.duke.ToolInstaller with
      org.kordamp.duke.info.JarvizInstaller,
      org.kordamp.duke.info.JReleaserInstaller,
      org.kordamp.duke.info.PomcheckerInstaller;
}
